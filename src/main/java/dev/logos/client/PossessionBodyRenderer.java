package dev.logos.client;

import java.util.UUID;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.runtime.PossessionBody;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

/**
 * A possessing player's body (附身), drawn as them: their skin, and the slim arms if theirs are slim. What it holds is
 * drawn in its hands with the arm poses the items ask for, as a player's (so a cast pose shows on it, as on 26.1's
 * mannequin).
 */
public final class PossessionBodyRenderer extends LivingEntityRenderer<PossessionBody, PlayerModel<PossessionBody>> {
    private final PlayerModel<PossessionBody> wide, slim;

    public PossessionBodyRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
        this.wide = getModel();
        this.slim = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
    }

    private static PlayerSkin skin(PossessionBody body) {
        UUID owner = body.owner();
        if (owner == null) return DefaultPlayerSkin.get(net.minecraft.Util.NIL_UUID);
        var connection = Minecraft.getInstance().getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(owner);
        return info != null ? info.getSkin() : DefaultPlayerSkin.get(owner);
    }

    @Override
    public void render(PossessionBody body, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        model = skin(body).model() == PlayerSkin.Model.SLIM ? slim : wide;
        HumanoidModel.ArmPose main = armPose(body, InteractionHand.MAIN_HAND), off = armPose(body, InteractionHand.OFF_HAND);
        boolean right = body.getMainArm() == HumanoidArm.RIGHT;
        model.rightArmPose = right ? main : off;
        model.leftArmPose = right ? off : main;
        super.render(body, yaw, partialTick, pose, buffers, light);
    }

    /** As PlayerRenderer asks: the item's own pose, else the plain hold. */
    private static HumanoidModel.ArmPose armPose(PossessionBody body, InteractionHand hand) {
        ItemStack stack = body.getItemInHand(hand);
        if (stack.isEmpty()) return HumanoidModel.ArmPose.EMPTY;
        HumanoidModel.ArmPose pose = IClientItemExtensions.of(stack).getArmPose(body, hand, stack);
        return pose != null ? pose : HumanoidModel.ArmPose.ITEM;
    }

    @Override
    public ResourceLocation getTextureLocation(PossessionBody body) {
        return skin(body).texture();
    }

    @Override
    protected void scale(PossessionBody body, PoseStack pose, float partialTick) {
        pose.scale(0.9375f, 0.9375f, 0.9375f);
    }
}
