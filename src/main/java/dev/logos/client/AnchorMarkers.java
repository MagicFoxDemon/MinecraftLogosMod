package dev.logos.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.logos.LogosMod;
import dev.logos.item.SpellBag;
import dev.logos.net.AnchorsPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * While a wand or an anchor nail is in hand, your anchors in this world (within 64 blocks) float
 * over their spots as the anchor sigil with their ^name, so you remember where you set them.
 */
final class AnchorMarkers {
    private static final double RANGE = 64;

    private AnchorMarkers() {}

    static boolean showing() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || ClientAnchors.all().isEmpty()) return false;
        for (InteractionHand h : InteractionHand.values()) if (p.getItemInHand(h).is(LogosMod.ANCHOR_NAIL.get())) return true;
        return SpellBag.heldWand(p) != null;
    }

    static void submit(Minecraft mc, CameraRenderState cam, float pt, PoseStack pose, SubmitNodeCollector out) {
        if (!showing() || mc.level == null) return;
        String here = mc.level.dimension().identifier().toString();
        float bob = (float) Math.sin((mc.level.getGameTime() + pt) * 0.08) * 0.08f;
        for (AnchorsPayload.Entry e : ClientAnchors.all()) {
            if (!e.dimension().equals(here)) continue;
            Vec3 at = new Vec3(e.x(), e.y() + 0.9 + bob, e.z());
            double d2 = at.distanceToSqr(cam.pos);
            if (d2 > RANGE * RANGE) continue;
            pose.pushPose();
            pose.translate(at.x - cam.pos.x, at.y - cam.pos.y, at.z - cam.pos.z);
            pose.mulPose(cam.orientation);
            out.submitCustomGeometry(pose, RenderTypes.beaconBeam(AnchorScreens.ICON, true), AnchorMarkers::sigil);
            pose.popPose();
            pose.pushPose();
            pose.translate(e.x() - cam.pos.x, e.y() - cam.pos.y, e.z() - cam.pos.z);
            out.submitNameTag(pose, new Vec3(0, 1.05 + bob, 0), 0, Component.literal("⚓ ^" + e.name()), false,
                    LightCoordsUtil.FULL_BRIGHT, d2, cam);
            pose.popPose();
        }
    }

    /** The anchor sigil as a camera-facing square, half a block across, drawn from both sides. */
    private static void sigil(PoseStack.Pose pose, VertexConsumer buf) {
        float s = 0.28f;
        float[][] c = {{-s, -s, 0, 1}, {s, -s, 1, 1}, {s, s, 1, 0}, {-s, s, 0, 0}};
        for (int i = 0; i < 4; i++) vertex(pose, buf, c[i]);
        for (int i = 3; i >= 0; i--) vertex(pose, buf, c[i]);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, float[] v) {
        buf.addVertex(pose, v[0], v[1], 0).setColor(0xFFFFFFFF).setUv(v[2], v[3]).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }
}
