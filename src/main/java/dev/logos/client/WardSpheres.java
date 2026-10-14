package dev.logos.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.runtime.Effects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Keeps one {@link WardSphereVisual} for each body near the camera that holds a spell shield. The shield
 * is read from the synced max_absorption modifier ({@link Effects#shielded}), so no packet is needed.
 */
public final class WardSpheres {
    private static final double RANGE = 64;
    private record Sphere(LivingEntity body, WardSphereVisual visual) {}
    private static final Map<Integer, Sphere> SPHERES = new HashMap<>();

    private WardSpheres() {}

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Entity camera = mc.getCameraEntity();
        if (level == null || camera == null) {
            SPHERES.values().forEach(s -> s.visual().end());
            SPHERES.clear();
            return;
        }
        Set<Integer> held = new HashSet<>();
        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity body) || !body.isAlive() || !Effects.shielded(body)) continue;
            if (body.distanceToSqr(camera) > RANGE * RANGE) continue;
            held.add(body.getId());
            Sphere s = SPHERES.get(body.getId());
            if (s == null || s.body() != body) {
                if (s != null) s.visual().end();
                s = new Sphere(body, new WardSphereVisual());
                SPHERES.put(body.getId(), s);
            }
            s.visual().tick(body);
        }
        for (Iterator<Map.Entry<Integer, Sphere>> it = SPHERES.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (held.contains(entry.getKey())) continue;
            entry.getValue().visual().end();
            it.remove();
        }
    }

    static boolean any() {
        return !SPHERES.isEmpty();
    }

    /** Whether a sphere is kept for {@code body} (the client scenes check it). */
    public static boolean holds(Entity body) {
        Sphere s = SPHERES.get(body.getId());
        return s != null && s.body() == body;
    }

    static void submit(Minecraft mc, CameraRenderState cam, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        Entity camera = mc.getCameraEntity();
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        for (Sphere s : SPHERES.values()) {
            s.visual().submit(s.body(), cam, partialTick, pose, out, firstPerson && s.body() == camera);
        }
    }
}
