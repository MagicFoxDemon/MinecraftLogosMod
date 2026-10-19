package dev.logos.client;

import dev.logos.LogosMod;
import dev.logos.runtime.ManaField;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The air where mana has spread: around the camera, now and then a mote appears in a chunk the
 * server says holds mana, more often the more it holds. A full chunk shows a few dozen faint motes
 * at a time; a thin one, one now and then; an empty one, none.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
final class ManaFieldClient {
    /** Tries a tick, and how far around the camera. */
    private static final int TRIES = 6, REACH = 12;
    /** Chance a try in a full chunk makes a mote. */
    private static final float FULL = 0.16f;

    private ManaFieldClient() {}

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused() || !ManaField.clientKnowsAny(mc.level)) return;
        RandomSource random = mc.level.getRandom();
        var eye = mc.gameRenderer.getMainCamera().position();
        for (int i = 0; i < TRIES; i++) {
            double x = eye.x + (random.nextDouble() * 2 - 1) * REACH;
            // Mostly about head height and above, where they are seen; little below the feet.
            double y = eye.y + random.nextDouble() * 10 - 4;
            double z = eye.z + (random.nextDouble() * 2 - 1) * REACH;
            BlockPos pos = BlockPos.containing(x, y, z);
            int level = ManaField.clientLevel(mc.level, pos);
            if (level <= 0 || random.nextFloat() >= FULL * level / ManaField.LEVELS) continue;
            if (!mc.level.getBlockState(pos).isAir()) continue;
            mc.level.addParticle(LogosMod.MANA_MOTE.get(), x, y, z, 0, 0, 0);
        }
    }
}
