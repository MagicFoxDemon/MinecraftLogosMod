package dev.logos.client;

import java.util.List;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.lang.Element;
import dev.logos.lang.Motion;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;

/** Element-local flight visuals; packet handling and independent look/trail overrides stay shared. */
interface ProjectileVisual {
    void move(Vec3 position, Vec3 heading, float size, double now);
    void submit(CameraRenderState camera, double now, PoseStack pose, SubmitNodeCollector out, boolean head, boolean tail);
    List<ParticleEmitter> particles(Motion motion);
    float particleScale(float size, boolean customLook);
    default boolean legacyAccents() { return false; }

    /** All elements are wired in advance, so upgrading one never needs to edit the packet router. */
    static ProjectileVisual create(Element element, Vec3 position, double now) {
        return switch (element) {
            case FIRE -> new FireProjectileVisual(position, now);
            case FROST -> new FrostProjectileVisual(position, now);
            case LIGHTNING -> new LightningProjectileVisual(position, now);
            case VOID -> new VoidProjectileVisual(position, now);
            case PHYSICAL -> new PhysicalProjectileVisual(position, now);
            case ARCANE -> new ArcaneProjectileVisual(position, now);
            case LIFE -> new LifeProjectileVisual(position, now);
        };
    }
}
