package dev.logos.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.lang.Element;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;

/**
 * One wand charge (or a lifeform's cast being charged) as an element draws it: gathering at the cast
 * point, held there once full, then let go into its spell or fizzling out. {@link ChargeVisuals} makes
 * one per charge and calls {@link #tick} every client tick, from the first update heard until it returns
 * false after the charge has ended, and {@link #submit} every frame. Everything about the charge (tier,
 * phase, progress, where the head and the caster are) is read from {@link LiveCharge}. Until an element
 * is upgraded it reports {@link #legacy()} and {@link ChargeEffects} draws the old particle charge instead.
 */
public interface ChargeVisual {
    /** True until the element draws its own charge. */
    default boolean legacy() { return true; }

    /**
     * Each client tick (not while the game is paused). {@link LiveCharge#entered()} is true on the first
     * tick of each phase. Returning false while it is still charging or held is ignored; once released or
     * fizzled, false drops it (and so does {@link ChargeVisuals#LINGER_CAP}).
     */
    default boolean tick(Level level, LiveCharge charge) { return false; }

    /** Each frame; {@code out} already goes through {@link ShaderPackOverlay}. */
    default void submit(Level level, LiveCharge charge, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {}

    /** Dropped (it finished, its caster went away, the level went away, or it outlived its cap): stop anything still playing. */
    default void end() {}

    static ChargeVisual create(Element element) {
        return switch (element) {
            case FIRE -> new FireChargeVisual();
            case FROST -> new FrostChargeVisual();
            case LIGHTNING -> new LightningChargeVisual();
            case VOID -> new VoidChargeVisual();
            case PHYSICAL -> new PhysicalChargeVisual();
            case ARCANE -> new ArcaneChargeVisual();
            case LIFE -> new LifeChargeVisual();
        };
    }
}
