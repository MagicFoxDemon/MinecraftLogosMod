package dev.logos.client;

import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.model.DefaultedEntityGeoModel;
import com.geckolib.renderer.GeoEntityRenderer;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer;
import com.mojang.math.Axis;

import dev.logos.LogosMod;
import dev.logos.runtime.ManaLeech;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/** Translucent wet skin, fullbright mana veins, and the prey's head pitch while attached. */
public class ManaLeechRenderer<R extends LivingEntityRenderState & GeoRenderState> extends GeoEntityRenderer<ManaLeech, R> {
    private static final DataTicket<Float> GLOW = DataTicket.create("logos_mana_leech_glow", Float.class);
    private static final DataTicket<Float> FACE_PITCH = DataTicket.create("logos_mana_leech_face_pitch", Float.class);
    private static final DataTicket<Boolean> FACE_PINNED = DataTicket.create("logos_mana_leech_face_pinned", Boolean.class);
    private static final Identifier NORMAL = texture("mana_leech_glowmask.png");
    private static final Identifier WEAK = texture("mana_leech_feed_1_glowmask.png");
    private static final Identifier MEDIUM = texture("mana_leech_feed_2_glowmask.png");
    private static final Identifier STRONG = texture("mana_leech_feed_3_glowmask.png");

    public ManaLeechRenderer(EntityRendererProvider.Context context) {
        super(context, new DefaultedEntityGeoModel<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "mana_leech")));
        this.shadowRadius = 0.25f;
        withRenderLayer(new AutoGlowingGeoLayer<>(this) {
            @Override
            protected Identifier getTextureResource(R state) {
                float glow = state.getOrDefaultGeckolibData(GLOW, 0f);
                if (glow <= 0) return NORMAL;
                if (glow < 1f / 3f) return WEAK;
                if (glow < 2f / 3f) return MEDIUM;
                return STRONG;
            }
        });
    }

    @Override
    public void addRenderData(ManaLeech leech, Void unused, R state, float partialTick) {
        super.addRenderData(leech, unused, state, partialTick);
        float glow = switch (leech.state()) {
            case CHARGE -> leech.chargeProgress();
            case SPLIT -> 1f;
            default -> leech.feedProgress();
        };
        state.addGeckolibData(GLOW, Math.clamp(glow, 0, 1));
        state.addGeckolibData(FACE_PITCH, leech.state() == ManaLeech.State.ATTACHED || leech.isDeadOrDying() ? leech.getXRot() : 0f);
        state.addGeckolibData(FACE_PINNED, leech.state() == ManaLeech.State.ATTACHED);
    }

    @Override
    public void adjustRenderPose(RenderPassInfo<R> info) {
        super.adjustRenderPose(info);
        // Gecko's normal 0.01-block foot lift would displace the precisely pinned mouth.
        if (info.getOrDefaultGeckolibData(FACE_PINNED, false)) info.poseStack().translate(0, -.01, 0);
        // Default living rendering supplies yaw only. The actor stores the opposite of its prey's pitch.
        info.poseStack().mulPose(Axis.XP.rotationDegrees(-info.getOrDefaultGeckolibData(FACE_PITCH, 0f)));
    }

    @Override
    public RenderType getRenderType(R state, Identifier texture) {
        return RenderTypes.entityTranslucent(texture);
    }

    @Override
    protected float getDeathMaxRotation(GeoRenderState state) {
        // The exported death clip already collapses this horizontal animal.
        return 0f;
    }

    private static Identifier texture(String name) {
        return Identifier.fromNamespaceAndPath(LogosMod.MODID, "textures/entity/" + name);
    }
}
