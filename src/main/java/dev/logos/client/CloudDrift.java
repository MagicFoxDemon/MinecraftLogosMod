package dev.logos.client;

import dev.logos.LogosMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Clouds move with game ticks, not with the day's clock, so a time-skip would leave them crawling
 * under a racing sun. This reads how fast the dimension's clock runs (its rate reaches clients
 * anyway) and lets the clouds drift that much faster, up to a pace that still reads as clouds.
 * The extra drift is kept, so clouds never jump back when the skip ends.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class CloudDrift {
    /** Fastest the clouds go, times their normal speed. */
    static final double MAX = 30;

    private static ClientLevel seen;
    private static long lastClock = -1;
    private static long lastGame;
    private static double factor = 1;
    /** Extra cloud ticks gathered so far, beyond game time. */
    private static double extra;

    private CloudDrift() {}

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != seen) {
            seen = level;
            lastClock = -1;
            factor = 1;
        }
        var clock = level == null ? null : level.dimensionType().defaultClock().orElse(null);
        if (clock == null) return;
        long now = level.clockManager().getTotalTicks(clock);
        long game = level.getGameTime();
        if (lastClock >= 0 && game > lastGame) {
            // One tick's worth of clock per game tick is normal; a jump (sleep, /time set) is not a rate and is capped away.
            double rate = (double) (now - lastClock) / (game - lastGame);
            factor = Math.clamp(rate, 1, MAX);
            extra += (factor - 1) * (game - lastGame);
        }
        lastClock = now;
        lastGame = game;
    }

    /** Game time as the clouds should see it. */
    public static long gameTime(long gameTime) {
        return gameTime + (long) Math.floor(extra);
    }

    /** Partial tick as the clouds should see it: faster within the tick, plus the fraction of the extra drift. */
    public static float partialTick(float partialTicks) {
        return (float) (partialTicks * factor + (extra - Math.floor(extra)));
    }
}
