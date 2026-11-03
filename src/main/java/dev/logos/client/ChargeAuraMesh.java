package dev.logos.client;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * A static mesh of the _ChargeGatherVFX systems the colossal auras follow (see CHARGE_VFX.md), read from
 * {@code vfx_meshes/<name>.json}: plain triangles, positions in blocks with Y up (the Unreal export's
 * centimetres / 100), a UV set and, for some, a second one. Drawn like a Niagara mesh renderer: placed by a
 * transform, tinted per vertex, its UVs offset and scaled (the material's panner); the triangles are written
 * as degenerate quads, since the charge materials draw quads. A missing or broken file draws nothing.
 */
final class ChargeAuraMesh {
    private static final Map<String, ChargeAuraMesh> CACHE = new HashMap<>();
    private static final ChargeAuraMesh EMPTY = new ChargeAuraMesh(new float[0], new float[0], null);

    /** x, y, z per vertex, three vertices per triangle. */
    final float[] pos;
    /** u, v per vertex. */
    final float[] uv;
    /** The second UV set, or null. */
    final float[] uv1;

    private ChargeAuraMesh(float[] pos, float[] uv, float[] uv1) {
        this.pos = pos;
        this.uv = uv;
        this.uv1 = uv1;
    }

    /** {@code charge_aura/spiral_streak} reads {@code assets/logos/vfx_meshes/charge_aura/spiral_streak.json}. */
    static ChargeAuraMesh get(String name) {
        return CACHE.computeIfAbsent(name, ChargeAuraMesh::load);
    }

    private static ChargeAuraMesh load(String name) {
        Identifier id = Identifier.fromNamespaceAndPath("logos", "vfx_meshes/" + name + ".json");
        try (Reader in = Minecraft.getInstance().getResourceManager().openAsReader(id)) {
            JsonObject o = JsonParser.parseReader(in).getAsJsonObject();
            return new ChargeAuraMesh(floats(o.getAsJsonArray("pos")), floats(o.getAsJsonArray("uv")),
                    o.has("uv1") ? floats(o.getAsJsonArray("uv1")) : null);
        } catch (Exception e) {
            return EMPTY;
        }
    }

    private static float[] floats(JsonArray a) {
        float[] f = new float[a.size()];
        for (int i = 0; i < f.length; i++) f[i] = a.get(i).getAsFloat();
        return f;
    }

    int vertices() {
        return pos.length / 3;
    }

    /** The colour of a vertex from where it sits in the mesh (before the transform) and its UV. */
    @FunctionalInterface
    interface Shade {
        int color(float x, float y, float z, float u, float v);
    }

    /**
     * Draws the mesh moved to {@code origin} and placed by {@code transform} (scale, turn and offset in blocks, about the
     * mesh's own origin), its UVs mapped to {@code u0 + u * uScale, v0 + v * vScale} from the first set, or the second
     * when {@code secondUv} (falling back to the first if the mesh has none), each vertex coloured by {@code shade}.
     */
    void draw(PoseStack.Pose pose, VertexConsumer buf, Vec3 origin, Matrix4f transform, boolean secondUv,
            float u0, float v0, float uScale, float vScale, Shade shade) {
        float[] uvs = secondUv && uv1 != null ? uv1 : uv;
        Vector3f p = new Vector3f();
        for (int t = 0; t + 2 < vertices(); t += 3) {
            for (int k = 0; k < 4; k++) {
                int i = t + Math.min(k, 2);
                float x = pos[i * 3], y = pos[i * 3 + 1], z = pos[i * 3 + 2], u = uvs[i * 2], v = uvs[i * 2 + 1];
                transform.transformPosition(x, y, z, p);
                buf.addVertex(pose, (float) origin.x + p.x, (float) origin.y + p.y, (float) origin.z + p.z)
                        .setColor(shade.color(x, y, z, u, v)).setUv(u0 + u * uScale, v0 + v * vScale)
                        .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
            }
        }
    }
}
