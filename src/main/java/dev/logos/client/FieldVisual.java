package dev.logos.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.lang.Element;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;

/**
 * One field ({@code field}) as an element draws it, from the moment clients first hear of it until it has
 * faded after ending. {@link FieldVisuals} makes one per field and calls {@link #tick} every client tick
 * until it returns false after the field ended, and {@link #submit} every frame. Everything about the field
 * (where it is and where its ground is, its radius, how far through its life it is, its pulses and who
 * entered it) is read from {@link LiveField}. Until an element is upgraded it reports {@link #legacy()} and
 * the old particle ring and pulse are played instead.
 */
public interface FieldVisual {
    /** True until the element draws its own field. */
    default boolean legacy() { return true; }

    /**
     * Each client tick (not while the game is paused). While the field lasts the return value is ignored;
     * once it has ended, false drops it (and so does {@link FieldVisuals#LINGER_CAP}).
     */
    default boolean tick(Level level, LiveField field) { return false; }

    /** Each frame; {@code out} already goes through {@link ShaderPackOverlay}. */
    default void submit(Level level, LiveField field, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {}

    /** Dropped (it finished, the level went away, or it outlived its cap): stop anything still playing. */
    default void end() {}

    static FieldVisual create(Element element) {
        return switch (element) {
            case FIRE -> new FireFieldVisual();
            case FROST -> new FrostFieldVisual();
            case LIGHTNING -> new LightningFieldVisual();
            case VOID -> new VoidFieldVisual();
            case PHYSICAL -> new PhysicalFieldVisual();
            case ARCANE -> new ArcaneFieldVisual();
            case LIFE -> new LifeFieldVisual();
        };
    }
}
