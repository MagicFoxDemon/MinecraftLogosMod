package dev.logos.client;

import com.geckolib.model.DefaultedBlockGeoModel;
import com.geckolib.renderer.GeoItemRenderer;
import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer;

import dev.logos.LogosMod;
import dev.logos.item.AlchemyTableItem;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * The alchemy table item, drawn from the block's own model as a freshly placed table looks: no
 * mana left in the hourglass, an empty tank and no flame. Centred on the seam between the halves.
 */
public class AlchemyTableItemRenderer extends GeoItemRenderer<AlchemyTableItem> {
    private static final String[] HIDDEN = {"mana_sand_upper_0", "mana_sand_upper_1", "mana_sand_upper_2", "mana_sand_upper_3",
            "water_tank_fill", "flame_outer"};
    /** Modelled at twice its size, as for the block. */
    private static final float SCALE = 0.5f;
    /** Half the table's height in blocks, so the item turns about its middle. */
    private static final float HALF_HEIGHT = 23.5f / 2 / 16;

    public AlchemyTableItemRenderer() {
        super(new DefaultedBlockGeoModel<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "alchemy_table")));
        withScale(SCALE);
        withRenderLayer(new AutoGlowingGeoLayer<>(this));
    }

    @Override
    public void adjustRenderPose(RenderPassInfo<GeoRenderState> info) {
        super.adjustRenderPose(info);
        info.poseStack().translate(0, -HALF_HEIGHT / SCALE, 0);
    }

    @Override
    public void adjustModelBonesForRender(RenderPassInfo<GeoRenderState> info, BoneSnapshots snapshots) {
        super.adjustModelBonesForRender(info, snapshots);
        for (String bone : HIDDEN) snapshots.ifPresent(bone, s -> s.skipRender(true));
    }

    /** Glass is see-through. */
    @Override
    public RenderType getRenderType(GeoRenderState state, Identifier texture) {
        return RenderTypes.entityTranslucent(texture);
    }
}
