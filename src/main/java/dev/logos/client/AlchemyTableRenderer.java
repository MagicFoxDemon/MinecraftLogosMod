package dev.logos.client;

import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.model.DefaultedBlockGeoModel;
import com.geckolib.renderer.GeoBlockRenderer;
import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer;

import dev.logos.LogosMod;
import dev.logos.alchemy.Process;
import dev.logos.block.AlchemyTableBlock;
import dev.logos.block.AlchemyTableBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;

/**
 * The alchemy table (assets/logos/geckolib/…/alchemy_table), drawn from its main half and centred on
 * the seam between the two halves. The hourglass shows the mana pool: sand above is mana left, sand
 * below is mana spent. The water tank under the table shows how full the fluid tank is. The
 * still's flame burns only while a card distils.
 */
public class AlchemyTableRenderer<R extends BlockEntityRenderState & GeoRenderState> extends GeoBlockRenderer<AlchemyTableBlockEntity, R> {
    private static final DataTicket<Float> MANA = DataTicket.create("logos_alchemy_table_mana", Float.class);
    private static final DataTicket<Float> FLUID = DataTicket.create("logos_alchemy_table_fluid", Float.class);
    private static final DataTicket<Boolean> DISTILLING = DataTicket.create("logos_alchemy_table_distilling", Boolean.class);
    /**
     * The sand of each half of the hourglass, bottom tier first, with each tier's volume (from the
     * art's render manifest). Both halves hold the same total; each tier is pivoted at its own floor
     * and is filled from the bottom tier up, so the sand never pokes through the narrow waist.
     */
    private static final String[] UPPER = {"mana_sand_upper_0", "mana_sand_upper_1", "mana_sand_upper_2", "mana_sand_upper_3"};
    private static final String[] LOWER = {"mana_sand_lower_0", "mana_sand_lower_1", "mana_sand_lower_2", "mana_sand_lower_3"};
    private static final float[] UPPER_VOLUME = {0.2592f, 1.125f, 2.7378f, 4.8672f};
    private static final float[] LOWER_VOLUME = {4.8672f, 2.7378f, 1.125f, 0.2592f};
    private static final float SAND = 8.9892f;
    /** The water bone is modelled 6.1 units deep; the tank inside is 10 deep. */
    private static final float WATER_MODELLED = 6.1f, WATER_FULL = 10f;
    /** Modelled at twice its size. */
    private static final float SCALE = 0.5f;

    public AlchemyTableRenderer(BlockEntityRendererProvider.Context context) {
        super(context, new DefaultedBlockGeoModel<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "alchemy_table")));
        withScale(SCALE);
        withRenderLayer(new AutoGlowingGeoLayer<>(this));
    }

    @Override
    public void addRenderData(AlchemyTableBlockEntity table, Void unused, R state, float partialTick) {
        super.addRenderData(table, unused, state, partialTick);
        state.addGeckolibData(MANA, (float) (table.mana() / AlchemyTableBlockEntity.MAX_MANA));
        state.addGeckolibData(FLUID, (float) table.fluid().getAmount() / AlchemyTableBlockEntity.TANK_CAPACITY);
        state.addGeckolibData(DISTILLING, table.isWorking(Process.DISTILL));
    }

    /** Centred on the seam: half a block over towards the side half, in the already halved model space. */
    @Override
    public void adjustRenderPose(RenderPassInfo<R> info) {
        super.adjustRenderPose(info);
        info.poseStack().translate(-0.5f / SCALE, 0, 0);
    }

    @Override
    public void adjustModelBonesForRender(RenderPassInfo<R> info, BoneSnapshots snapshots) {
        super.adjustModelBonesForRender(info, snapshots);
        float mana = info.getOrDefaultGeckolibData(MANA, 0f);
        fillSand(snapshots, UPPER, UPPER_VOLUME, SAND * mana);
        fillSand(snapshots, LOWER, LOWER_VOLUME, SAND * (1 - mana));
        float fluid = info.getOrDefaultGeckolibData(FLUID, 0f);
        snapshots.ifPresent("water_tank_fill", s -> {
            if (fluid <= 0.001f) s.skipRender(true);
            else s.setScaleY(WATER_FULL * fluid / WATER_MODELLED);
        });
        boolean distilling = info.getOrDefaultGeckolibData(DISTILLING, false);
        snapshots.ifPresent("flame_outer", s -> s.skipRender(!distilling));
    }

    /** Fills the tiers of one half bottom-up with {@code volume} of sand; an empty tier is hidden. */
    private static void fillSand(BoneSnapshots snapshots, String[] tiers, float[] volumes, float volume) {
        float below = 0;
        for (int i = 0; i < tiers.length; i++) {
            float fill = Math.clamp((volume - below) / volumes[i], 0, 1);
            below += volumes[i];
            snapshots.ifPresent(tiers[i], s -> {
                if (fill <= 0.001f) s.skipRender(true);
                else {
                    s.skipRender(false);
                    s.setScaleY(fill);
                }
            });
        }
    }

    /** Glass and water are see-through. */
    @Override
    public RenderType getRenderType(R state, Identifier texture) {
        return RenderTypes.entityTranslucent(texture);
    }

    /** The whole two-block table and what stands on it. */
    @Override
    public AABB getRenderBoundingBox(AlchemyTableBlockEntity table) {
        return new AABB(table.getBlockPos()).minmax(new AABB(AlchemyTableBlock.otherHalf(table.getBlockPos(), table.getBlockState()))).expandTowards(0, 1, 0);
    }
}
