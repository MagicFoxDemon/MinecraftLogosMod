package dev.logos.client;

import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.logos.LogosMod;
import dev.logos.runtime.AlchemicalColossus;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;
import software.bernie.geckolib.util.RenderUtil;

/**
 * The alchemical colossus (assets/logos/geckolib/…/alchemical_colossus). Its scorched shell — in a
 * frenzy and after one, until maintained — is the animated smouldering texture with its glowmask
 * drawn over it; the normal shell has no glow. Each frame it reports where its hands, seats and
 * muzzles are, so riders and the held sit exactly in them. While it aims its palm cannon, its right
 * upper arm is turned on top of the animation so the palm points at what it shoots (an aim offset).
 * The three reservoir tubes on its back show how full its pool is: the glowing fill sinks as its mana
 * is spent, and the spent part of each tube is drawn again in the same material, blackened.
 */
public class ColossusRenderer extends GeoEntityRenderer<AlchemicalColossus> {
    private static final ResourceLocation NORMAL = ResourceLocation.fromNamespaceAndPath(LogosMod.MODID, "textures/entity/alchemical_colossus.png");
    private static final ResourceLocation SCORCHED = ResourceLocation.fromNamespaceAndPath(LogosMod.MODID, "textures/entity/alchemical_colossus_berserk.png");
    /** The fill of its three reservoir tubes; each is pivoted at its foot, so it shrinks downward. */
    private static final String[] RESERVOIR = {"energy_fill_left", "energy_fill_center", "energy_fill_right"};
    /** The spent part of a tube: the same texture, darkened almost to black. */
    private static final int SPENT = 0xFF1E1E22;
    private static final String[] REPORTED = {"right_hand", "left_hand", "attachment_seat_left", "attachment_seat_right", "attachment_muzzle_right", "attachment_muzzle_left", "right_upper_arm", "left_foot", "right_foot"};

    public ColossusRenderer(EntityRendererProvider.Context context) {
        super(context, new DefaultedEntityGeoModel<AlchemicalColossus>(ResourceLocation.fromNamespaceAndPath(LogosMod.MODID, "alchemical_colossus")) {
            @Override
            public ResourceLocation getTextureResource(AlchemicalColossus c) {
                return c.isScorched() ? SCORCHED : NORMAL;
            }

            /** GeckoLib 4 calls this right after the animation has posed the bones. */
            @Override
            public void setCustomAnimations(AlchemicalColossus c, long instanceId, AnimationState<AlchemicalColossus> state) {
                super.setCustomAnimations(c, instanceId, state);
                adjustBones(this, c, state.getPartialTick());
            }
        });
        this.shadowRadius = 1.2f;
        // The smouldering cracks glow only on the scorched shell.
        addRenderLayer(new AutoGlowingGeoLayer<>(this) {
            @Override
            public void render(PoseStack poseStack, AlchemicalColossus c, BakedGeoModel model, RenderType renderType, MultiBufferSource buffers,
                    VertexConsumer buffer, float partialTick, int light, int overlay) {
                if (c.isScorched()) super.render(poseStack, c, model, renderType, buffers, buffer, partialTick, light, overlay);
            }
        });
    }

