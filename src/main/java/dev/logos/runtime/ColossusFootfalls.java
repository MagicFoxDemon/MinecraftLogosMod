package dev.logos.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** The colossus's footfalls, on the client: a heavy step heard each time one of its feet comes down. */
final class ColossusFootfalls {
    /** A planted foot's ankle sits about 0.27 above its feet; lifted in a step it rises past this, and it lands below the next. */
    private static final double FOOT_LIFTED = 0.32, FOOT_DOWN = 0.29;

    private final AlchemicalColossus c;
    /** Client only: whether each foot (left, right) is lifted, to hear it come down; and the beat of unseen steps. */
    private final boolean[] footUp = new boolean[2];
    private int stepClock, footfalls;

    ColossusFootfalls(AlchemicalColossus c) {
        this.c = c;
    }

    /**
     * Client: a heavy footfall each time a foot comes down, heard as the model's foot lands (from the
     * drawn foot bones). Not drawn (behind the camera), it is heard on the gait's beat instead: every
     * half run cycle, or every half walk cycle.
     */
    void stepSounds() {
        boolean seen = false;
        String[] feet = {"left_foot", "right_foot"};
        for (int i = 0; i < 2; i++) {
            Vec3 foot = c.drawnBone(feet[i]);
            if (foot == null) continue;
            seen = true;
            double h = foot.y - c.getY();
            if (h > FOOT_LIFTED) footUp[i] = true;
            else if (h < FOOT_DOWN && footUp[i]) {
                footUp[i] = false;
                footfall(foot);
            }
        }
        if (seen || c.isDormant() || Math.hypot(c.getX() - c.xo, c.getZ() - c.zo) < 0.01) {
            stepClock = 0;
            return;
        }
        int every = c.isRunning() || c.isDriven() ? Math.max(4, Math.round(11 / c.runPace)) : 30;
        if (++stepClock >= every) {
            stepClock = 0;
            footfall(c.position());
        }
    }

    /** One heavy step: a deep metal thud and the ground's own step, low. */
    private void footfall(Vec3 at) {
        footfalls++;
        c.level().playLocalSound(at.x, c.getY(), at.z, SoundEvents.IRON_GOLEM_STEP, c.getSoundSource(), 1.3f, 0.45f + c.getRandom().nextFloat() * 0.1f, false);
        BlockState ground = c.level().getBlockState(BlockPos.containing(at.x, c.getY() - 0.2, at.z));
        if (!ground.isAir()) {
            SoundType type = ground.getSoundType();
            c.level().playLocalSound(at.x, c.getY(), at.z, type.getStepSound(), c.getSoundSource(), type.getVolume(), type.getPitch() * 0.55f, false);
        }
    }

    /** Client: how many footfalls it has made (for checks). */
    int footfalls() {
        return footfalls;
    }
}
