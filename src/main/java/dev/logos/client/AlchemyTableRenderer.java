package dev.logos.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.logos.LogosMod;
import dev.logos.alchemy.Process;
import dev.logos.block.AlchemyTableBlock;
import dev.logos.block.AlchemyTableBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

/**
 * The alchemy table (assets/logos/geckolib/…/alchemy_table), drawn from its main half and centred on
 * the seam between the two halves. The hourglass shows the mana pool: sand above is mana left, sand
 * below is mana spent. The water tank under the table shows how full the fluid tank is. The
 * still's flame burns only while a card distils.
 */
public class AlchemyTableRenderer extends GeoBlockRenderer<AlchemyTableBlockEntity> {
    /**
     * The sand of each half of the hourglass, bottom tier first, with each tier's volume (from the
     * art's render manifest). Both halves hold the same total; each tier is pivoted at its own floor
     * and is filled from the bottom tier up, so the sand never pokes through the narrow waist.
     */
    static final String[] UPPER = {"mana_sand_upper_0", "mana_sand_upper_1", "mana_sand_upper_2", "mana_sand_upper_3"};
    static final String[] LOWER = {"mana_sand_lower_0", "mana_sand_lower_1", "mana_sand_lower_2", "mana_sand_lower_3"};
    private static final float[] UPPER_VOLUME = {0.2592f, 1.125f, 2.7378f, 4.8672f};
    private static final float[] LOWER_VOLUME = {4.8672f, 2.7378f, 1.125f, 0.2592f};
    private static final float SAND = 8.9892f;
    /** The water bone is modelled 6.1 units deep; the tank inside is 10 deep. */
    private static final float WATER_MODELLED = 6.1f, WATER_FULL = 10f;
    /** Modelled at twice its size. */
    private static final float SCALE = 0.5f;

    public AlchemyTableRenderer(BlockEntityRendererProvider.Context context) {
        super(new DefaultedBlockGeoModel<>(ResourceLocation.fromNamespaceAndPath(LogosMod.MODID, "alchemy_table")));
        withScale(SCALE);
        addRenderLayer(new AutoGlowingGeoLayer<>(this));
    }

    /** Centred on the seam: half a block over towards the side half, in the already halved model space. */
    @Override
    protected void rotateBlock(Direction facing, PoseStack poseStack) {
        super.rotateBlock(facing, poseStack);
        poseStack.translate(-0.5f / SCALE, 0, 0);
    }

    /**
     * GeckoLib 4 shares one baked model between every table (and the item), and its bones carry their state: each bone
     * this renderer changes is set again every time it is drawn, after the animations.
     */
    @Override
    public void renderRecursively(PoseStack poseStack, AlchemyTableBlockEntity table, GeoBone bone, RenderType renderType, MultiBufferSource buffers,
            VertexConsumer buffer, boolean isReRender, float partialTick, int light, int overlay, int colour) {
        String name = bone.getName();
        float mana = (float) (table.mana() / AlchemyTableBlockEntity.MAX_MANA);
        int upper = indexOf(UPPER, name), lower = indexOf(LOWER, name);
        if (upper >= 0) fillSand(bone, UPPER_VOLUME, upper, SAND * mana);
        else if (lower >= 0) fillSand(bone, LOWER_VOLUME, lower, SAND * (1 - mana));
        else if (name.equals("water_tank_fill")) {
            float fluid = (float) table.fluid().getAmount() / AlchemyTableBlockEntity.TANK_CAPACITY;
            bone.setHidden(fluid <= 0.001f);
            if (fluid > 0.001f) bone.setScaleY(WATER_FULL * fluid / WATER_MODELLED);
        } else if (name.equals("flame_outer")) {
            bone.setHidden(!table.isWorking(Process.DISTILL));
        }
        super.renderRecursively(poseStack, table, bone, renderType, buffers, buffer, isReRender, partialTick, light, overlay, colour);
    }

    static int indexOf(String[] names, String name) {
        for (int i = 0; i < names.length; i++) if (names[i].equals(name)) return i;
        return -1;
    }

    /** Tier {@code i} of one half when the half holds {@code volume} of sand, filled bottom-up; an empty tier is hidden. */
    private static void fillSand(GeoBone bone, float[] volumes, int i, float volume) {
        float below = 0;
        for (int k = 0; k < i; k++) below += volumes[k];
        float fill = Math.clamp((volume - below) / volumes[i], 0, 1);
        bone.setHidden(fill <= 0.001f);
        if (fill > 0.001f) bone.setScaleY(fill);
    }

    /** Glass and water are see-through. */
    @Override
    public RenderType getRenderType(AlchemyTableBlockEntity table, ResourceLocation texture, MultiBufferSource buffers, float partialTick) {
        return RenderType.entityTranslucent(texture);
    }

    /** The whole two-block table and what stands on it. */
    @Override
    public AABB getRenderBoundingBox(AlchemyTableBlockEntity table) {
        return new AABB(table.getBlockPos()).minmax(new AABB(AlchemyTableBlock.otherHalf(table.getBlockPos(), table.getBlockState()))).expandTowards(0, 1, 0);
    }
}
