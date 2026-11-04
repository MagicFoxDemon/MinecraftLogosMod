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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Holding a beam (光柱) until it ends: a wand is pointed straight down the gaze, the arm out as it is
 * while charging; a staff, too tall to point that way, is thrust out level in front, the arm lower and
 * the staff turned forward in the hand so its head leads. Steady, without the charge's tremble.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
final class BeamPose implements CastPoses.Pose {
    /** How far the arm comes up from hanging, below the gaze: a wand nearly level with it, a staff lower. */
    private static final float WAND_RAISE = 1.4f, STAFF_RAISE = 1.2f;
    /** Third person: how much further a wand turns forward in the hand, and how far a staff slides forward along itself. */
    private static final float WAND_LEAN = 0.5f, STAFF_REACH = 0.6f;

    private BeamPose() {}

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        CastPoses.register(CastPose.BEAM, new BeamPose());
    }

    private static float raise(ItemStack held) {
        return held.getItem() instanceof StaffItem ? STAFF_RAISE : WAND_RAISE;
    }

    @Override
    public void arm(HumanoidModel<?> model, HumanoidRenderState state, HumanoidArm arm, ItemStack held, float weight) {
        ModelPart part = arm == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
        float side = arm == HumanoidArm.RIGHT ? 1 : -1;
        part.xRot = Mth.lerp(weight, part.xRot, model.head.xRot - raise(held));
        part.yRot = Mth.lerp(weight, part.yRot, model.head.yRot - side * 0.1f);
        part.zRot = Mth.lerp(weight, part.zRot, side * 0.04f);
    }

    /**
     * The item stands across the arm; turning it forward by about as much as the arm came up lays it along
     * the gaze (a wand's model already leans back in the hand, so it turns a little further). A staff,
     * gripped at its middle, slides forward along itself so that its head leads and little trails behind.
     */
    @Override
    public void item(ArmedEntityRenderState state, PoseStack pose, HumanoidArm arm, ItemStack held, float weight) {
        boolean staff = held.getItem() instanceof StaffItem;
        pose.mulPose(Axis.XP.rotation(-(raise(held) + (staff ? 0 : WAND_LEAN)) * weight));
        if (staff) pose.translate(0, STAFF_REACH * weight, 0);
    }

    @Override
    public void hand(PoseStack pose, LocalPlayer player, HumanoidArm arm, ItemStack held, float partialTick, float weight) {
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        if (held.getItem() instanceof StaffItem) {
            pose.translate(-side * 0.1f * weight, -0.06f * weight, -0.2f * weight);
            pose.mulPose(Axis.XP.rotationDegrees(-60 * weight));
        } else {
            pose.translate(-side * 0.14f * weight, 0.12f * weight, -0.1f * weight);
            pose.mulPose(Axis.XP.rotationDegrees(-35 * weight));
        }
        pose.mulPose(Axis.ZP.rotationDegrees(side * 6 * weight));
    }
}
