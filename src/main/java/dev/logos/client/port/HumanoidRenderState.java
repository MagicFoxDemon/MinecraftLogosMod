package dev.logos.client.port;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Stands in for 26.1's {@code net.minecraft.client.renderer.entity.state.HumanoidRenderState}; see {@link EntityRenderState}. */
public class HumanoidRenderState extends ArmedEntityRenderState {
    /** Ticks the item in use has been used, partial tick included; 0 when none is. */
    public float ticksUsingItem;
    public boolean isUsingItem;

    /**
     * What 26.1 would have extracted from {@code entity} for this frame, with the arm poses {@code model} is being set
     * up with: an {@link AvatarRenderState} for a player (or a possession body), as 26.1 has.
     */
    public static HumanoidRenderState of(LivingEntity entity, HumanoidModel<?> model) {
        float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(!entity.level().tickRateManager().isEntityFrozen(entity));
        HumanoidRenderState state;
        // 26.1's avatars: players and mannequins, whose stand-in here is the possession body.
        if (entity instanceof Player || entity instanceof dev.logos.runtime.PossessionBody) {
            AvatarRenderState avatar = new AvatarRenderState();
            avatar.id = entity.getId();
            state = avatar;
        } else {
            state = new HumanoidRenderState();
        }
        state.partialTick = partialTick;
        state.ageInTicks = entity.tickCount + partialTick;
        state.isInvisible = entity.isInvisible();
        state.mainArm = entity.getMainArm();
        state.rightArmPose = model.rightArmPose;
        state.leftArmPose = model.leftArmPose;
        state.rightHandItemStack = heldBy(entity, HumanoidArm.RIGHT);
        state.leftHandItemStack = heldBy(entity, HumanoidArm.LEFT);
        state.isUsingItem = entity.isUsingItem();
        state.ticksUsingItem = entity.isUsingItem() ? entity.getTicksUsingItem() + partialTick : 0;
        return state;
    }

    private static ItemStack heldBy(LivingEntity entity, HumanoidArm arm) {
        return entity.getMainArm() == arm ? entity.getMainHandItem() : entity.getOffhandItem();
    }
}