    /**
     * The tubes' fill, the reach and the aim offset: the raised arm turned toward its aim. In this model a negative turn
     * about the bone's Y swings the raised arm toward the colossus's right, and a positive turn about X raises it (both
     * checked against the drawn bones in the logos_colossus scenario).
     */
    private static void adjustBones(GeoModel<AlchemicalColossus> model, AlchemicalColossus c, float partialTick) {
        float fill = c.fill();
        for (String tube : RESERVOIR) model.getBone(tube).ifPresent(b -> {
            b.setHidden(fill <= 0.001f);
            if (fill > 0.001f && fill < 1) b.setScaleY(b.getScaleY() * fill);
        });
        Vec3 goal = c.reachGoal();
        float weight = goal == null ? 0 : c.reachWeight(partialTick);
        if (goal != null && weight > 0) {
            // The reach: its arm (and if need be its torso) posed onto the point, in the model's own space.
            Vec3 at = c.getPosition(partialTick);
            float yaw = Mth.rotLerp(partialTick, c.yBodyRotO, c.yBodyRot);
            Vector3f local = new Vector3f((float) (goal.x - at.x), (float) (goal.y - at.y - 0.01), (float) (goal.z - at.z))
                    .rotateY(-(180 - yaw) * Mth.DEG_TO_RAD);
            ColossusArmIk.solve(model.getBakedModel(model.getModelResource(c)), local, weight);
        }
        float[] turn = c.drawnArmTurn(partialTick);
        if (turn[0] == 0 && turn[1] == 0) return;
        model.getBone("right_upper_arm").ifPresent(arm -> {
            arm.setRotY(arm.getRotY() - turn[0]);
            arm.setRotX(arm.getRotX() + turn[1]);
        });
    }

    /** GeckoLib 4 works out where a bone is drawn only for bones asked to. */
    @Override
    public void preRender(PoseStack poseStack, AlchemicalColossus c, BakedGeoModel model, MultiBufferSource buffers, VertexConsumer buffer,
            boolean isReRender, float partialTick, int light, int overlay, int colour) {
        for (String name : REPORTED) model.getBone(name).ifPresent(b -> b.setTrackingMatrices(true));
        super.preRender(poseStack, c, model, buffers, buffer, isReRender, partialTick, light, overlay, colour);
    }

    /** Where each reported bone was drawn, from where the colossus is drawn this frame. */
    @Override
    public void renderFinal(PoseStack poseStack, AlchemicalColossus c, BakedGeoModel model, MultiBufferSource buffers, VertexConsumer buffer,
            float partialTick, int light, int overlay, int colour) {
        super.renderFinal(poseStack, c, model, buffers, buffer, partialTick, light, overlay, colour);
        Vec3 at = c.getPosition(partialTick);
        for (String name : REPORTED) model.getBone(name).ifPresent(b -> {
            var local = b.getLocalPosition();
            c.reportBone(name, at.add(local.x, local.y, local.z));
        });
    }

    /** After the backpack, the spent part of its tubes. */
    @Override
    public void renderRecursively(PoseStack poseStack, AlchemicalColossus c, GeoBone bone, RenderType renderType, MultiBufferSource buffers,
            VertexConsumer buffer, boolean isReRender, float partialTick, int light, int overlay, int colour) {
        super.renderRecursively(poseStack, c, bone, renderType, buffers, buffer, isReRender, partialTick, light, overlay, colour);
        float fill = c.fill();
        if (!isReRender && fill < 0.999f && bone.getName().equals("backpack")) renderSpent(poseStack, c, bone, buffer, light, overlay, fill);
    }

    /** Above each tube's glowing fill, the spent part: the same tube again, blackened, from the fill's top to the tube's. */
    private void renderSpent(PoseStack poseStack, AlchemicalColossus c, GeoBone pack, VertexConsumer vertices, int light, int overlay, float fill) {
        poseStack.pushPose();
        RenderUtil.prepMatrixForBone(poseStack, pack);
        for (String name : RESERVOIR) {
            GeoBone tube = getGeoModel().getBone(name).orElse(null);
            if (tube == null) continue;
            for (GeoCube cube : tube.getCubes()) {
                poseStack.pushPose();
                RenderUtil.translateToPivotPoint(poseStack, tube);
                poseStack.translate(0, fill * cube.size().y / 16, 0);
                poseStack.scale(1, 1 - fill, 1);
                RenderUtil.translateAwayFromPivotPoint(poseStack, tube);
                renderCube(poseStack, cube, vertices, light, overlay, SPENT);
                poseStack.popPose();
            }
        }
        poseStack.popPose();
    }

    @Override
    public RenderType getRenderType(AlchemicalColossus c, ResourceLocation texture, MultiBufferSource buffers, float partialTick) {
        return RenderType.entityCutoutNoCull(texture); // 26.1's entityCutout draws both sides
    }
}
