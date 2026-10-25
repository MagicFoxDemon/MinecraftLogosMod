package dev.logos.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import dev.logos.LogosMod;
import dev.logos.net.SageAdminStatePayload;
import dev.logos.net.SageAskPayload;
import dev.logos.net.SageCancelPayload;
import dev.logos.net.SageReplyPayload;
import dev.logos.net.SageStatusPayload;
import dev.logos.sage.OpenAiChat;
import dev.logos.sage.SageAgent;
import dev.logos.sage.SageRunes;
import dev.logos.sage.SageServer;
import dev.logos.sage.SageSettings;
import dev.logos.sage.SageTask;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

/**
 * Where a question to the sage goes. The server's sage when the server offers one (and the player
 * has not chosen their own first), else the player's own endpoint, run right here on a worker
 * thread. Either way the answer comes back on the client thread.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class SageClient {
    public enum Route { SERVER, LOCAL, NONE }

    /** Something that can be stopped: the question in flight. */
    public interface Handle {
        void cancel();
    }

    private record Pending(SageAgent.Listener listener, Consumer<SageAgent.Outcome> done) {}

    /** Set by the server when it joins: whether its sage is offered, and whether this player may set it up. */
    static boolean serverOffered, admin;
    /** The open server-settings page, waiting for the server's settings. */
    static Consumer<SageAdminStatePayload> adminListener = s -> {};
    private static final Map<Integer, Pending> PENDING = new HashMap<>();
    private static int nextId = 1;
    /** Set by client scenarios: answers in place of the player's own endpoint, without a network. */
    public static dev.logos.sage.SageModel testModel;

    private SageClient() {}

    public static Route route() {
        if (testModel != null) return Route.LOCAL;
        SageSettings.Client own = SageSettings.client();
        if (serverOffered && (own.preferServer() || !own.endpoint().configured())) return Route.SERVER;
        return own.endpoint().configured() ? Route.LOCAL : Route.NONE;
    }

    /** Asks the sage; {@code listener} hears its progress and {@code done} its answer, both on the client thread. */
    static Handle ask(List<SageAgent.Turn> history, String message, String current, int capacity, boolean emitter, SageAgent.Listener listener, Consumer<SageAgent.Outcome> done) {
        Minecraft mc = Minecraft.getInstance();
        String lang = mc.getLanguageManager().getSelected();
        int id = nextId++;
        switch (route()) {
            case SERVER -> {
                PENDING.put(id, new Pending(listener, done));
                ClientPacketDistributor.sendToServer(new SageAskPayload(id, lang, SageServer.writeHistory(history), message, current, capacity, emitter));
                return () -> {
                    if (PENDING.remove(id) != null) ClientPacketDistributor.sendToServer(new SageCancelPayload(id));
                };
            }
            case LOCAL -> {
                var req = new SageAgent.Request(lang, SageRunes.available(mc.player), history, message, current, capacity, emitter);
                var model = testModel != null ? testModel : new OpenAiChat(SageSettings.client().endpoint(), java.time.Duration.ofSeconds(90));
                SageTask task = new SageTask(id);
                SageServer.WORKERS.execute(() -> {
                    SageAgent.Outcome out = null;
                    try {
                        if (task.start()) out = SageAgent.run(model, req, (k, a) -> mc.execute(() -> {
                            if (!task.cancelled()) listener.status(k, a);
                        }));
                    } catch (InterruptedException ex) {
                        // cancelled
                    } catch (RuntimeException ex) {
                        LogosMod.LOGGER.warn("Sage request failed: {}", ex.toString());
                        out = SageAgent.Outcome.failed("logos.sage.error.internal", ex.getClass().getSimpleName(), "");
                    } finally {
                        task.finish();
                    }
                    SageAgent.Outcome answer = out;
                    if (answer != null) mc.execute(() -> {
                        if (!task.cancelled()) done.accept(answer);
                    });
                });
                return task::cancel;
            }
            default -> {
                done.accept(SageAgent.Outcome.failed("logos.sage.error.unconfigured", "", ""));
                return () -> {};
            }
        }
    }

    @SubscribeEvent
    static void onRegisterPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(SageReplyPayload.TYPE, (p, ctx) -> {
            Pending pending = p.done() ? PENDING.remove(p.id()) : PENDING.get(p.id());
            if (pending == null) return;
            if (p.done()) pending.done().accept(p.outcome());
            else pending.listener().status(p.key(), p.arg());
        });
        event.register(SageStatusPayload.TYPE, (p, ctx) -> {
            serverOffered = p.offered();
            admin = p.admin();
        });
        event.register(SageAdminStatePayload.TYPE, (p, ctx) -> adminListener.accept(p));
    }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        serverOffered = false;
        admin = false;
        PENDING.clear();
    }
}
