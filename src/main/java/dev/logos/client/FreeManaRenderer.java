package dev.logos.client;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.logos.LogosMod;
import dev.logos.runtime.FreeManaEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import dev.logos.client.port.LightCoordsUtil;
import net.minecraft.util.Mth;

/**
 * Free mana as a swarm of glowing motes circling a common centre, each on its own tilted orbit.
 * More mana means more motes and a wider swarm. It fades in over its first moments.
 */
final class FreeManaRenderer extends EntityRenderer<FreeManaEntity> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(LogosMod.MODID, "textures/entity/free_mana.png");
    private static final RenderType RENDER_TYPE = RenderType.eyes(TEXTURE);
    private static final int MAX_MOTES = 28;
    /** The whole swarm is drawn at this size. */
    private static final float SCALE = 0.7f;
    /** Ticks to fade from invisible to fully visible after it appears (1.5 seconds). */
    private static final float FADE_IN_TICKS = 30;

    FreeManaRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0;
    }

    @Override
    public ResourceLocation getTextureLocation(FreeManaEntity entity) {
        return TEXTURE;
    }

    @Override
    public void render(FreeManaEntity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        float amount = (float) entity.amount();
        int seed = entity.getId();
        int motes = Mth.clamp(4 + (int) Math.sqrt(amount) * 2, 4, MAX_MOTES);
        float spread = Mth.clamp(0.18f + (float) Math.cbrt(amount) * 0.06f, 0.18f, 0.7f);
        float t = entity.tickCount + partialTick;
        float fade = Mth.clamp(t / FADE_IN_TICKS, 0, 1);
        Quaternionf face = Minecraft.getInstance().gameRenderer.getMainCamera().rotation();
        Vector3f right = face.transform(new Vector3f(1, 0, 0));
        Vector3f up = face.transform(new Vector3f(0, 1, 0));
        pose.pushPose();
        pose.translate(0, entity.getBbHeight() / 2 + Mth.sin(t * 0.08f) * 0.05f, 0);
        pose.scale(SCALE, SCALE, SCALE);
        PoseStack.Pose p = pose.last();
        VertexConsumer buf = buffers.getBuffer(RENDER_TYPE);
        {
            // The core: one larger, slowly pulsing mote at the centre.
            float core = 0.22f + spread * 0.25f + Mth.sin(t * 0.15f) * 0.03f;
            mote(p, buf, 0, 0, 0, core, right, up, (int) (255 * fade));
            for (int i = 0; i < motes; i++) {
                float h1 = hash(seed, i, 1), h2 = hash(seed, i, 2), h3 = hash(seed, i, 3);
                float radius = spread * (0.45f + 0.55f * h1);
                float speed = (0.05f + 0.07f * h2) * (h3 < 0.5f ? 1 : -1);
                float angle = t * speed + h1 * Mth.TWO_PI;
                // Each orbit is tilted differently, so the swarm reads as a ball, not a ring.
                float tilt = (h2 - 0.5f) * 2.4f, around = h3 * Mth.TWO_PI;
                float ox = Mth.cos(angle) * radius, oy = Mth.sin(angle) * radius * Mth.sin(tilt), oz = Mth.sin(angle) * radius * Mth.cos(tilt);
                float x = ox * Mth.cos(around) - oz * Mth.sin(around), z = ox * Mth.sin(around) + oz * Mth.cos(around);
                float twinkle = 0.65f + 0.35f * Mth.sin(t * (0.2f + h1 * 0.3f) + i);
                float size = (0.07f + 0.07f * h3) * (0.8f + 0.2f * twinkle);
                mote(p, buf, x, oy, z, size, right, up, (int) (255 * twinkle * fade));
            }
        }
        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, light);
    }

    /** A camera-facing square of half-size {@code s} centred at (x, y, z). */
    private static void mote(PoseStack.Pose p, VertexConsumer buf, float x, float y, float z, float s, Vector3f right, Vector3f up, int alpha) {
        vertex(p, buf, x - (right.x + up.x) * s, y - (right.y + up.y) * s, z - (right.z + up.z) * s, 0, 1, alpha);
        vertex(p, buf, x + (right.x - up.x) * s, y + (right.y - up.y) * s, z + (right.z - up.z) * s, 1, 1, alpha);
        vertex(p, buf, x + (right.x + up.x) * s, y + (right.y + up.y) * s, z + (right.z + up.z) * s, 1, 0, alpha);
        vertex(p, buf, x - (right.x - up.x) * s, y - (right.y - up.y) * s, z - (right.z - up.z) * s, 0, 0, alpha);
    }

    private static void vertex(PoseStack.Pose p, VertexConsumer buf, float x, float y, float z, float u, float v, int alpha) {
        buf.addVertex(p, x, y, z).setColor(255, 255, 255, alpha).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(p, 0, 1, 0);
    }

    /** A stable pseudo-random number in [0, 1) per entity, mote and channel. */
    private static float hash(int seed, int i, int k) {
        int h = seed * 0x9E3779B1 + i * 0x85EBCA6B + k * 0xC2B2AE35;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }
}
