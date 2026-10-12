package dev.logos.client;

import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.model.DefaultedEntityGeoModel;
import com.geckolib.renderer.GeoEntityRenderer;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer;

import dev.logos.LogosMod;
import dev.logos.runtime.ManaLeech;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;

/**
 * The mana leech (assets/logos/geckolib/…/mana_leech). Its glands glow brighter with the mana it holds:
 * the plain glowmask, then feed_1 to feed_3 — the last also while it coils to leap or splits.
 */
public class ManaLeechRenderer<R extends LivingEntityRenderState & GeoRenderState> extends GeoEntityRenderer<ManaLeech, R> {
    private static final DataTicket<Integer> GLOW = DataTicket.create("logos_leech_glow", Integer.class);
    private static final Identifier[] GLOWMASKS = {
            glowmask("mana_leech_glowmask"), glowmask("mana_leech_feed_1_glowmask"),
            glowmask("mana_leech_feed_2_glowmask"), glowmask("mana_leech_feed_3_glowmask")};

    public ManaLeechRenderer(EntityRendererProvider.Context context) {
        super(context, new DefaultedEntityGeoModel<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "mana_leech")));
        this.shadowRadius = 0.35f;
        withRenderLayer(new AutoGlowingGeoLayer<>(this) {
            @Override
            protected Identifier getTextureResource(R state) {
                return GLOWMASKS[state.getOrDefaultGeckolibData(GLOW, 0)];
            }
        });
    }

    private static Identifier glowmask(String name) {
        return Identifier.fromNamespaceAndPath(LogosMod.MODID, "textures/entity/" + name + ".png");
    }

    @Override
    public void addRenderData(ManaLeech leech, Void unused, R state, float partialTick) {
        super.addRenderData(leech, unused, state, partialTick);
        state.addGeckolibData(GLOW, leech.glowStage());
    }
}
