package dev.logos.client;

import org.joml.Vector3f;

import com.geckolib.cache.model.GeoBone;
import com.geckolib.cache.model.cuboid.CuboidGeoBone;
import com.geckolib.cache.model.cuboid.GeoCube;
import com.geckolib.constant.DataTickets;
import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.model.DefaultedEntityGeoModel;
import com.geckolib.renderer.GeoEntityRenderer;
import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.LogosMod;
import dev.logos.runtime.AlchemicalColossus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The alchemical colossus (assets/logos/geckolib/…/alchemical_colossus). Its scorched shell — in a
 * frenzy and after one, until maintained — is the animated smouldering texture with its glowmask
 * drawn over it; the normal shell has no glow. Each frame it reports where its hands, seats and
 * muzzles are, so riders and the held sit exactly in them. While it aims its palm cannon, its right
 * upper arm is turned on top of the animation so the palm points at what it shoots (an aim offset).
 * The three reservoir tubes on its back show how full its pool is: the glowing fill sinks as its mana
 * is spent, and the spent part of each tube is drawn again in the same material, blackened.
 */
public class ColossusRenderer<R extends LivingEntityRenderState & GeoRenderState> extends GeoEntityRenderer<AlchemicalColossus, R> {
    private static final Identifier NORMAL = Identifier.fromNamespaceAndPath(LogosMod.MODID, "textures/entity/alchemical_colossus.png");
    private static final Identifier SCORCHED = Identifier.fromNamespaceAndPath(LogosMod.MODID, "textures/entity/alchemical_colossus_berserk.png");
    private static final DataTicket<Boolean> SCORCHED_SHELL = DataTicket.create("logos_colossus_scorched", Boolean.class);
    private static final DataTicket<Integer> ENTITY = DataTicket.create("logos_colossus_id", Integer.class);
    private static final DataTicket<Float> ARM_YAW = DataTicket.create("logos_colossus_arm_yaw", Float.class);
    private static final DataTicket<Float> ARM_PITCH = DataTicket.create("logos_colossus_arm_pitch", Float.class);
    private static final DataTicket<Float> FILL = DataTicket.create("logos_colossus_fill", Float.class);
    /** What its grabbing hand reaches for (world), and how fully the reach is drawn. */
    private static final DataTicket<Vec3> REACH_GOAL = DataTicket.create("logos_colossus_reach_goal", Vec3.class);
    private static final DataTicket<Float> REACH_WEIGHT = DataTicket.create("logos_colossus_reach_weight", Float.class);
    /** The fill of its three reservoir tubes; each is pivoted at its foot, so it shrinks downward. */
    private static final String[] RESERVOIR = {"energy_fill_left", "energy_fill_center", "energy_fill_right"};
    /** The spent part of a tube: the same texture, darkened almost to black. */
    private static final int SPENT = 0xFF1E1E22;
    private static final String[] REPORTED = {"right_hand", "left_hand", "attachment_seat_left", "attachment_seat_right", "attachment_muzzle_right", "attachment_muzzle_left", "right_upper_arm", "left_foot", "right_foot"};

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
        float[] turn = c.drawnArmTurn(partialTick);
        state.addGeckolibData(ARM_YAW, turn[0]);
        state.addGeckolibData(ARM_PITCH, turn[1]);
        state.addGeckolibData(FILL, c.fill());
        Vec3 goal = c.reachGoal();
        if (goal != null) {
            state.addGeckolibData(REACH_GOAL, goal);
            state.addGeckolibData(REACH_WEIGHT, c.reachWeight(partialTick));
        }
    }

    /**
     * The aim offset: the raised arm turned toward its aim. In this model a negative turn about the
     * bone's Y swings the raised arm toward the colossus's right, and a positive turn about X raises it
     * (both checked against the drawn bones in the logos_colossus scenario).
     */
    @Override
    public void adjustModelBonesForRender(RenderPassInfo<R> info, BoneSnapshots snapshots) {
        super.adjustModelBonesForRender(info, snapshots);
        float fill = info.getOrDefaultGeckolibData(FILL, 1f);
        if (fill < 1) {
            for (String tube : RESERVOIR) snapshots.ifPresent(tube, s -> {
                if (fill <= 0.001f) s.skipRender(true);
                else s.setScaleY(s.getScaleY() * fill);
            });
        }
        Vec3 goal = info.getGeckolibData(REACH_GOAL);
        float weight = info.getOrDefaultGeckolibData(REACH_WEIGHT, 0f);
        if (goal != null && weight > 0) {
            // The reach: its arm (and if need be its torso) posed onto the point, in the model's own space.
            R s = info.renderState();
            float yaw = info.getOrDefaultGeckolibData(DataTickets.ENTITY_BODY_YAW, 0f);
            Vector3f local = new Vector3f((float) (goal.x - s.x), (float) (goal.y - s.y - 0.01), (float) (goal.z - s.z))
                    .rotateY(-(180 - yaw) * Mth.DEG_TO_RAD);
            ColossusArmIk.solve(info.model(), snapshots, local, weight);
        }
        float yaw = info.getOrDefaultGeckolibData(ARM_YAW, 0f), pitch = info.getOrDefaultGeckolibData(ARM_PITCH, 0f);
        if (yaw == 0 && pitch == 0) return;
        snapshots.ifPresent("right_upper_arm", arm -> {
            arm.setRotY(arm.getRotY() - yaw);
            arm.setRotX(arm.getRotX() + pitch);
        });
    }

    /** Above each tube's glowing fill, the spent part: the same tube again, blackened, from the fill's top to the tube's. */
    private void submitSpent(RenderPassInfo<R> info, GeoBone pack, SubmitNodeCollector out, float fill) {
        RenderType type = getRenderType(info.renderState(), getGeoModel().getTextureResource(info.renderState()));
        int light = info.packedLight(), overlay = info.packedOverlay();
        out.submitCustomGeometry(info.poseStack(), type, (pose, vertices) -> {
            PoseStack ps = new PoseStack();
            ps.last().set(pose);
            pack.translateAwayFromPivotPoint(ps);
            for (String name : RESERVOIR) {
                if (!(info.model().getBone(name).orElse(null) instanceof CuboidGeoBone tube)) continue;
                for (GeoCube cube : tube.cubes) {
                    ps.pushPose();
                    tube.translateToPivotPoint(ps);
                    ps.translate(0, fill * cube.size().y / 16, 0);
                    ps.scale(1, 1 - fill, 1);
                    tube.translateAwayFromPivotPoint(ps);
                    cube.render(ps, vertices, light, overlay, SPENT);
                    ps.popPose();
                }
            }
        });
    }

    @Override
    public void adjustRenderPose(RenderPassInfo<R> info) {
        super.adjustRenderPose(info);
        Integer id = info.getOrDefaultGeckolibData(ENTITY, -1);
        var level = Minecraft.getInstance().level;
        float fill = info.getOrDefaultGeckolibData(FILL, 1f);
        if (fill < 0.999f) info.model().getBone("backpack").ifPresent(pack -> info.addPerBoneRender(pack, (pass, bone, out) -> submitSpent(pass, bone, out, fill)));
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
