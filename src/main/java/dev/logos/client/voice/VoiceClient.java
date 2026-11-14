package dev.logos.client.voice;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import dev.logos.item.SpellBag;
import dev.logos.item.WandItem;
import dev.logos.net.VoiceCastPayload;
import dev.logos.runtime.VoiceCasting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The client's half of 言出法随: the player's recordings, the microphone, and hearing them name a
 * spell while they hold right-click with 言出法随 chosen. The microphone is open only while something
 * listens (a held wand, or the workbench's recording page) and shuts a few seconds after. Embedding
 * and matching run on one worker thread; what they find comes back on the client thread.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class VoiceClient {
    /** Ticks the microphone stays open after the last listener, so quick presses do not reopen it each time. */
    private static final int KEEP_OPEN = 200;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Logos voice");
        t.setDaemon(true);
        return t;
    });

    private static @Nullable CompletableFuture<SpellVoices> voices;
    private static @Nullable Microphone mic;
    private static @Nullable Endpointer endpointer;
    private static int idle;
    /** The wand press listening for a spell: it hears one name, then waits for the next press. */
    private static boolean casting, heard;

    private VoiceClient() {}

    /** The player's recordings, read on the worker the first time they are wanted. */
    public static synchronized CompletableFuture<SpellVoices> voices() {
        if (voices == null) voices = CompletableFuture.supplyAsync(() -> {
            try {
                SpellVoices v = SpellVoices.open();
                v.load();
                return v;
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, WORK);
        return voices;
    }

    /** Client scenarios keep their recordings apart from the player's own. */
    public static synchronized void useVoices(SpellVoices v) {
        voices = CompletableFuture.completedFuture(v);
    }

    /** Runs {@code job} on the voice worker, after the recordings are loaded. */
    public static <T> CompletableFuture<T> withVoices(java.util.function.Function<SpellVoices, T> job) {
        return voices().thenApplyAsync(job, WORK);
    }

    /**
     * From now on, each thing the player says goes to {@code onSpeech} on the client thread (replacing
     * any earlier listener), until {@link #stopListening} or {@code alive} turns false. False when the
     * microphone could not be opened.
     */
    public static boolean listen(Consumer<float[]> onSpeech, BooleanSupplier alive) {
        if (mic == null && !simulated) {
            try {
                mic = Microphone.open(null);
            } catch (Exception e) {
                LogosMod.LOGGER.warn("Could not open the microphone for 言出法随: {}", e.toString());
                return false;
            }
        }
        Minecraft mc = Minecraft.getInstance();
        Endpointer[] self = new Endpointer[1];
        self[0] = new Endpointer(audio -> mc.execute(() -> {
            if (endpointer == self[0]) onSpeech.accept(audio);
        }));
        endpointer = self[0];
        listener = onSpeech;
        stillWanted = alive;
        if (mic != null) mic.listen(self[0]::accept);
        idle = 0;
        return true;
    }

    public static void stopListening() {
        endpointer = null;
        listener = null;
        if (mic != null) mic.listen(null);
    }

    /** Client scenarios run without a microphone: listening then waits for {@link #simulateSpeech}. */
    public static boolean simulated;
    private static @Nullable Consumer<float[]> listener;
    private static BooleanSupplier stillWanted = () -> false;

    /** Hands {@code audio} to the current listener as if the player had just said it (client scenarios). */
    public static void simulateSpeech(float[] audio) {
        Consumer<float[]> l = listener;
        if (l != null) l.accept(audio);
    }

    public static boolean listening() {
        return endpointer != null;
    }

    /** True while the player is in the middle of saying something. */
    public static boolean speaking() {
        Endpointer e = endpointer;
        return e != null && e.speaking();
    }

    /** Microphone loudness, 0 to 1, for a level meter. */
    public static float level() {
        return mic == null ? 0 : mic.level();
    }

    @SubscribeEvent
    static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        voices();
    }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        stopCasting();
        if (mic != null) mic.close();
        mic = null;
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Player player = Minecraft.getInstance().player;
        boolean pressed = player != null && player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND
                && player.getUseItem().getItem() instanceof WandItem && VoiceCasting.armed(player.getUseItem());
        if (!pressed) {
            heard = false;
            if (casting) stopCasting();
        } else if (!casting && !heard) {
            casting = listen(VoiceClient::castHeard, () -> casting);
            if (!casting) player.sendOverlayMessage(Component.literal("言出法随：打不开麦克风，检查系统的麦克风权限").withStyle(ChatFormatting.RED));
        }
        if (endpointer != null && !stillWanted.getAsBoolean()) stopListening();
        if (mic != null && endpointer == null && ++idle > KEEP_OPEN) {
            mic.close();
            mic = null;
        }
    }

    private static void stopCasting() {
        casting = false;
        stopListening();
    }

    /** Something said during a press: matched against the voice spells the wand reaches, and named to the server if it is one. */
    private static void castHeard(float[] audio) {
        Player player = Minecraft.getInstance().player;
        if (player == null || !casting) return;
        ItemStack wand = player.getMainHandItem();
        List<SpellBag.Entry> all = SpellBag.entries(player, wand);
        List<String> names = VoiceCasting.spoken(player, wand).stream().map(e -> e.page().get(LogosMod.VOICE.get())).toList();
        withVoices(v -> v.recognize(audio, names)).thenAcceptAsync(r -> {
            if (!casting || heard) return;
            if (r == null) {
                player.sendOverlayMessage(Component.literal("🗣 还没有录制这些咒语：到术式编纂台的「言出法随」页录制").withStyle(ChatFormatting.YELLOW));
                return;
            }
            if (!r.accepted()) {
                player.sendOverlayMessage(Component.literal("🗣 没有听出咒语（最像《" + r.name() + "》）· 再念一遍").withStyle(ChatFormatting.GRAY));
                return;
            }
            for (int i = 0; i < all.size(); i++) {
                SpellBag.Entry e = all.get(i);
                if (!r.name().equals(e.page().get(LogosMod.VOICE.get())) || e.spell().isPassive()) continue;
                heard = true;
                stopCasting();
                ClientPacketDistributor.sendToServer(new VoiceCastPayload(i, r.name()));
                return;
            }
        }, Minecraft.getInstance());
    }
}
