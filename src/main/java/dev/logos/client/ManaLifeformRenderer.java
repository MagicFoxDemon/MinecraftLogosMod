package dev.logos.client;

import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.model.DefaultedEntityGeoModel;
import com.geckolib.renderer.GeoEntityRenderer;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;

import dev.logos.LogosMod;
import dev.logos.runtime.ManaLifeform;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

/**
 * The mana lifeform's GeckoLib model (assets/logos/geckolib/…/mana_lifeform). Awake it is mana and
 * lights itself; dormant it lies dim and grey. Its size follows its level. Culled faces, because the
 * model's outline is an inverted shell that shows only from behind.
 */
public class ManaLifeformRenderer<R extends LivingEntityRenderState & GeoRenderState> extends GeoEntityRenderer<ManaLifeform, R> {
    private static final DataTicket<Float> SCALE = DataTicket.create("logos_lifeform_scale", Float.class);

    public ManaLifeformRenderer(EntityRendererProvider.Context context) {
        super(context, new DefaultedEntityGeoModel<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "mana_lifeform")));
        this.shadowRadius = 0.4f;
    }

    @Override
    public void addRenderData(ManaLifeform lf, Void unused, R state, float partialTick) {
        super.addRenderData(lf, unused, state, partialTick);
        state.addGeckolibData(SCALE, ManaLifeform.renderScale(lf.lifeLevel()));
    }

    @Override
    public void scaleModelForRender(RenderPassInfo<R> info, float widthScale, float heightScale) {
        float s = info.getOrDefaultGeckolibData(SCALE, ManaLifeform.renderScale(1));
        super.scaleModelForRender(info, widthScale * s, heightScale * s);
    }

    @Override
    public RenderType getRenderType(R state, Identifier texture) {
        return RenderTypes.entityCutoutCull(texture);
    }

    @Override
    public int getRenderColor(ManaLifeform lf, Void unused, float partialTick) {
        return lf.isDormant() ? 0xFF7F8A99 : 0xFFFFFFFF;
    }

    @Override
    protected int getBlockLightLevel(ManaLifeform lf, BlockPos pos) {
        return lf.isDormant() ? super.getBlockLightLevel(lf, pos) : 15;
    }
}
