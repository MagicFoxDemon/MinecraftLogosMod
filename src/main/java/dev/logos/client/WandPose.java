package dev.logos.client;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.logos.LogosMod;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;
import net.neoforged.neoforge.client.IArmPoseTransformer;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * How a player holds a charging wand. Third person: the wand arm comes up to point where they look,
 * trembling more as the charge fills and steadying once full. First person: the wand lifts in toward
 * the middle of the view and trembles the same way. Nothing else changes: no slowdown, no bow draw.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class WandPose {
    /** Ticks the arm takes to come up. */
    private static final float RAISE_TICKS = 5;

    /** The charging arm pose, added to {@link HumanoidModel.ArmPose} through META-INF/enumextensions.json. */
    public static final EnumProxy<HumanoidModel.ArmPose> CHARGE = new EnumProxy<>(HumanoidModel.ArmPose.class,
            false, false, (IArmPoseTransformer) WandPose::poseArm);

    private WandPose() {}

    @SubscribeEvent
    static void onRegisterExtensions(RegisterClientExtensionsEvent event) {
        var wands = new java.util.ArrayList<net.minecraft.world.item.Item>();
        wands.add(LogosMod.WAND.get());
        wands.add(LogosMod.ADEPT_WAND.get());
        wands.add(LogosMod.MASTER_WAND.get());
        LogosMod.WOOD_WANDS.forEach(w -> wands.add(w.get()));
        event.registerItem(new IClientItemExtensions() {
            @Override
            public HumanoidModel.@Nullable ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
                return charging(entity, hand) ? CHARGE.getValue() : null;
            }

            @Override
            public boolean applyForgeHandTransform(PoseStack pose, LocalPlayer player, HumanoidArm arm, ItemStack stack, float partialTick,
                    float equipProcess, float swingProcess) {
                InteractionHand hand = arm == player.getMainArm() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
                if (!charging(player, hand)) return false;
                handInView(pose, player, arm, partialTick, equipProcess);
                return true;
            }
        }, wands.toArray(net.minecraft.world.item.Item[]::new));
    }

    /** Whether {@code entity} is charging the wand in {@code hand}. */
    static boolean charging(LivingEntity entity, InteractionHand hand) {
        return entity.isUsingItem() && entity.getUsedItemHand() == hand && ClientCharges.of(entity) != null;
    }

    /** Smoothly 0 → 1 as the arm comes up. */
    private static float raise(float ticksUsing) {
        float t = Mth.clamp(ticksUsing / RAISE_TICKS, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** Tremble size: a little at first, growing with the charge, nearly still once full. */
    private static float tremble(ClientCharges.@Nullable State c) {
        if (c == null) return 0;
        return c.full ? 0.15f : 0.3f + c.progress(0);
    }

    private static void poseArm(HumanoidModel<?> model, HumanoidRenderState state, HumanoidArm arm) {
        ModelPart part = arm == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
        float side = arm == HumanoidArm.RIGHT ? 1 : -1;
        float k = raise(state.ticksUsingItem);
        ClientCharges.State c = state instanceof AvatarRenderState avatar ? ClientCharges.all().get(avatar.id) : null;
        float shake = tremble(c) * 0.04f;
        float t = state.ageInTicks;
        // Pointing where they look, the wand a little below the eye line.
        float x = model.head.xRot - 1.35f + Mth.sin(t * 2.1f) * shake;
        float y = model.head.yRot - side * 0.12f + Mth.cos(t * 2.7f) * shake;
        part.xRot = Mth.lerp(k, part.xRot, x);
        part.yRot = Mth.lerp(k, part.yRot, y);
        part.zRot = Mth.lerp(k, part.zRot, side * 0.04f);
    }

    /** First person: the vanilla hand position, lifted toward the middle of the view and tipped forward. */
    private static void handInView(PoseStack pose, LocalPlayer player, HumanoidArm arm, float partialTick, float equipProcess) {
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        float k = raise(player.getTicksUsingItem() + partialTick);
        float shake = tremble(ClientCharges.of(player)) * 0.008f;
        float t = ClientCharges.ticks() + partialTick;
        pose.translate(side * 0.56f, -0.52f + equipProcess * -0.6f, -0.72f);
        pose.translate(-side * 0.14f * k + Mth.sin(t * 2.1f) * shake, 0.12f * k + Mth.cos(t * 2.7f) * shake, -0.08f * k);
        pose.mulPose(Axis.XP.rotationDegrees(-14 * k));
        pose.mulPose(Axis.ZP.rotationDegrees(side * 6 * k));
    }
}
