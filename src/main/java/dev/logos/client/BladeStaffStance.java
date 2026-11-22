package dev.logos.client;

import java.util.HashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.LogosMod;
import dev.logos.item.BladeStaffItem;
import dev.logos.mixin.ItemStackRenderStateAccessor;
import dev.logos.mixin.LayerRenderStateAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * How a player holds a blade staff ({@link BladeStaffItem}): crown up, like a staff. The first swing of the main hand
 * turns it about its grip so the blade leads, and it stays so while the blows keep coming; {@link #HOLD_TICKS} after
 * the last one, or as soon as it charges a spell, it turns back crown up. The model's own display transforms are the
 * crown-up hold, straight like a staff's (they are what mobs and other renderers show); this class adds the half turn
 * for the blade-forward one.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class BladeStaffStance {
    /** How long the blade stays forward after the last swing: 1.5 s. */
    static final int HOLD_TICKS = 30;
    /** Ticks the turn takes: quick to the blade, as the swing starts; a little slower back to the crown. */
    private static final float TO_BLADE_TICKS = 3, TO_CROWN_TICKS = 5;
    /**
     * Where the fist holds it in third person, in the model's own units (16 to a block): the half of the grip by the
     * blade (the grip runs from the guard at 15.4 to 27.4). The staff turns about it, so the fist keeps its place.
     */
    static final Vector3fc GRIP = new Vector3f(8, 18.4f, 8);
    /**
     * In first person it turns about a point by the guard instead: the crown-up hold stands high, where a staff's crown
     * does, and the half turn brings the grip down to the bottom of the view, part of the handle out of it.
     */
    static final Vector3fc VIEW_PIVOT = new Vector3f(8, 16.9f, 8);

    private static final class State {
        float blade, bladeO;
        long lastSwing = Long.MIN_VALUE / 2;
    }

    /** Per player id: how far the blade is turned forward, 0 (crown up) to 1. */
    private static final Map<Integer, State> STATES = new HashMap<>();

    private BladeStaffStance() {}

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            STATES.clear();
            return;
        }
        // Held still with the charges' clock, so a scenario can catch the turn halfway.
        if (ClientCharges.frozen()) return;
        long now = ClientCharges.ticks();
        for (var e : level.entitiesForRendering()) {
            // Players and mannequins: the bodies drawn as an AvatarRenderState, which is where the turn is applied.
            if (!(e instanceof Avatar p)) continue;
            if (!(p.getMainHandItem().getItem() instanceof BladeStaffItem)) {
                STATES.remove(p.getId());
                continue;
            }
            State s = STATES.computeIfAbsent(p.getId(), id -> new State());
            s.bladeO = s.blade;
            if (p.swinging && p.swingingArm == InteractionHand.MAIN_HAND) s.lastSwing = now;
            boolean blade = !p.isUsingItem() && now - s.lastSwing < HOLD_TICKS;
            s.blade = blade ? Math.min(1, s.blade + 1 / TO_BLADE_TICKS) : Math.max(0, s.blade - 1 / TO_CROWN_TICKS);
        }
        STATES.keySet().removeIf(id -> level.getEntity(id) == null);
    }

    /** 0 with the crown up, 1 with the blade forward, eased; the off hand always holds it crown up. */
    public static float blade(int entityId, boolean mainHand, float partialTick) {
        State s = mainHand ? STATES.get(entityId) : null;
        return s == null ? 0 : CastPoses.smooth(Mth.lerp(partialTick, s.bladeO, s.blade));
    }

    /**
     * Third person: turns a blade staff about its grip as far toward the blade as it has come, before {@code item}'s display
     * transform is applied to {@code pose}.
     */
    public static void turn(int entityId, ItemStack stack, ItemStackRenderState item, boolean leftHand, boolean mainHand, PoseStack pose,
            float partialTick) {
        if (stack.getItem() instanceof BladeStaffItem) turn(item, leftHand, pose, blade(entityId, mainHand, partialTick), GRIP);
    }

    /** First person: as {@link #turn(int, ItemStack, ItemStackRenderState, boolean, boolean, PoseStack, float)}, for the viewer's own hand. */
    public static void turnInView(LivingEntity holder, ItemStack stack, ItemDisplayContext context, PoseStack pose) {
        if (!(stack.getItem() instanceof BladeStaffItem)) return;
        Minecraft mc = Minecraft.getInstance();
        boolean mainHand = context.leftHand() == (holder.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT);
        ItemStackRenderState state = new ItemStackRenderState();
        mc.getItemModelResolver().updateForTopItem(state, stack, context, holder.level(), holder, 0);
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        turn(state, context.leftHand(), pose, blade(holder.getId(), mainHand, partialTick), VIEW_PIVOT);
    }

    /**
     * Turns the item by {@code half} of a half turn about the model's X axis through {@code pivot}, worked out in the
     * model's own space and carried into the hand's: display · turn · display⁻¹.
     */
    private static void turn(ItemStackRenderState item, boolean leftHand, PoseStack pose, float half, Vector3fc pivot) {
        if (half <= 0) return;
        var access = (ItemStackRenderStateAccessor) item;
        if (access.logos$activeLayerCount() <= 0) return;
        var layer = (LayerRenderStateAccessor) access.logos$layers()[0];
        PoseStack.Pose display = new PoseStack.Pose();
        layer.logos$itemTransform().apply(leftHand, display);
        display.mulPose(layer.logos$localTransform());
        Matrix4f toModel = new Matrix4f(display.pose());
        Vector3f grip = new Vector3f(pivot).div(16);
        Matrix4f turn = new Matrix4f(toModel).translate(grip).rotateX(Mth.PI * half).translate(-grip.x, -grip.y, -grip.z)
                .mul(toModel.invert(new Matrix4f()));
        pose.mulPose(turn);
    }
}
