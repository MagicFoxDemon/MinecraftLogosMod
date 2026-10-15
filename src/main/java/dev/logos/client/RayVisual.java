package dev.logos.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.lang.Element;
import dev.logos.runtime.Vfx;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * One {@code ray} (or link) as an element draws it: the instant burst along the line, then whatever it
 * leaves there fading. {@link LogosFx} makes one per ray, calls {@link #start} once, {@link #tick} every
 * client tick until it returns false, and {@link #submit} every frame. Until an element is upgraded it
 * reports {@link #legacy()} and the old bead chain is drawn instead.
 */
interface RayVisual {
    /** True until the element draws its own ray. */
    default boolean legacy() { return true; }

    /** Fired from {@code from} (the caster's cast point when it left their eyes) to {@code to}; {@code end} says what stopped it. */
    default void start(Level level, Vec3 from, Vec3 to, Vfx.RayEnd end) {}

    /** Each client tick; false once nothing is left to draw, and it is dropped. */
    default boolean tick(Level level) { return false; }

    /** Each frame; {@code out} already goes through {@link ShaderPackOverlay}. */
    default void submit(Level level, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {}

    /** Dropped early (the level went away, or it outlived {@code LogosFx}'s cap): stop anything still playing. */
    default void end() {}

    static RayVisual create(Element element) {
        return switch (element) {
            case FIRE -> new FireRayVisual();
            case FROST -> new FrostRayVisual();
            case LIGHTNING -> new LightningRayVisual();
            case VOID -> new VoidRayVisual();
            case PHYSICAL -> new PhysicalRayVisual();
            case ARCANE -> new ArcaneRayVisual();
            case LIFE -> new LifeRayVisual();
        };
    }
}
