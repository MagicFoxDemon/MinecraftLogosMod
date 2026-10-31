package dev.logos.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.RandomSource;

/**
 * The wrap every element starts from: the surface aura's mana style (the flowing glow, three threads spiralling to
 * the tip, wisps with trails and now and then a rune) in two colours, {@code glow} for the glow and the wisps, the
 * brighter {@code core} for the solid thread lines and the wisps' centres.
 */
class TintedWrapVisual extends WrapVisual {
    private final int glow, core;
    private final List<SurfaceAuraVisual.Mote> motes = new ArrayList<>();

    TintedWrapVisual(int glow, int core) {
        this.glow = glow;
        this.core = core;
    }

    @Override
    void local(ItemSurface surface, VfxParts.View view, float t, long seed, PoseStack stack, SubmitNodeCollector collector) {
        SurfaceAuraVisual.mana(surface, view, t, stack, collector, glow, core);
    }

    @Override
    void emit(SurfaceAuras.Emitter e, RandomSource random) {
        SurfaceAuraVisual.manaMotes(e, random, motes, 0);
    }

    @Override
    void tick() {
        motes.removeIf(m -> !m.tick());
    }

    @Override
    void world(VfxParts.View view, float pt, PoseStack stack, IntFunction<OrderedSubmitNodeCollector> layers) {
        if (!motes.isEmpty()) SurfaceAuraVisual.manaWorld(motes, view, pt, stack, layers.apply(1), m -> glow, m -> core);
    }

    @Override
    int particles() { return motes.size(); }

    @Override
    void clear() { motes.clear(); }
}
