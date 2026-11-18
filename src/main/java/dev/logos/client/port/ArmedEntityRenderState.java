package dev.logos.client.port;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

/** Stands in for 26.1's {@code net.minecraft.client.renderer.entity.state.ArmedEntityRenderState}; see {@link EntityRenderState}. */
public class ArmedEntityRenderState extends LivingEntityRenderState {
    public HumanoidArm mainArm = HumanoidArm.RIGHT;
    public HumanoidModel.ArmPose rightArmPose = HumanoidModel.ArmPose.EMPTY;
    public HumanoidModel.ArmPose leftArmPose = HumanoidModel.ArmPose.EMPTY;
    public ItemStack rightHandItemStack = ItemStack.EMPTY;
    public ItemStack leftHandItemStack = ItemStack.EMPTY;

    public ItemStack getMainHandItemStack() {
        return mainArm == HumanoidArm.RIGHT ? rightHandItemStack : leftHandItemStack;
    }

    public ItemStack getUseItemStackForArm(HumanoidArm arm) {
        return arm == HumanoidArm.RIGHT ? rightHandItemStack : leftHandItemStack;
    }
}
