package dev.logos.client;

import static dev.logos.client.LogosFx.layer;
import static dev.logos.client.LogosFx.range;
import static dev.logos.client.LogosFx.size;
import static dev.logos.client.LogosFx.sphere;
import java.util.List;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.lang.Element;
import dev.logos.lang.Motion;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;

/** Preserves the original generic projectile until its own element file supplies a designed effect. */
abstract class LegacyProjectileVisual implements ProjectileVisual {
    private final Element element;

    LegacyProjectileVisual(Element element) { this.element = element; }

    @Override public void move(Vec3 position, Vec3 heading, float size, double now) {}
    @Override public void submit(CameraRenderState camera, double now, PoseStack pose, SubmitNodeCollector out, boolean head, boolean tail) {}
    @Override public boolean legacyAccents() { return true; }
    @Override public float particleScale(float size, boolean customLook) {
        return Math.max(.6f, size / .3f) * (customLook ? .2f : 1);
    }

    @Override public List<ParticleEmitter> particles(Motion motion) {
        boolean lingering = motion == Motion.HOMING || motion == Motion.BOOMERANG || motion == Motion.ORBIT;
        float spread = motion == Motion.WANDER ? .08f : .03f;
        return List.of(
                layer(element, false, element == Element.LIGHTNING ? 22 : 16, 0, size(.1f, .22f), range(0, spread),
                        lingering ? range(12, 22) : range(8, 14), sphere(.1f)),
                layer(element, true, 6, 0, size(.5f, .85f), range(0, .005f), range(5, 9), sphere(.05f)));
    }
}
