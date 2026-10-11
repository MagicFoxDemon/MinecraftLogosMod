package dev.logos.client;

import java.util.List;
import static dev.logos.client.LogosFx.layer;
import static dev.logos.client.LogosFx.range;
import static dev.logos.client.LogosFx.size;
import static dev.logos.client.LogosFx.sphere;
import static dev.logos.client.ProjectileParticles.configureTexture;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import dev.logos.lang.Element;
import dev.logos.lang.Motion;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

/** Gabriel Cube01's spinning, distorted cube head with QFX Winter's narrow flowing wake. */
final class FrostProjectileVisual implements ProjectileVisual {
    private static final RenderType CUBE = material(true);
    private static final RenderType WAKE = material(false);
    // Cube_01.fbx has eight corners at +/-1 and these six quad faces, with a full face UV on each.
    private static final int[][] FACES = {{0,1,3,2},{2,3,7,6},{6,7,5,4},{4,5,1,0},{2,6,4,0},{7,3,1,5}};
    private final ProjectileMotion motion;
    private final ProjectilePath path;
    private Vec3 heading = new Vec3(0,0,1);
    private float scale;

    FrostProjectileVisual(Vec3 position, double now) {
        motion = new ProjectileMotion(position, now);
        path = new ProjectilePath(position);
    }

    @Override
    public void move(Vec3 position, Vec3 heading, float size, double now) {
        motion.move(position, now);
        path.move(position);
        if (heading.lengthSqr() > 1e-6) this.heading = heading.normalize();
        scale = visualScale(size);
    }

    /** The cube has a .4-block base edge; cap the default visual at a half-block edge. */
    static float visualScale(float size) {
        return Math.min(1.25f, Math.max(.6f, size / .3f));
    }

    @Override
    public void submit(CameraRenderState camera, double now, PoseStack pose, SubmitNodeCollector out, boolean head, boolean tail) {
        Vec3 origin = motion.sample(now);
        float size = scale;
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        if (tail) {
            List<Vec3> samples = path.samples(origin, heading, 2.2 * size, 0);
            out.submitCustomGeometry(pose, WAKE, (p,b) -> ribbon(p,b,samples,camera.pos,size));
        }
        if (head) {
            pose.translate(origin.x, origin.y, origin.z);
            pose.mulPose(new Quaternionf().rotationTo(new Vector3f(0,0,1), heading.toVector3f())
                    .rotateZ((float)(now * Math.PI / 10)).rotateY(.7f).rotateX(.35f));
            out.submitCustomGeometry(pose, CUBE, (p,b) -> cube(p,b,.2f * size));
        }
        pose.popPose();
    }

    private static void cube(PoseStack.Pose pose, VertexConsumer buf, float radius) {
        for (int face = 0; face < FACES.length; face++) {
            int color = face % 3 == 0 ? 0xFF449FFF : face % 3 == 1 ? 0xFF76CFFF : 0xFF387CE8;
            for (int j = 0; j < 4; j++) {
                int index = FACES[face][j];
                Vec3 corner = new Vec3((index & 4) == 0 ? -radius : radius,
                        (index & 2) == 0 ? -radius : radius, (index & 1) == 0 ? -radius : radius);
                vertex(pose,buf,corner,j < 2 ? 0 : 1,j == 0 || j == 3 ? 0 : 1,color);
            }
        }
    }

