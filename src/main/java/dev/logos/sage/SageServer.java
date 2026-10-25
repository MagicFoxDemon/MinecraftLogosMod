package dev.logos.sage;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.logos.LogosMod;
import dev.logos.net.SageAdminPayload;
import dev.logos.net.SageAdminStatePayload;
import dev.logos.net.SageAskPayload;
import dev.logos.net.SageReplyPayload;
import dev.logos.net.SageStatusPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The server's sage: players send their conversation up, the agent runs here with the server's
 * key, and only the answer goes back down. The key and the provider settings never reach a client;
 * players only learn whether the sage is offered. Limits: one question per player at a time, a
 * cooldown, a daily count, and a cap on questions running at once.
 */
@EventBusSubscriber(modid = LogosMod.MODID)
public final class SageServer {
    /** Agent loops wait on the network, not the CPU: they run off the server thread, on daemon threads. */
    public static final ExecutorService WORKERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "logos-sage");
        t.setDaemon(true);
        return t;
    });
    static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final int MAX_TURNS = 24;

    private record Day(LocalDate date, int count) {}

    private static final Map<UUID, SageTask> RUNNING = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_ASK = new ConcurrentHashMap<>();
    private static final Map<UUID, Day> DAILY = new ConcurrentHashMap<>();
    private static final AtomicInteger ACTIVE = new AtomicInteger();

    private SageServer() {}

    /** Operators may set the server's sage up; so may the owner of a singleplayer world, cheats or not. */
    public static boolean admin(ServerPlayer sp) {
        return sp.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) || sp.level().getServer().isSingleplayerOwner(sp.nameAndId());
    }

    private static boolean offered(SageSettings.Server s) {
        return s.offered() && s.endpoint().configured();
    }

    public static void ask(ServerPlayer sp, SageAskPayload p) {
        SageSettings.Server s = SageSettings.server();
        UUID who = sp.getUUID();
        if (!offered(s)) {
            reply(sp, p.id(), SageAgent.Outcome.failed("logos.sage.error.server_off", "", ""));
            return;
        }
        if (RUNNING.containsKey(who)) {
            reply(sp, p.id(), SageAgent.Outcome.failed("logos.sage.error.busy", "", ""));
            return;
        }
        long now = System.currentTimeMillis();
        long wait = LAST_ASK.getOrDefault(who, 0L) + s.cooldownSeconds() * 1000L - now;
        if (wait > 0) {
            reply(sp, p.id(), SageAgent.Outcome.failed("logos.sage.error.cooldown", String.valueOf((wait + 999) / 1000), ""));
            return;
        }
        Day day = DAILY.get(who);
        LocalDate today = LocalDate.now();
        int used = day != null && day.date().equals(today) ? day.count() : 0;
        if (s.dailyLimit() > 0 && used >= s.dailyLimit()) {
            reply(sp, p.id(), SageAgent.Outcome.failed("logos.sage.error.daily", String.valueOf(s.dailyLimit()), ""));
            return;
        }
        if (ACTIVE.incrementAndGet() > s.maxConcurrent()) {
            ACTIVE.decrementAndGet();
            reply(sp, p.id(), SageAgent.Outcome.failed("logos.sage.error.full", "", ""));
            return;
        }
        LAST_ASK.put(who, now);
        DAILY.put(who, new Day(today, used + 1));
        MinecraftServer server = sp.level().getServer();
        SageAgent.Request req = new SageAgent.Request(p.lang(), SageRunes.available(sp), readHistory(p.history()), p.message(), p.current(),
                Math.max(0, p.capacity()), p.emitter());
        SageModel model = new OpenAiChat(s.endpoint(), TIMEOUT);
        SageTask running = new SageTask(p.id());
        RUNNING.put(who, running);
        WORKERS.execute(() -> {
            SageAgent.Outcome out = null;
            try {
                if (running.start()) {
                    out = SageAgent.run(model, req, (key, arg) -> server.execute(() -> send(server, who, SageReplyPayload.status(p.id(), key, arg))));
                }
            } catch (InterruptedException ex) {
                // cancelled: the player is no longer waiting
            } catch (RuntimeException ex) {
                LogosMod.LOGGER.warn("Sage request failed: {}", ex.toString());
                out = SageAgent.Outcome.failed("logos.sage.error.internal", ex.getClass().getSimpleName(), "");
            } finally {
                running.finish();
                ACTIVE.decrementAndGet();
                RUNNING.remove(who, running);
            }
            if (out != null) {
                SageAgent.Outcome done = out;
                server.execute(() -> send(server, who, SageReplyPayload.of(p.id(), done)));
            }
        });
    }

    public static void cancel(ServerPlayer sp, int id) {
        SageTask r = RUNNING.get(sp.getUUID());
        if (r != null && r.id == id) r.cancel();
    }

    /** Sends the settings page, or saves it and tells everyone whether the sage is now offered. */
    public static void admin(ServerPlayer sp, SageAdminPayload p) {
        if (!admin(sp)) return;
        if (p.save()) {
            SageSettings.Server old = SageSettings.server();
            String key = p.clearKey() ? "" : p.key().isBlank() ? old.endpoint().apiKey() : p.key();
            SageSettings.saveServer(new SageSettings.Server(p.offered(), p.cooldownSeconds(), p.dailyLimit(), p.maxConcurrent(),
                    new SageEndpoint(p.url(), p.model(), key)));
            LogosMod.LOGGER.info("{} changed the server sage settings", sp.getPlainTextName());
            for (ServerPlayer other : sp.level().getServer().getPlayerList().getPlayers()) syncStatus(other);
        }
        SageSettings.Server s = SageSettings.server();
        SageSettings.Loaded e = SageSettings.serverEndpoint();
        PacketDistributor.sendToPlayer(sp, new SageAdminStatePayload(s.offered(), e.endpoint().url(), e.endpoint().model(), e.endpoint().keyHint(),
                e.urlFromEnv(), e.modelFromEnv(), e.keyFromEnv(), s.cooldownSeconds(), s.dailyLimit(), s.maxConcurrent()));
    }

    public static void syncStatus(ServerPlayer sp) {
        // Unasked, so only to clients that know the payload (not test players or other connections).
        if (sp.connection.hasChannel(SageStatusPayload.TYPE)) {
            PacketDistributor.sendToPlayer(sp, new SageStatusPayload(offered(SageSettings.server()), admin(sp)));
        }
    }

    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) syncStatus(sp);
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SageTask r = RUNNING.get(event.getEntity().getUUID());
        if (r != null) r.cancel();
    }

    private static void reply(ServerPlayer sp, int id, SageAgent.Outcome o) {
        PacketDistributor.sendToPlayer(sp, SageReplyPayload.of(id, o));
    }

    /** To whoever asked, if they are still here. */
    private static void send(MinecraftServer server, UUID who, SageReplyPayload p) {
        ServerPlayer sp = server.getPlayerList().getPlayer(who);
        if (sp != null) PacketDistributor.sendToPlayer(sp, p);
    }

    /** A conversation as it travels: {@code [{"p":true,"t":"…","s":"…"}, …]}, the latest {@link #MAX_TURNS} kept. */
    public static String writeHistory(List<SageAgent.Turn> turns) {
        int from = Math.max(0, turns.size() - MAX_TURNS);
        String s = writeTurns(turns.subList(from, turns.size()));
        // Too long for the packet: drop the oldest turns until it fits.
        return s.length() <= SageAskPayload.MAX_HISTORY || turns.size() - from <= 1 ? s : writeHistory(turns.subList(from + 1, turns.size()));
    }

    public static String writeTurns(List<SageAgent.Turn> turns) {
        JsonArray a = new JsonArray();
        for (SageAgent.Turn t : turns) {
            JsonObject o = new JsonObject();
            o.addProperty("p", t.player());
            o.addProperty("t", t.text());
            o.addProperty("s", t.source());
            a.add(o);
        }
        return a.toString();
    }

    public static List<SageAgent.Turn> readHistory(String json) {
        return readTurns(json, MAX_TURNS);
    }

    public static List<SageAgent.Turn> readTurns(String json, int max) {
        List<SageAgent.Turn> out = new ArrayList<>();
        try {
            JsonElement e = JsonParser.parseString(json == null || json.isBlank() ? "[]" : json);
            if (!e.isJsonArray()) return out;
            for (JsonElement it : e.getAsJsonArray()) {
                if (!it.isJsonObject() || out.size() >= max) continue;
                JsonObject o = it.getAsJsonObject();
                out.add(new SageAgent.Turn(o.has("p") && o.get("p").getAsBoolean(), o.has("t") ? o.get("t").getAsString() : "",
                        o.has("s") ? o.get("s").getAsString() : ""));
            }
        } catch (RuntimeException ignored) {
            // a malformed history is answered as a fresh conversation
        }
        return out;
    }
}
