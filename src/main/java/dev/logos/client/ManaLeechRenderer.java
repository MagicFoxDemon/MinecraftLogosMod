package dev.logos.client;

import dev.logos.LogosMod;
import dev.logos.runtime.ManaLeech;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

/**
 * The mana leech (assets/logos/geckolib/…/mana_leech). Its glands glow brighter with the mana it holds:
 * the plain glowmask, then feed_1 to feed_3 — the last also while it coils to leap or splits.
 */
public class ManaLeechRenderer extends GeoEntityRenderer<ManaLeech> {
    private static final ResourceLocation[] GLOWMASKS = {
            glowmask("mana_leech_glowmask"), glowmask("mana_leech_feed_1_glowmask"),
            glowmask("mana_leech_feed_2_glowmask"), glowmask("mana_leech_feed_3_glowmask")};

    public ManaLeechRenderer(EntityRendererProvider.Context context) {
        super(context, new DefaultedEntityGeoModel<>(ResourceLocation.fromNamespaceAndPath(LogosMod.MODID, "mana_leech")));
        this.shadowRadius = 0.35f;
        addRenderLayer(new AutoGlowingGeoLayer<>(this) {
            /** The glowmask itself, drawn glowing (GeckoLib 4 would look for one beside the base texture). */
            @Override
            protected RenderType getRenderType(ManaLeech leech, MultiBufferSource buffers) {
                return RenderType.eyes(GLOWMASKS[Math.clamp(leech.glowStage(), 0, GLOWMASKS.length - 1)]);
            }
        });
    }

    private static ResourceLocation glowmask(String name) {
        return ResourceLocation.fromNamespaceAndPath(LogosMod.MODID, "textures/entity/" + name + ".png");
    }
}