    private static void ribbon(PoseStack.Pose pose, VertexConsumer buf, List<Vec3> samples, Vec3 camera, float size) {
        for (int i = 0; i < 16; i++) {
            float a = i / 16f, b = (i+1) / 16f;
            Vec3 p0 = ProjectilePath.point(samples,a), p1 = ProjectilePath.point(samples,b);
            Vec3 side = p1.subtract(p0).cross(camera.subtract(p0));
            side = side.lengthSqr() < 1e-8 ? new Vec3(1,0,0) : side.normalize();
            Vec3 s0 = side.scale(.3 * size * (1-a)), s1 = side.scale(.3 * size * (1-b));
            int c0 = (int)(245*(1-a)) << 24 | 0x57AEFF, c1 = (int)(245*(1-b)) << 24 | 0x57AEFF;
            vertex(pose,buf,p0.add(s0),a,0,c0);vertex(pose,buf,p0.subtract(s0),a,1,c0);
            vertex(pose,buf,p1.subtract(s1),b,1,c1);vertex(pose,buf,p1.add(s1),b,0,c1);
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color) {
        buf.addVertex(pose,(float)p.x,(float)p.y,(float)p.z).setColor(color).setUv(u,v).setLight(LightCoordsUtil.FULL_BRIGHT);
    }

    private static RenderType material(boolean cube) {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/frost_" + (cube ? "cube" : "trail")))
                .withFragmentShader(id("core/frost_" + (cube ? "cube" : "trail")))
                .withSampler("Sampler1").withCull(cube)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();
        return RenderType.create("logos_frost_" + (cube ? "cube" : "trail"), RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id("textures/vfx/frost/" + (cube ? "cube_face" : "winter_trail") + ".png"))
                .withTexture("Sampler1", id("textures/vfx/frost/" + (cube ? "cube_distortion" : "winter_mask") + ".png"))
                .sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos",path); }

    @Override
    public List<ParticleEmitter> particles(Motion motion) {
        ParticleEmitter snow = layer(Element.FROST, false, .05f, 0, size(.06f, .1f), range(0, .02f), range(4, 14), sphere(.18f));
        configureTexture(snow, "frost/winter_snowflake", false);
        snow.config.emission.setDistanceRate(NumberFunction.constant(2));
        snow.config.setStartColor(NumberFunction.color(0xDB80C6FF));
        snow.config.physics.setEnable(false);

        ParticleEmitter shards = layer(Element.FROST, false, .05f, 0, size(.08f, .13f), range(0, .025f), range(4, 12), sphere(.17f));
        configureTexture(shards, "frost/winter_shards", false);
        shards.config.emission.setDistanceRate(NumberFunction.constant(1.5f));
        shards.config.setStartColor(NumberFunction.color(0xD9B7E8FF));
        shards.config.physics.setEnable(false);
        shards.config.uvAnimation.setEnable(true);
        shards.config.uvAnimation.setTiles(new org.joml.Vector2i(2, 1));
        shards.config.uvAnimation.setStartFrame(range(0, 2));
        shards.config.uvAnimation.setFrameOverTime(NumberFunction.constant(0));

        ParticleEmitter stars = layer(Element.FROST, true, .05f, 0, size(.1f, .2f), range(0, .015f), range(4, 10), sphere(.13f));
        configureTexture(stars, "frost/winter_star", true);
        stars.config.emission.setDistanceRate(NumberFunction.constant(1));
        stars.config.setStartColor(NumberFunction.color(0x899CDAFF));
        stars.config.physics.setEnable(false);

        ParticleEmitter mist = layer(Element.FROST, false, .05f, 0, size(.4f, .55f), range(0, .006f), range(5, 9), sphere(.08f));
        configureTexture(mist, "frost/winter_mist", false);
        mist.config.emission.setDistanceRate(NumberFunction.constant(3));
        mist.config.setStartColor(NumberFunction.color(0x386DAFFF));
        mist.config.physics.setEnable(false);
        mist.config.shape.setPosition(new NumberFunction3(0, 0, -.2f));
        mist.config.uvAnimation.setEnable(true);
        mist.config.uvAnimation.setTiles(new org.joml.Vector2i(5, 5));
        mist.config.uvAnimation.setFrameOverTime(new Curve(0, 1, 0, 24, "age", "frame", new ECBCurves(0, 0, .33f, .33f, .67f, .67f, 1, 1)));
        return List.of(snow, shards, stars, mist);
    }

    @Override
    public float particleScale(float size, boolean customLook) { return visualScale(size); }
}
