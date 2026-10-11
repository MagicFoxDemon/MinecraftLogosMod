package dev.logos.client;

import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.model.DefaultedEntityGeoModel;
import com.geckolib.renderer.GeoEntityRenderer;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer;

import dev.logos.LogosMod;
import dev.logos.runtime.AlchemicalColossus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * The alchemical colossus (assets/logos/geckolib/…/alchemical_colossus). Its scorched shell — in a
 * frenzy and after one, until maintained — is the animated smouldering texture with its glowmask
 * drawn over it; the normal shell has no glow. Each frame it reports where its hands, seats and
 * muzzles are, so riders and the held sit exactly in them.
 */
public class ColossusRenderer<R extends LivingEntityRenderState & GeoRenderState> extends GeoEntityRenderer<AlchemicalColossus, R> {
    private static final Identifier NORMAL = Identifier.fromNamespaceAndPath(LogosMod.MODID, "textures/entity/alchemical_colossus.png");
    private static final Identifier SCORCHED = Identifier.fromNamespaceAndPath(LogosMod.MODID, "textures/entity/alchemical_colossus_berserk.png");
    private static final DataTicket<Boolean> SCORCHED_SHELL = DataTicket.create("logos_colossus_scorched", Boolean.class);
    private static final DataTicket<Integer> ENTITY = DataTicket.create("logos_colossus_id", Integer.class);
    private static final String[] REPORTED = {"right_hand", "left_hand", "attachment_seat_left", "attachment_seat_right", "attachment_muzzle_right", "attachment_muzzle_left"};

    public ColossusRenderer(EntityRendererProvider.Context context) {
        super(context, new DefaultedEntityGeoModel<AlchemicalColossus>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "alchemical_colossus")) {
            @Override
            public Identifier getTextureResource(GeoRenderState state) {
                return Boolean.TRUE.equals(state.getOrDefaultGeckolibData(SCORCHED_SHELL, false)) ? SCORCHED : NORMAL;
            }
        });
        this.shadowRadius = 1.2f;
        // The smouldering cracks glow only on the scorched shell.
        withRenderLayer(new AutoGlowingGeoLayer<>(this) {
            @Override
            public void submitRenderTask(RenderPassInfo<R> info, SubmitNodeCollector collector) {
                if (Boolean.TRUE.equals(info.getOrDefaultGeckolibData(SCORCHED_SHELL, false))) super.submitRenderTask(info, collector);
            }
        });
    }

    @Override
    public void addRenderData(AlchemicalColossus c, Void unused, R state, float partialTick) {
        super.addRenderData(c, unused, state, partialTick);
        state.addGeckolibData(SCORCHED_SHELL, c.isScorched());
        state.addGeckolibData(ENTITY, c.getId());
    }

    @Override
    public void adjustRenderPose(RenderPassInfo<R> info) {
        super.adjustRenderPose(info);
        Integer id = info.getOrDefaultGeckolibData(ENTITY, -1);
        var level = Minecraft.getInstance().level;
        if (level == null || id < 0 || !(level.getEntity(id) instanceof AlchemicalColossus c)) return;
        for (String bone : REPORTED) {
            // GeckoLib hands over (world position, model-space position, position relative to the entity).
            info.addBonePositionListener(bone, (world, model, relative) -> {
                if (world != null) c.reportBone(bone, world);
            });
        }
    }

    @Override
    public RenderType getRenderType(R state, Identifier texture) {
        return RenderTypes.entityCutout(texture);
    }
}
