package dev.logos.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.logos.LogosMod;
import dev.logos.runtime.ManaLifeform;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.util.Color;

/**
 * The mana lifeform's GeckoLib model (assets/logos/geckolib/…/mana_lifeform). Awake it is mana and
 * lights itself; dormant it lies dim and grey. Its size follows its level. Culled faces, because the
 * model's outline is an inverted shell that shows only from behind. Where its {@code cast_point} bone
 * is drawn, its spells visibly leave from ({@link CastPoints}).
 */
public class ManaLifeformRenderer extends GeoEntityRenderer<ManaLifeform> {
    public ManaLifeformRenderer(EntityRendererProvider.Context context) {
        super(context, new DefaultedEntityGeoModel<>(ResourceLocation.fromNamespaceAndPath(LogosMod.MODID, "mana_lifeform")));
        this.shadowRadius = 0.2f;
    }

    @Override
    public void preRender(PoseStack poseStack, ManaLifeform lf, BakedGeoModel model, MultiBufferSource buffers, VertexConsumer buffer,
            boolean isReRender, float partialTick, int light, int overlay, int colour) {
        // GeckoLib 4 works out where a bone is drawn only for bones asked to.
        model.getBone(CastPoints.BONE).ifPresent(b -> b.setTrackingMatrices(true));
        super.preRender(poseStack, lf, model, buffers, buffer, isReRender, partialTick, light, overlay, colour);
    }

    @Override
    public void renderFinal(PoseStack poseStack, ManaLifeform lf, BakedGeoModel model, MultiBufferSource buffers, VertexConsumer buffer,
            float partialTick, int light, int overlay, int colour) {
        super.renderFinal(poseStack, lf, model, buffers, buffer, partialTick, light, overlay, colour);
        // From where it is drawn this frame (GeckoLib 4's own world position uses the tick's, not the frame's).
        Vec3 drawn = lf.getPosition(partialTick);
        model.getBone(CastPoints.BONE).ifPresent(b -> {
            var at = b.getLocalPosition();
            CastPoints.report(lf.getId(), drawn.add(at.x, at.y, at.z));
        });
    }

    @Override
    public void scaleModelForRender(float widthScale, float heightScale, PoseStack poseStack, ManaLifeform lf, BakedGeoModel model,
            boolean isReRender, float partialTick, int light, int overlay) {
        float s = ManaLifeform.renderScale(lf.lifeLevel());
        super.scaleModelForRender(widthScale * s, heightScale * s, poseStack, lf, model, isReRender, partialTick, light, overlay);
    }

    @Override
    public RenderType getRenderType(ManaLifeform lf, ResourceLocation texture, MultiBufferSource buffers, float partialTick) {
        return RenderType.entityCutout(texture); // culled: 26.1's entityCutoutCull
    }

    @Override
    public Color getRenderColor(ManaLifeform lf, float partialTick, int light) {
        return lf.isDormant() ? Color.ofOpaque(0x7F8A99) : Color.WHITE;
    }

    @Override
    protected int getBlockLightLevel(ManaLifeform lf, BlockPos pos) {
        return lf.isDormant() ? super.getBlockLightLevel(lf, pos) : 15;
    }
}
