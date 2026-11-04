package dev.logos.client;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.LogosMod;
import dev.logos.net.CastPosePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;
import net.neoforged.neoforge.client.IArmPoseTransformer;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The client half of {@link dev.logos.runtime.CastPose}: which casters hold which pose, and how each pose
 * id is drawn. A pose is registered once ({@link #register}, from client setup); the item it is held
 * with decides which hand shows it, by returning {@link #armPose} from its {@code getArmPose} and calling
 * {@link #handInView} from its {@code applyForgeHandTransform} (Logos' wands do so in {@link WandPose}).
 * A pose eases in when it starts and back out when it is let go, and a hold not heard of for a while is
 * let go too.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class CastPoses {
    /** Ticks a pose takes to come on, and to go back to the plain hold once let go. */
    public static final float EASE_IN = 4, EASE_OUT = 5;
    /** Holds are sent again every {@link dev.logos.runtime.CastPose#REFRESH_TICKS}; one unheard of this long is over. */
    private static final int STALE_TICKS = 30;

    /**
     * How one pose is drawn. Each method blends toward the pose by {@code weight}, 0 (the plain hold) to 1;
     * the forms with {@code age} (ticks since it began, partial tick included) are the ones called, and by
     * default hand on to the forms without, which suit a pose that stays still.
     */
    public interface Pose {
        /**
         * Ticks a one-shot pose ({@link dev.logos.runtime.CastPose#play}) plays for, its easing in and out
         * included; it ends by itself after that. 0 (the default) for a pose held until let go.
         */
        default int duration() {
            return 0;
        }

        /** Third person: the arm holding the item, which still has the plain item hold on it. */
        default void arm(HumanoidModel<?> model, HumanoidRenderState state, HumanoidArm arm, ItemStack held, float weight) {}

        default void arm(HumanoidModel<?> model, HumanoidRenderState state, HumanoidArm arm, ItemStack held, float weight, float age) {
            arm(model, state, arm, held, weight);
        }

        /** Third person: turns the item in the hand, after it has been put there (as a wrist would). */
        default void item(ArmedEntityRenderState state, PoseStack pose, HumanoidArm arm, ItemStack held, float weight) {}

        default void item(ArmedEntityRenderState state, PoseStack pose, HumanoidArm arm, ItemStack held, float weight, float age) {
            item(state, pose, arm, held, weight);
        }

        /** First person: moves the hand from where the plain hold puts it (already applied to {@code pose}). */
        default void hand(PoseStack pose, LocalPlayer player, HumanoidArm arm, ItemStack held, float partialTick, float weight) {}

        default void hand(PoseStack pose, LocalPlayer player, HumanoidArm arm, ItemStack held, float partialTick, float weight, float age) {
            hand(pose, player, arm, held, partialTick, weight);
        }
    }

    /** The arm pose any posed hand is drawn with, added to {@link HumanoidModel.ArmPose} through META-INF/enumextensions.json. */
    public static final EnumProxy<HumanoidModel.ArmPose> HOLD = new EnumProxy<>(HumanoidModel.ArmPose.class,
            false, false, (IArmPoseTransformer) CastPoses::poseArm);

    private static final Map<Identifier, Pose> POSES = new ConcurrentHashMap<>();
    /** Per entity id, its holds by hold id, in the order they began. */
    private static final Map<Integer, LinkedHashMap<Integer, Held>> HELD = new HashMap<>();

    private static final class Held {
        final Identifier pose;
        final long start;
        /** A one-shot's length in ticks; 0 for a hold. */
        final int duration;
        long seen;
        /** Client tick it was let go at; -1 while held. */
        long released = -1;

        Held(Identifier pose, long now) {
            this.pose = pose;
            this.start = this.seen = now;
            Pose p = POSES.get(pose);
            this.duration = p == null ? 0 : Math.max(0, p.duration());
        }

        float age(long now, float partialTick) {
            return now - start + partialTick;
        }

        /** Whether it still shows as held: a hold until let go, a one-shot until it has played. */
        boolean on(long now) {
            return duration > 0 ? now - start < duration : released < 0;
        }

        float weight(long now, float partialTick) {
            float t = age(now, partialTick);
            if (duration > 0) {
                // A short gesture eases in and out over at most a quarter of itself each way.
                float in = Math.min(EASE_IN, duration / 4f), out = Math.min(EASE_OUT, duration / 4f);
                return Math.min(smooth(t / in), 1 - smooth((t - (duration - out)) / out));
            }
            float in = smooth(t / EASE_IN);
            if (released < 0) return in;
            return Math.min(in, 1 - smooth((now - released + partialTick) / EASE_OUT));
        }

        boolean over(long now) {
            return duration > 0 ? now - start > duration : released >= 0 && now - released > EASE_OUT;
        }
    }

    /** The pose an entity shows now, how far it is on, and how many ticks since it began. */
    public record Active(Identifier pose, float weight, float age) {}

    private CastPoses() {}

    /** Says how {@code id} is drawn. Call from client setup; a later call for the same id replaces it. */
    public static void register(Identifier id, Pose pose) {
        POSES.put(id, pose);
    }

    public static void receive(CastPosePayload p) {
        long now = ClientCharges.ticks();
        var holds = HELD.computeIfAbsent(p.entityId(), k -> new LinkedHashMap<>());
        Held h = holds.get(p.hold());
        if (!p.on()) {
            if (h != null && h.released < 0) h.released = now;
            return;
        }
        if (h == null || h.released >= 0) {
            h = new Held(p.pose(), now);
            holds.remove(p.hold());
            holds.put(p.hold(), h);
        }
        h.seen = now;
    }

    /**
     * What {@code entityId} shows now: the latest pose still on (a hold not let go, a one-shot still
     * playing), else the latest still easing out. Null if none.
     */
    public static @Nullable Active of(int entityId, float partialTick) {
        var holds = HELD.get(entityId);
        if (holds == null || holds.isEmpty()) return null;
        long now = ClientCharges.ticks();
        Held shown = null;
        for (Held h : holds.values()) {
            if (!POSES.containsKey(h.pose) || h.over(now)) continue;
            if (h.on(now) || shown == null || !shown.on(now)) shown = h;
        }
        if (shown == null) return null;
        float w = shown.weight(now, partialTick);
        return w > 0 ? new Active(shown.pose, w, shown.age(now, partialTick)) : null;
    }

    public static @Nullable Active of(@Nullable Entity entity, float partialTick) {
        return entity == null ? null : of(entity.getId(), partialTick);
    }

    /** For an item's {@code getArmPose}: the posed arm while {@code entity} shows a pose, else null (the item's own pose). */
    public static HumanoidModel.@Nullable ArmPose armPose(Entity entity) {
        return of(entity, 1) != null ? HOLD.getValue() : null;
    }

    /**
     * For an item's {@code applyForgeHandTransform}: while the player shows a pose, places the hand as the
     * plain hold does and moves it by the pose, and returns true; otherwise leaves it and returns false.
     */
    public static boolean handInView(PoseStack pose, LocalPlayer player, HumanoidArm arm, ItemStack held, float partialTick, float equipProcess) {
        Active a = of(player, partialTick);
        if (a == null) return false;
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        pose.translate(side * 0.56f, -0.52f + equipProcess * -0.6f, -0.72f);
        POSES.get(a.pose).hand(pose, player, arm, held, partialTick, a.weight, a.age);
        return true;
    }

    /** Third person, from the item-in-hand layer: turns the item in a posed hand. */
    public static void tiltItem(ArmedEntityRenderState state, PoseStack pose, HumanoidArm arm, ItemStack held) {
        if ((arm == HumanoidArm.RIGHT ? state.rightArmPose : state.leftArmPose) != HOLD.getValue()) return;
        if (!(state instanceof AvatarRenderState avatar)) return;
        Active a = of(avatar.id, state.partialTick);
        if (a != null) POSES.get(a.pose).item(state, pose, arm, held, a.weight, a.age);
    }

    private static void poseArm(HumanoidModel<?> model, HumanoidRenderState state, HumanoidArm arm) {
        ModelPart part = arm == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
        // Start from the plain item hold, so easing in and out does not jump.
        part.xRot = part.xRot * 0.5f - (float) (Math.PI / 10);
        part.yRot = 0;
        if (!(state instanceof AvatarRenderState avatar)) return;
        Active a = of(avatar.id, state.partialTick);
        if (a != null) POSES.get(a.pose).arm(model, state, arm, state.getUseItemStackForArm(arm), a.weight, a.age);
    }

    /** Smoothly 0 → 1 over {@code t} from 0 to 1. */
    public static float smooth(float t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            HELD.clear();
            return;
        }
        long now = ClientCharges.ticks();
        HELD.entrySet().removeIf(e -> {
            if (level.getEntity(e.getKey()) == null) return true;
            e.getValue().values().removeIf(h -> {
                if (h.duration == 0 && h.released < 0 && now - h.seen > STALE_TICKS) h.released = now;
                return h.over(now);
            });
            return e.getValue().isEmpty();
        });
    }
}
