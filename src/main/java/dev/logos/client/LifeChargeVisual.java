package dev.logos.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The life charge (see CHARGE_VFX.md): the shared charge in life's green, its hot centre warmed towards the
 * gold of the life ray and beam, and for grand and colossal charges a few leaves drifting slowly down round
 * the caster, swaying and turning over as they fall (drawn as the life beam's leaves, {@link LifeBeamParts}).
 * Ordinary and elaborate charges are colour only.
 */
final class LifeChargeVisual extends ChargeCore {
    /** Green as before, with a warm golden white at the centre (after the ray's 0xF6FFE0 and the beam's gold motes). */
    private static final ChargeStyle STYLE = ChargeStyle.colours(0xFAF4C4, 0x40E050, 0x1A9A30);
    /** Leaf tints: the beam's greens, now and then one of gold. */
    private static final int[] LEAF = {0x2FB83A, 0x58E83A, 0x8FE83A, 0xB8F25A, 0x46C95A, 0xF0D262};

    private final List<LifeBeamParts.Mote> leaves = new ArrayList<>();
    private float owed;

    LifeChargeVisual() {
        super(STYLE);
    }

    @Override
    public boolean tick(Level level, LiveCharge c) {
        boolean core = super.tick(level, c);
        double now = c.age(0);
        leaves.removeIf(m -> now > m.death());
        LiveCharge.Phase phase = c.phase();
        if (c.tier() >= 2 && !phase.over()) {
            float more = phase == LiveCharge.Phase.HELD ? 1 : ChargeParts.smooth(.1f, .5f, c.progress(0));
            // Grand: one now and then; colossal: a light, steady fall.
            float rate = c.tier() == 3 ? .16f : .055f;
            int cap = c.tier() == 3 ? 16 : 6;
            for (owed += rate * more; owed >= 1; owed--) if (leaves.size() < cap) leaf(level.getRandom(), c, now);
        }
        return core || !leaves.isEmpty();
    }

    private void leaf(RandomSource r, LiveCharge c, double now) {
        float scale = c.height() / 1.8f;
        double angle = r.nextDouble() * Math.PI * 2;
        double radius = (c.tier() == 3 ? 1.3 + 2.4 * r.nextDouble() : 1.0 + 1.4 * r.nextDouble()) * scale;
        // In first person the leaves keep their distance, so none drifts big and blurred across the view.
        if (c.firstPerson()) radius = (2.8 + 1.6 * r.nextDouble()) * scale;
        double y = c.height() * (c.tier() == 3 ? 1.5 + .7 * r.nextDouble() : 1.2 + .5 * r.nextDouble());
        Vec3 at = c.feet(0).add(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
        int tint = LEAF[r.nextInt(5) == 0 ? LEAF.length - 1 : r.nextInt(LEAF.length - 1)];
        leaves.add(new LifeBeamParts.Mote(true, at, Vec3.ZERO, -(.012 + .008 * r.nextDouble()) * scale,
                (r.nextDouble() - .5) * .6 * scale, (r.nextDouble() - .5) * .6 * scale, r.nextDouble() * 6.28, now, 90 + r.nextInt(30),
                tint, r.nextInt(4), (.14f + .08f * r.nextFloat()) * scale, (r.nextFloat() - .5f) * .1f, r.nextFloat() * 6.28f));
    }

    @Override
    public void submit(Level level, LiveCharge c, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        super.submit(level, c, camera, partialTick, pose, out);
        if (leaves.isEmpty()) return;
        ChargeParts.View view = ChargeParts.View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        LifeBeamParts.motes(pose, out, leaves, c.age(partialTick), view.cam(), view.right(), view.up());
        pose.popPose();
    }
}
