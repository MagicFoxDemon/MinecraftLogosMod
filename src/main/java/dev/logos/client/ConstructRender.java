package dev.logos.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.logos.LogosMod;
import dev.logos.lang.Element;
import dev.logos.runtime.ConstructEntity;
import dev.logos.runtime.ConstructGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Draws constructs. By default as alchemical glass in the element's colour: a translucent panel
 * with a bright edge grid, one tile per block, built as the true shape (a smooth dome, a round
 * shield) while collision uses its cells. A one-way construct glows brighter on the face that
 * stops things. With {@code :look &block} each cell is drawn as that block instead.
 */
final class ConstructRender {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(LogosMod.MODID, "textures/misc/construct.png");
    private static final int SEGMENTS = 24, RINGS = 8;
    private static final BlockModelRenderState BLOCK = new BlockModelRenderState();

    private ConstructRender() {}

    /** Each element's field dress, whose circles a shield wearing {@code :sigil} shows. */
    private static final java.util.Map<Element, FieldStyle> SIGILS = new java.util.EnumMap<>(Element.class);

    /** {@code vfx} goes through {@link ShaderPackOverlay}, for the magic-circle shields drawn like field circles. */
    static void submit(Minecraft mc, Level level, CameraRenderState cam, float pt, PoseStack pose, SubmitNodeCollector out, SubmitNodeCollector vfx) {
        for (ConstructEntity c : ConstructEntity.near(level, new AABB(cam.pos, cam.pos).inflate(128))) {
            ConstructGeometry g = c.geometry();
            // A travelling construct moves in tick steps; draw it where it is between them. (Followers track the caster directly.)
            Vec3 lag = c.follows() ? Vec3.ZERO : new Vec3(net.minecraft.util.Mth.lerp(pt, c.xo, c.getX()) - c.getX(),
                    net.minecraft.util.Mth.lerp(pt, c.yo, c.getY()) - c.getY(), net.minecraft.util.Mth.lerp(pt, c.zo, c.getZ()) - c.getZ());
            Vec3 eye = cam.pos.subtract(lag);
            float fade = Math.min(1, (c.tickCount + pt) / 8f);
            if (c.look().isPresent()) {
                mc.getBlockModelResolver().update(BLOCK, c.look().get(), DisplayRenderer.BLOCK_DISPLAY_CONTEXT);
                // Cells grow into place as the construct forms.
                float s = (float) g.cell * (0.4f + 0.6f * fade);
                for (AABB cell : g.cells) {
                    Vec3 mid = cell.getCenter();
                    pose.pushPose();
                    pose.translate(mid.x - eye.x - s / 2, mid.y - eye.y - s / 2, mid.z - eye.z - s / 2);
                    pose.scale(s, s, s);
                    BLOCK.submit(pose, out, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
                    pose.popPose();
                }
                continue;
            }
            if (c.sigil() && g.shape == dev.logos.lang.ConstructShape.DISK) {
                sigil(c, g, eye, fade, (c.tickCount + pt) / 20f, pose, vfx);
                continue;
            }
            int rgb = LogosFx.coreColor(c.element());
            int front = argb(c.oneWay() ? 0.62f : 0.42f, fade, rgb);
            int other = argb(c.oneWay() ? 0.22f : 0.42f, fade, rgb);
            Mesh mesh = new Mesh(g, eye, front, other);
            out.submitCustomGeometry(pose, RenderTypes.beaconBeam(TEXTURE, true), mesh::render);
        }
    }

    /**
     * A shield as its element's magic circle, in the shield's own plane: a soft glow and a dark underlay behind, the field's outer,
     * middle and inner circles turning against each other. A one-way shield burns a little brighter.
     */
    private static void sigil(ConstructEntity c, ConstructGeometry g, Vec3 eye, float fade, float seconds, PoseStack pose, SubmitNodeCollector out) {
        FieldStyle s = SIGILS.computeIfAbsent(c.element(), e -> FieldVisual.create(e) instanceof FieldCore core ? core.style() : FieldStyle.of(e));
        Vec3 mid = g.center.subtract(eye);
        float r = (float) g.radius, a = fade * (c.oneWay() ? 1f : .85f);
        out.submitCustomGeometry(pose, ChargeParts.add("heal/glow"), (p, buf) ->
                ChargeParts.flat(p, buf, mid, g.right, g.up, r * 1.15f, 0, ChargeParts.argb(.35f * a, s.deep())));
        // Blended in the deep shade under the glowing lines, so the colour holds against a bright sky.
        out.submitCustomGeometry(pose, ChargeParts.blend(s.outer()), (p, buf) ->
                ChargeParts.flat(p, buf, mid, g.right, g.up, r, seconds * .35f, ChargeParts.argb(.6f * a, s.deep())));
        out.submitCustomGeometry(pose, ChargeParts.blend(s.inner()), (p, buf) ->
                ChargeParts.flat(p, buf, mid, g.right, g.up, r * .5f, seconds * .8f, ChargeParts.argb(.5f * a, s.deep())));
        out.submitCustomGeometry(pose, ChargeParts.add(s.outer()), (p, buf) ->
                ChargeParts.flat(p, buf, mid, g.right, g.up, r, seconds * .35f, ChargeParts.argb(.95f * a, s.main())));
        out.submitCustomGeometry(pose, ChargeParts.add(s.middle()), (p, buf) ->
                ChargeParts.flat(p, buf, mid, g.right, g.up, r * .78f, -seconds * .5f, ChargeParts.argb(.7f * a, s.main())));
        out.submitCustomGeometry(pose, ChargeParts.add(s.inner()), (p, buf) ->
                ChargeParts.flat(p, buf, mid, g.right, g.up, r * .5f, seconds * .8f, ChargeParts.argb(.9f * a, s.light())));
    }

    private static int argb(float alpha, float fade, int rgb) {
        return ((int) (alpha * fade * 255) << 24) | rgb;
    }

    /** The true shape in world space (relative to the camera), every face drawn from both sides. */
    private record Mesh(ConstructGeometry g, Vec3 cam, int front, int other) {
        void render(PoseStack.Pose pose, VertexConsumer buf) {
            double t = g.cell;
            switch (g.shape) {
                case BOX -> cuboid(pose, buf, g.width, g.height, g.depth);
                case WALL -> cuboid(pose, buf, g.width, g.height, t);
                case PLANE -> cuboid(pose, buf, g.width, t, g.depth);
                case DISK -> disk(pose, buf, g.radius, t);
                case DOME -> dome(pose, buf, g.radius);
            }
        }

        private Vec3 at(double x, double y, double z) {
            return g.center.add(g.right.scale(x)).add(g.up.scale(y)).add(g.forward.scale(z)).subtract(cam);
        }

        private void cuboid(PoseStack.Pose pose, VertexConsumer buf, double w, double h, double d) {
            double x = w / 2, y = h / 2, z = d / 2;
            boolean plane = g.shape == dev.logos.lang.ConstructShape.PLANE;
            // ±forward faces carry the wall's front; ±up faces a platform's.
            face(pose, buf, 0, 0, z, 1, 0, 0, 0, 1, 0, x, y, plane ? other : front);
            face(pose, buf, 0, 0, -z, 1, 0, 0, 0, 1, 0, x, y, other);
            face(pose, buf, x, 0, 0, 0, 0, 1, 0, 1, 0, z, y, other);
            face(pose, buf, -x, 0, 0, 0, 0, 1, 0, 1, 0, z, y, other);
            face(pose, buf, 0, y, 0, 1, 0, 0, 0, 0, 1, x, z, plane ? front : other);
            face(pose, buf, 0, -y, 0, 1, 0, 0, 0, 0, 1, x, z, other);
        }

        /** A rectangle centred at local (cx,cy,cz), spanning ±ha along local axis a and ±hb along b. */
        private void face(PoseStack.Pose pose, VertexConsumer buf, double cx, double cy, double cz,
                double ax, double ay, double az, double bx, double by, double bz, double ha, double hb, int color) {
            Vec3[] p = new Vec3[4];
            double[][] s = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            for (int i = 0; i < 4; i++) {
                double u = s[i][0] * ha, v = s[i][1] * hb;
                p[i] = at(cx + ax * u + bx * v, cy + ay * u + by * v, cz + az * u + bz * v);
            }
            quad(pose, buf, p, new float[] {(float) -ha, (float) ha, (float) ha, (float) -ha}, new float[] {(float) -hb, (float) -hb, (float) hb, (float) hb}, color);
        }

        private void disk(PoseStack.Pose pose, VertexConsumer buf, double r, double t) {
            for (int j = 0; j < SEGMENTS; j++) {
                double a0 = Math.PI * 2 * j / SEGMENTS, a1 = Math.PI * 2 * (j + 1) / SEGMENTS;
                double x0 = Math.cos(a0) * r, y0 = Math.sin(a0) * r, x1 = Math.cos(a1) * r, y1 = Math.sin(a1) * r;
                for (int side = -1; side <= 1; side += 2) {
                    double z = side * t / 2;
                    Vec3 c = at(0, 0, z);
                    quad(pose, buf, new Vec3[] {c, c, at(x0, y0, z), at(x1, y1, z)}, new float[] {0, 0, (float) x0, (float) x1},
                            new float[] {0, 0, (float) y0, (float) y1}, side > 0 ? front : other);
                }
                float arc0 = (float) (a0 * r), arc1 = (float) (a1 * r);
                quad(pose, buf, new Vec3[] {at(x0, y0, -t / 2), at(x1, y1, -t / 2), at(x1, y1, t / 2), at(x0, y0, t / 2)},
                        new float[] {arc0, arc1, arc1, arc0}, new float[] {0, 0, (float) t, (float) t}, other);
            }
        }

        private void dome(PoseStack.Pose pose, VertexConsumer buf, double r) {
            for (int i = 0; i < RINGS; i++) {
                double l0 = Math.PI / 2 * i / RINGS, l1 = Math.PI / 2 * (i + 1) / RINGS;
                for (int j = 0; j < SEGMENTS; j++) {
                    double a0 = Math.PI * 2 * j / SEGMENTS, a1 = Math.PI * 2 * (j + 1) / SEGMENTS;
                    Vec3[] p = {point(r, l0, a0), point(r, l0, a1), point(r, l1, a1), point(r, l1, a0)};
                    float u0 = (float) (a0 * r), u1 = (float) (a1 * r), v0 = (float) (l0 * r), v1 = (float) (l1 * r);
                    quad(pose, buf, p, new float[] {u0, u1, u1, u0}, new float[] {v0, v0, v1, v1}, front);
                }
            }
        }

        private Vec3 point(double r, double lat, double lon) {
            return g.center.add(Math.cos(lat) * Math.cos(lon) * r, Math.sin(lat) * r, Math.cos(lat) * Math.sin(lon) * r).subtract(cam);
        }

        private static void quad(PoseStack.Pose pose, VertexConsumer buf, Vec3[] p, float[] u, float[] v, int color) {
            for (int i = 0; i < 4; i++) vertex(pose, buf, p[i], u[i], v[i], color);
            for (int i = 3; i >= 0; i--) vertex(pose, buf, p[i], u[i], v[i], color);
        }

        private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color) {
            buf.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
        }
    }
}
