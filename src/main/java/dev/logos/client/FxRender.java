package dev.logos.client;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import dev.logos.client.FxLayers.Palette;
import dev.logos.client.FxLive.Beam;
import dev.logos.client.FxLive.LiveRay;
import dev.logos.client.FxLive.Look;
import dev.logos.client.FxLive.Trail;
import dev.logos.runtime.AlchemicalColossus;
import net.minecraft.client.Minecraft;
import dev.logos.client.port.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import dev.logos.client.port.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import dev.logos.client.port.LightCoordsUtil;
import net.minecraft.world.item.ItemDisplayContext;
import dev.logos.client.port.SubmitCustomGeometryEvent;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Draws, every frame, what {@link FxLive} holds: lasting beams, the rays and beam wakes still showing, and the
 * spell bodies in flight with what they wear. The other spell visuals that share the frame are submitted from
 * here too, in between, so that they all go into the {@link ShaderPackOverlay} frame this begins.
 */
final class FxRender {
    private FxRender() {}

    /** Draws lasting beams, rays and what they leave, ward spheres, wand charges, fields, the sky over area weather, and the item models of spell bodies that wear a {@code :look}. */
    static void submit(SubmitCustomGeometryEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ShaderPackOverlay.begin();
        Level level = mc.level;
        if (level == null || (FxLive.TRAILS.isEmpty() && FxLive.BEAMS.isEmpty() && FxLive.RAYS.isEmpty() && !WardSpheres.any() && !ChargeVisuals.any() && !FieldVisuals.any() && !VeinVisuals.any() && !AreaWeatherSky.any()
                && !dev.logos.runtime.ConstructEntity.any() && !AnchorMarkers.showing())) return;
        CameraRenderState cam = event.getLevelRenderState().cameraRenderState;
        Quaternionf toCamera = new Quaternionf(cam.orientation).conjugate();
        float pt = mc.getTimer().getGameTimeDeltaPartialTick(false);
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector vfx = ShaderPackOverlay.collector(event.getSubmitNodeCollector());
        for (Beam b : FxLive.BEAMS.values()) submitBeam(mc, level, b, cam, pt, pose,
                b.dedicated() ? vfx : event.getSubmitNodeCollector());
        ConstructRender.submit(mc, level, cam, pt, pose, event.getSubmitNodeCollector(), vfx);
        WardSpheres.submit(mc, cam, pt, pose, vfx);
        for (LiveRay r : FxLive.RAYS) r.visual().submit(level, cam, pt, pose, vfx);
        ChargeVisuals.submit(level, cam, pt, pose, vfx);
        FieldVisuals.submit(level, cam, pt, pose, vfx);
        VeinVisuals.submit(level, cam, pt, pose, vfx);
        AreaWeatherSky.submit(level, cam, pt, pose, vfx);
        AnchorMarkers.submit(mc, cam, pt, pose, event.getSubmitNodeCollector());
        for (var e : FxLive.TRAILS.entrySet()) {
            Trail t = e.getValue();
            if (t.visual != null) t.visual.submit(cam, level.getGameTime() + pt,
                    pose, vfx, t.look == null, t.particle == null);
            Look l = t.look;
            if (l == null) continue;
            Vector3f h = toCamera.transform(new Vector3f((float) l.heading.x, (float) l.heading.y, (float) l.heading.z));
            if (h.x * h.x + h.y * h.y > 0.04f) l.angle = (float) Math.atan2(h.y, h.x);
            if (Math.abs(h.x) > 0.2f) l.tumble = h.x > 0 ? -1 : 1;
            float turned = (float) ((level.getGameTime() - l.born + pt) / 20.0 * l.spin * Math.PI * 2) * l.tumble;
            Vec3 p = l.prev.lerp(l.cur, pt);
            if (l.model != null) {
                submitModel(mc, l, p, turned, cam, pt, pose, event.getSubmitNodeCollector());
                continue;
            }
            pose.pushPose();
            pose.translate(p.x - cam.pos.x, p.y - cam.pos.y, p.z - cam.pos.z);
            pose.mulPose(cam.orientation);
            // Item sprites point up-right (+x +y); turn that diagonal onto the heading.
            pose.mulPose(Axis.ZP.rotation(l.angle + turned - (float) (Math.PI / 4)));
            pose.scale(l.scale, l.scale, l.scale);
            mc.getItemModelResolver().updateForTopItem(l.state, l.stack, ItemDisplayContext.NONE, level, null, e.getKey());
            l.state.submit(pose, event.getSubmitNodeCollector(), LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
    }

    /**
     * A creature-model look: the stand-in faces the way the body flies, scaled so its larger side is the look's
     * size, centred on the body, and tumbling {@code turned} over its side. Always fully lit, like an item look.
     */
    private static void submitModel(Minecraft mc, Look l, Vec3 p, float turned, CameraRenderState cam, float pt, PoseStack pose, SubmitNodeCollector out) {
        Entity m = l.model;
        float yaw = (float) Math.toDegrees(Math.atan2(-l.heading.x, l.heading.z));
        m.setPos(p);
        m.setYRot(yaw);
        m.yRotO = yaw;
        if (m instanceof net.minecraft.world.entity.LivingEntity le) le.yBodyRot = le.yBodyRotO = le.yHeadRot = le.yHeadRotO = yaw;
        var state = mc.getEntityRenderDispatcher().extractEntity(m, pt);
        state.lightCoords = LightCoordsUtil.FULL_BRIGHT;
        float s = l.scale / Math.max(0.25f, Math.max(m.getBbHeight(), m.getBbWidth()));
        Vec3 side = new Vec3(l.heading.z, 0, -l.heading.x);
        side = side.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : side.normalize();
        pose.pushPose();
        pose.translate(p.x - cam.pos.x, p.y - cam.pos.y, p.z - cam.pos.z);
        pose.mulPose(new Quaternionf().rotateAxis(turned, (float) side.x, 0, (float) side.z));
        pose.scale(s, s, s);
        pose.translate(0, -m.getBbHeight() / 2, 0);
        mc.getEntityRenderDispatcher().submit(state, cam, 0, 0, 0, pose, out);
        pose.popPose();
    }

    /**
     * A beam follows the interpolated gaze, keeping the server's blocked length, but leaves the
     * same cast point as charging effects. The five damage elements use dedicated visuals; the remaining
     * elements keep the beacon-style core/glow, swelling from a thin outlet to their full radius.
     * A colossus's beam leaves its palm, where the model's muzzle bone was drawn this frame.
     */
    private static void submitBeam(Minecraft mc, Level level, Beam b, CameraRenderState cam, float pt, PoseStack pose, SubmitNodeCollector out) {
        Vec3 from = b.prevFrom.lerp(b.from, pt);
        Vec3 to = b.prevTo.lerp(b.to, pt);
        boolean firstPerson = false;
        Entity anchor = b.anchor >= 0 ? level.getEntity(b.anchor) : null;
        if (anchor instanceof AlchemicalColossus) {
            Vec3 muzzle = CastPoints.drawn(anchor);
            if (muzzle != null) from = muzzle;
        } else if (anchor != null) {
            double len = b.from.distanceTo(b.to);
            to = anchor.getEyePosition(pt).add(anchor.getViewVector(pt).scale(len));
            firstPerson = anchor == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson();
            from = CastPoints.of(anchor, pt);
        }
        // Its end races out at BEAM_REACH blocks a tick, then holds; only the picture, the server's reach is unchanged.
        double reach = (level.getGameTime() - b.born + pt) * FxLive.BEAM_REACH, full = from.distanceTo(to);
        if (reach < full) to = from.lerp(to, reach / full);
        b.shownFrom = from;
        b.shownTo = to;
        if (b.fire != null) {
            b.fire.submit(level, from, to, b.radius, cam, pose, out);
            return;
        }
        if (b.frost != null) {
            b.frost.submit(level, from, to, b.radius, cam, pose, out);
            return;
        }
        if (b.lightning != null) {
            b.lightning.submit(level, from, to, b.radius, cam, pose, out);
            return;
        }
        if (b.voidBeam != null) {
            b.voidBeam.submit(level, from, to, b.radius, cam, pose, out);
            return;
        }
        if (b.physical != null) {
            b.physical.submit(level, from, to, b.radius, cam, pose, out);
            return;
        }
        if (b.arcane != null) {
            b.arcane.submit(level, from, to, b.radius, cam, pose, out);
            return;
        }
        if (b.life != null) {
            b.life.submit(level, from, to, b.radius, cam, pose, out);
            return;
        }
        Vec3 d = to.subtract(from);
        float len = (float) d.length();
        if (len < 0.05f) return;
        d = d.scale(1 / len);
        Palette pal = FxLayers.PALETTES.get(b.element);
        float r = b.radius;
        float taper = Math.min(len * 0.5f, firstPerson ? 2.5f : 1.2f);
        float startFrac = firstPerson ? 0.12f : 0.3f;
        float flow = ((level.getGameTime() % 200) + pt) * 0.08f;
        pose.pushPose();
        pose.translate(from.x - cam.pos.x, from.y - cam.pos.y, from.z - cam.pos.z);
        pose.mulPose(new Quaternionf().rotationTo(0, 1, 0, (float) d.x, (float) d.y, (float) d.z));
        Tube core = new Tube(len, taper, startFrac, r * 0.4f, 0xFF000000 | pal.bright(), flow, false);
        Tube glow = new Tube(len, taper, startFrac, r, (0x70 << 24) | (pal.core() & 0xFFFFFF), flow * 0.6f, true);
        out.submitCustomGeometry(pose, RenderType.beaconBeam(BeaconRenderer.BEAM_LOCATION, false), core::render);
        out.submitCustomGeometry(pose, RenderType.beaconBeam(BeaconRenderer.BEAM_LOCATION, true), glow::render);
        pose.popPose();
    }

    /**
     * A round tube along local +Y from 0 to {@code len}, swelling from {@code startFrac × radius} to
     * {@code radius} over {@code taper}. {@code twoSided} also emits inward faces, for a glow seen from inside.
     */
    private record Tube(float len, float taper, float startFrac, float radius, int color, float flow, boolean twoSided) {
        private static final int SIDES = 10;
        private static final float[] RINGS = {0, 0.25f, 0.5f, 0.75f, 1};

        float radiusAt(float y) {
            float t = taper <= 0 ? 1 : Math.min(1, y / taper);
            t = t * t * (3 - 2 * t);
            return radius * (startFrac + (1 - startFrac) * t);
        }

        void render(PoseStack.Pose pose, VertexConsumer buf) {
            float[] ys = new float[RINGS.length + 1];
            for (int i = 0; i < RINGS.length; i++) ys[i] = RINGS[i] * taper;
            ys[RINGS.length] = len;
            for (int s = 0; s < ys.length - 1; s++) {
                float y0 = ys[s], y1 = ys[s + 1];
                if (y1 - y0 < 1e-4f) continue;
                float r0 = radiusAt(y0), r1 = radiusAt(y1);
                // The beacon texture repeats every block along the beam and scrolls away from the caster.
                float v0 = y0 - flow, v1 = y1 - flow;
                for (int k = 0; k < SIDES; k++) {
                    double a0 = Math.PI * 2 * k / SIDES, a1 = Math.PI * 2 * (k + 1) / SIDES;
                    float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
                    float u0 = (float) k / SIDES, u1 = (float) (k + 1) / SIDES;
                    vertex(pose, buf, c0 * r1, y1, s0 * r1, u0, v1);
                    vertex(pose, buf, c0 * r0, y0, s0 * r0, u0, v0);
                    vertex(pose, buf, c1 * r0, y0, s1 * r0, u1, v0);
                    vertex(pose, buf, c1 * r1, y1, s1 * r1, u1, v1);
                    if (twoSided) {
                        vertex(pose, buf, c1 * r1, y1, s1 * r1, u1, v1);
                        vertex(pose, buf, c1 * r0, y0, s1 * r0, u1, v0);
                        vertex(pose, buf, c0 * r0, y0, s0 * r0, u0, v0);
                        vertex(pose, buf, c0 * r1, y1, s0 * r1, u0, v1);
                    }
                }
            }
        }

        private void vertex(PoseStack.Pose pose, VertexConsumer buf, float x, float y, float z, float u, float v) {
            buf.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
        }
    }
}
