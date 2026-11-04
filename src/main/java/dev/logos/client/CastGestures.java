package dev.logos.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.logos.LogosMod;
import dev.logos.item.StaffItem;
import dev.logos.runtime.CastPose;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * The one-shot gestures a cast plays by the form it leads with ({@link CastPose#gesture}): 魔弹 pushes
 * forward and recoils, 射线 points sharply, 领域 lifts and draws two quick circles in the air, 炼成体 sweeps an
 * arc sideways, 游脉 points at the ground and flicks up, 缠绕 zigzags with the grip leading the head, and a
 * cast with no leading form gives a light flick. Each is a {@link Frame} along its progress, drawn the same
 * way in third person (arm, then the item turned in the hand) and first person (the hand moved in view).
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
final class CastGestures {
    /** A wand's model leans back in the hand; this much more forward turn lays it along the arm as a staff is. */
    private static final float WAND_LEAN = 0.5f;
    /** A staff is gripped at its middle; it slides this far along itself so that its head leads, as in {@link BeamPose}. */
    private static final float STAFF_REACH = 0.6f;

    private CastGestures() {}

    /**
     * One moment of a gesture. Third person: the arm's pitch and yaw from the gaze ({@code pitch} -π/2 is level
     * with it, {@code yaw} outward from the body), its roll outward, how far it pushes forward in model pixels,
     * and the item turned forward ({@code tilt}) and leaned sideways ({@code lean}) in the hand, in radians.
     * First person: the hand moved by {@code fx} (outward) {@code fy} {@code fz} and turned by {@code fPitch}
     * (negative tips the head away) and {@code fRoll}, in degrees.
     */
    record Frame(float pitch, float yaw, float roll, float push, float tilt, float lean, float fx, float fy, float fz, float fPitch, float fRoll) {}

    /** A gesture: its length in ticks and its frame at progress 0 to 1. */
    private record Gesture(int ticks, java.util.function.Function<Float, Frame> at) implements CastPoses.Pose {
        @Override
        public int duration() {
            return ticks;
        }

        private Frame frame(float age) {
            return at.apply(Mth.clamp(age / ticks, 0, 1));
        }

        @Override
        public void arm(HumanoidModel<?> model, HumanoidRenderState state, HumanoidArm arm, ItemStack held, float weight, float age) {
            Frame f = frame(age);
            ModelPart part = arm == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
            float side = arm == HumanoidArm.RIGHT ? 1 : -1;
            part.xRot = Mth.lerp(weight, part.xRot, model.head.xRot + f.pitch);
            part.yRot = Mth.lerp(weight, part.yRot, model.head.yRot + side * f.yaw);
            part.zRot = Mth.lerp(weight, part.zRot, side * f.roll);
            part.z -= f.push * weight;
        }

        @Override
        public void item(ArmedEntityRenderState state, PoseStack pose, HumanoidArm arm, ItemStack held, float weight, float age) {
            Frame f = frame(age);
            float side = arm == HumanoidArm.RIGHT ? 1 : -1;
            boolean staff = held.getItem() instanceof StaffItem;
            pose.mulPose(Axis.XP.rotation(-(f.tilt + (staff ? 0 : WAND_LEAN)) * weight));
            pose.mulPose(Axis.ZP.rotation(side * f.lean * weight));
            if (staff) pose.translate(0, STAFF_REACH * weight, 0);
        }

        @Override
        public void hand(PoseStack pose, LocalPlayer player, HumanoidArm arm, ItemStack held, float partialTick, float weight, float age) {
            Frame f = frame(age);
            int side = arm == HumanoidArm.RIGHT ? 1 : -1;
            pose.translate(side * f.fx * weight, f.fy * weight, f.fz * weight);
            pose.mulPose(Axis.XP.rotationDegrees(f.fPitch * weight));
            pose.mulPose(Axis.ZP.rotationDegrees(side * f.fRoll * weight));
        }
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        register(CastPose.PROJECTILE, 10, CastGestures::projectile);
        register(CastPose.RAY, 7, CastGestures::ray);
        register(CastPose.FIELD, 26, CastGestures::field);
        register(CastPose.CONSTRUCT, 12, CastGestures::construct);
        register(CastPose.VEIN, 13, CastGestures::vein);
        register(CastPose.IMBUE, 18, CastGestures::imbue);
        register(CastPose.CAST, 8, CastGestures::cast);
    }

    private static void register(Identifier id, int ticks, java.util.function.Function<Float, Frame> at) {
        CastPoses.register(id, new Gesture(ticks, at));
    }

    private static float ease(float t) {
        return CastPoses.smooth(t);
    }

    /** 0 → 1 as {@code p} goes from {@code a} to {@code b}, eased. */
    private static float span(float p, float a, float b) {
        return ease((p - a) / (b - a));
    }

    /** 魔弹: a push straight out along the gaze, then a recoil that kicks the head up a little and settles. */
    private static Frame projectile(float p) {
        float out = span(p, 0, 0.3f), back = span(p, 0.3f, 1);
        float pitch = Mth.lerp(out, -1.3f, -1.55f) + 0.15f * back - 0.25f * Mth.sin(Mth.PI * back);
        float push = 3 * out - 3.4f * back + 0.4f * back * back;
        float tilt = 1.3f * out - 0.6f * back;
        return new Frame(pitch, -0.1f, 0.04f, push, tilt, 0,
                -0.1f * out, 0.05f * out, -0.3f * out + 0.3f * back, -30 * out + 18 * back, 0);
    }

    /** 射线: a sharp point down the gaze, held for a beat. */
    private static Frame ray(float p) {
        float k = span(p, 0, 0.3f);
        return new Frame(-1.57f, -0.1f, 0.04f, 1.5f * k, 1.5f * k, 0, -0.14f * k, 0.1f * k, -0.18f * k, -42 * k, 0);
    }

    /** 领域: the arm lifts high, then the head draws two quick full circles (720°) in the air. */
    private static Frame field(float p) {
        float up = span(p, 0, 0.22f);
        float c = Mth.clamp((p - 0.22f) / 0.66f, 0, 1);
        float turn = 4 * Mth.PI * ease(c);
        // The circles widen in and narrow out, so they start and end on the raised arm.
        float r = Mth.sin(Mth.PI * c);
        float pitch = Mth.lerp(up, -1.35f, -2.6f) + 0.32f * r * Mth.sin(turn);
        float roll = 0.32f * r * Mth.cos(turn);
        return new Frame(pitch, 0.05f, 0.1f * up + roll, 0, 1.0f * up, 0,
                -0.1f * up + 0.16f * r * Mth.cos(turn), 0.18f * up + 0.16f * r * Mth.sin(turn), -0.05f * up, 12 * up, 0);
    }

    /** 炼成体: a level arc swept from out at the side across in front of the body. */
    private static Frame construct(float p) {
        float s = span(p, 0.08f, 0.85f);
        float yaw = Mth.lerp(s, 0.9f, -0.75f);
        float lift = Mth.sin(Mth.PI * s);
        return new Frame(-1.35f - 0.22f * lift, yaw, 0.04f, 0, 1.1f, 0,
                Mth.lerp(s, 0.28f, -0.42f), 0.08f * lift, -0.05f, -30, Mth.lerp(s, -22, 22));
    }

    /** 游脉: the head points at the ground ahead, then flicks up. */
    private static Frame vein(float p) {
        float down = span(p, 0, 0.4f), flick = span(p, 0.45f, 0.7f);
        float pitch = Mth.lerp(down, -1.35f, -0.7f) + Mth.lerp(flick, 0, -1.35f);
        float tilt = Mth.lerp(down, 0.4f, 1.4f) - 0.5f * flick;
        return new Frame(pitch, -0.08f, 0.04f, 0, tilt, 0,
                -0.08f * down, -0.12f * down + 0.26f * flick, -0.1f * down, -75 * down + 90 * flick, 0);
    }

    /**
     * 缠绕: the head zigzags across in three strokes, stepping down each time, while the grip leads it:
     * the item leans against the way it moves, so the hand is always ahead of the head.
     */
    private static Frame imbue(float p) {
        float s = Mth.clamp((p - 0.12f) / 0.76f, 0, 1) * 3;
        int stroke = Math.min(2, (int) s);
        float f = s - stroke;
        // Strokes run out → in, in → out, out → in (dir -1 is inward); before and after, the head moves to the start and back.
        float dir = stroke % 2 == 0 ? -1 : 1;
        float yaw, speed;
        if (p < 0.12f) {
            yaw = 0.5f * span(p, 0, 0.12f);
            speed = 0;
        } else if (p > 0.88f) {
            yaw = Mth.lerp(span(p, 0.88f, 1), -0.5f, 0);
            speed = 0;
        } else {
            yaw = Mth.lerp(ease(f), -0.5f * dir, 0.5f * dir);
            speed = dir * Mth.sin(Mth.PI * f);
        }
        // Held fairly upright, so the lean against the stroke reads as the head dragging behind the hand. Swinging
        // the arm out turns the item out with it, so the lean takes that back as well.
        float pitch = -1.6f + 0.12f * s;
        return new Frame(pitch, yaw, 0.04f, 0, 0.6f, 0.95f * speed + 0.6f * yaw,
                0.2f * yaw, 0.1f - 0.04f * s, -0.05f, -20, 38 * speed);
    }

    /** No leading form: a light flick of the head, forward and back. */
    private static Frame cast(float p) {
        float up = span(p, 0, 0.35f), flick = span(p, 0.35f, 0.7f);
        return new Frame(Mth.lerp(up, -0.6f, -1.0f) + 0.3f * flick, -0.08f, 0.04f, 0, 0.3f * up + 0.6f * flick, 0,
                0, 0.03f * up, -0.06f * flick, 12 * up - 36 * flick, 0);
    }
}
