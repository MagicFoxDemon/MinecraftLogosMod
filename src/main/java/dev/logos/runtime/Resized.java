package dev.logos.runtime;

import dev.logos.LogosMod;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * The effect behind 缩放: while it lasts it multiplies the vanilla {@code scale} attribute, so the
 * effect's own clock, saving and syncing do the rest. The amplifier is the size on a log scale,
 * {@link #STEPS} per doubling and {@link #ONE} meaning unchanged.
 */
public final class Resized extends MobEffect {
    public static final int STEPS = 16;
    public static final int ONE = 128;
    /** Vanilla's scale bounds: 1/16 to 16. */
    public static final double MIN = 1.0 / 16, MAX = 16;

    public Resized() {
        super(MobEffectCategory.NEUTRAL, 0x9B7BD8);
        addAttributeModifier(Attributes.SCALE, Identifier.fromNamespaceAndPath(LogosMod.MODID, "resized"),
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, amplifier -> scaleOf(amplifier) - 1);
    }

    /** The size an amplifier stands for. */
    public static double scaleOf(int amplifier) {
        return Math.pow(2, (amplifier - ONE) / (double) STEPS);
    }

    /** The amplifier nearest to {@code scale}, clamped to vanilla's bounds. */
    public static int amplifierFor(double scale) {
        double s = Math.clamp(scale, MIN, MAX);
        return ONE + (int) Math.round(Math.log(s) / Math.log(2) * STEPS);
    }
}
