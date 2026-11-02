package dev.logos.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.lang.Element;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;

/**
 * One head of a vein ({@code vein}) as an element draws it, from the moment clients first hear of it until it has
 * played out after stopping. {@link VeinVisuals} makes one per head and calls {@link #tick} every client tick until it
 * returns false after the head stopped, and {@link #submit} every frame. Everything about the head is read from
 * {@link LiveVein}. Every element starts on {@link TintedVeinVisual}, the shared look in its own colours.
 * <p>
 * A vein that lingers ({@code :linger}) is a different thing to see, and every element gets a look for each: without it
 * something that passes over the ground (a sword's qi skimming along it), with it something the ground keeps (spikes
 * thrust up out of it). The shared lingering look is {@link TintedLingeringVeinVisual}. A lingering head is kept until what
 * it crawled over has faded ({@link LiveVein#linger} ticks after it was crawled over), or until the vein is gone.
 */
public interface VeinVisual {
    /**
     * Each client tick (not while the game is paused). While the head crawls the return value is ignored; once it has
     * stopped, false drops it (and so does {@link VeinVisuals#LINGER_CAP}).
     */
    default boolean tick(Level level, LiveVein vein) { return false; }

    /** Each frame; {@code out} already goes through {@link ShaderPackOverlay}. */
    default void submit(Level level, LiveVein vein, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {}

    /** Dropped (it finished, the level went away, or it outlived its cap): stop anything still playing. */
    default void end() {}

    /** The look of a head of {@code element}: of one that leaves what it crawled over behind if it {@code lingers}. */
    static VeinVisual create(Element element, boolean lingers) {
        return lingers ? new TintedLingeringVeinVisual(element) : new TintedVeinVisual(element);
    }
}
