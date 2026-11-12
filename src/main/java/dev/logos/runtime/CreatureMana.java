package dev.logos.runtime;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A mana pool a compat keeps for another mod's creature (a maid): what it holds and the cap the compat last
 * worked out for it. Saved with the creature; {@link ManaVessels} hands it to spells, infusion and potions.
 */
public final class CreatureMana implements ManaVessel {
    public static final MapCodec<CreatureMana> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.DOUBLE.fieldOf("mana").forGetter(m -> m.mana),
            Codec.DOUBLE.fieldOf("max_mana").forGetter(m -> m.max)
    ).apply(i, CreatureMana::new));

    private double mana, max;

    public CreatureMana() {
        this(0, 0);
    }

    private CreatureMana(double mana, double max) {
        this.max = Math.max(0, max);
        this.mana = Math.max(0, Math.min(mana, this.max));
    }

    @Override
    public double mana() {
        return mana;
    }

    @Override
    public double maxMana() {
        return max;
    }

    /** A new cap; what it holds past it is lost. */
    public void setMax(double max) {
        this.max = Math.max(0, max);
        if (mana > this.max) mana = this.max;
    }

    public void setMana(double mana) {
        this.mana = Math.max(0, Math.min(mana, max));
    }

    @Override
    public double receiveMana(double amount) {
        double took = Math.max(0, Math.min(amount, max - mana));
        mana += took;
        return took;
    }

    /** Takes out up to {@code amount}; returns what it took. */
    public double take(double amount) {
        double took = Math.max(0, Math.min(amount, mana));
        mana -= took;
        return took;
    }
}
