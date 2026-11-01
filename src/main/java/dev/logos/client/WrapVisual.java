package dev.logos.client;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.IntFunction;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.lang.Element;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.RandomSource;

/**
 * How a mana wrap (the imbue rune, {@code dev.logos.runtime.ManaWrap}) looks in one element: what clings to the
 * wrapped item or bare fist, and the particles it lets off into the world. {@link SurfaceAuras} feeds it the same way
 * as a surface aura. Every element has its own class, so each can be made in its own worktree (FORM_VFX.md); they all
 * start as {@link TintedWrapVisual}, the mana style in the element's colours. One instance per element, which keeps
 * that element's particles.
 */
abstract class WrapVisual {
    private static final Map<Element, WrapVisual> BY_ELEMENT = new EnumMap<>(Element.class);

    static WrapVisual of(Element element) {
        return BY_ELEMENT.computeIfAbsent(element, e -> switch (e) {
            case PHYSICAL -> new PhysicalWrapVisual();
            case FIRE -> new FireWrapVisual();
            case FROST -> new FrostWrapVisual();
            case LIGHTNING -> new LightningWrapVisual();
            case VOID -> new VoidWrapVisual();
            case ARCANE -> new ArcaneWrapVisual();
            case LIFE -> new LifeWrapVisual();
        });
    }

    /** The elements drawn so far. */
    static Collection<WrapVisual> all() { return BY_ELEMENT.values(); }

    /**
     * What clings to the surface, drawn in its model space (0–1 is one block, {@code view} the camera moved into it),
     * as {@link SurfaceAuraVisual#local} does. {@code t} is in ticks; {@code seed} tells one wrapped hand from another.
     */
    abstract void local(ItemSurface surface, VfxParts.View view, float t, long seed, PoseStack stack, SubmitNodeCollector collector);

    /** Once a tick for every wrapped item or fist in view: lets particles off its surface into the world. */
    abstract void emit(SurfaceAuras.Emitter e, RandomSource random);

    /** Once a tick: moves the particles on and drops the spent ones. */
    abstract void tick();

    /** Draws the particles, the pose at world origin; {@code layers} as {@link VfxParts#layered}. */
    abstract void world(VfxParts.View view, float pt, PoseStack stack, IntFunction<OrderedSubmitNodeCollector> layers);

    /** How many particles are in the world (they count towards the shared cap). */
    abstract int particles();

    abstract void clear();
}
