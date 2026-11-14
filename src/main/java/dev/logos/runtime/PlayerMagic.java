package dev.logos.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.logos.LogosMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.player.Player;

/**
 * Persistent per-player magic state: mana pool and stability capacity. Passive spells live on wand pages.
 * The cap the pool fills to is {@link #maxMana(Player)}: {@link #baseMaxMana} plus what {@link ManaGrowth}
 * and equipment add.
 */
public final class PlayerMagic {
    public static final MapCodec<PlayerMagic> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.DOUBLE.fieldOf("mana").forGetter(PlayerMagic::savedMana),
            Codec.DOUBLE.fieldOf("max_mana").forGetter(m -> m.baseMaxMana),
            Codec.INT.fieldOf("stability").forGetter(m -> m.stability),
            Codec.BOOL.fieldOf("trace").forGetter(m -> m.trace),
            Codec.DOUBLE.optionalFieldOf("lifetime_xp", 0.0).forGetter(m -> m.lifetimeXp),
            Codec.DOUBLE.optionalFieldOf("xp_seen", -1.0).forGetter(m -> m.xpSeen),
            Codec.DOUBLE.optionalFieldOf("mana_spent", 0.0).forGetter(m -> m.manaSpent)
    ).apply(i, PlayerMagic::new));

    public double mana;
    /**
     * The permanent part of the cap (200 to start; 根源权限 and later potions raise it for good), saved with
     * the player. It is the base of the {@code logos:max_mana} attribute; read the whole cap with {@link #maxMana}.
     */
    public double baseMaxMana;
    public int stability;
    public boolean trace;
    /** Experience points ever gained, never lowered by spending or dying (see {@link ManaGrowth}). */
    public double lifetimeXp;
    /** The experience points the player held when last looked at; −1 before the first look. */
    public double xpSeen;
    /** Mana ever drawn out of the pool, for practice (see {@link ManaGrowth}). */
    public double manaSpent;
    /** Not persisted: the pool as last tick left it, to tell what was drawn since; NaN until then. */
    public double manaSeen = Double.NaN;
    /** Not persisted: passive spell name → game tick when it may fire again. */
    public final Map<String, Long> cooldowns = new HashMap<>();
    /** Not persisted: what the client last heard its mana HUD shows, spell included (see {@link ManaHud}). */
    public dev.logos.net.@org.jspecify.annotations.Nullable ManaHudPayload hudSent;
    /** Not persisted: the wand cast being charged, if any (see {@link Charging}). */
    public Charging.@org.jspecify.annotations.Nullable Charge charge;
    /** Not persisted: the staff being filled, if any (see {@link StaffCharging}). */
    public StaffCharging.@org.jspecify.annotations.Nullable Fill fill;
    /** Not persisted: the wand listening for a spell's name, if any (see {@link VoiceCasting}). */
    public VoiceCasting.@org.jspecify.annotations.Nullable Listen listen;
    /** Not persisted: the page whose spell a staff with a chant charm keeps casting after it was named aloud. */
    public net.minecraft.world.item.@org.jspecify.annotations.Nullable ItemStack chanting;

    public PlayerMagic() {
        this(Limits.DEFAULT_MAX_MANA, Limits.DEFAULT_MAX_MANA, Limits.DEFAULT_STABILITY, true, 0, -1, 0);
    }

    private PlayerMagic(double mana, double maxMana, int stability, boolean trace, double lifetimeXp, double xpSeen, double manaSpent) {
        this.mana = mana;
        this.baseMaxMana = maxMana;
        this.stability = stability;
        this.trace = trace;
        this.lifetimeXp = lifetimeXp;
        this.xpSeen = xpSeen;
        this.manaSpent = manaSpent;
    }

    /** The pool as saved: mana on its way into a staff is not on the item yet, so a crash before it gets there must find it here. */
    private double savedMana() {
        return fill == null ? mana : mana + fill.pending;
    }

    public static PlayerMagic of(Player p) {
        return p.getData(LogosMod.PLAYER_MAGIC);
    }

    /**
     * The cap {@code p}'s pool fills to: the {@code logos:max_mana} attribute, whose base is kept equal to
     * {@link #baseMaxMana} here, so other mods raise it with attribute modifiers, never by setting its base.
     */
    public static double maxMana(Player p) {
        PlayerMagic m = of(p);
        AttributeInstance cap = p.getAttribute(SpellModifiers.MAX_MANA);
        if (cap == null) return m.baseMaxMana;
        if (cap.getBaseValue() != m.baseMaxMana) cap.setBaseValue(m.baseMaxMana);
        return cap.getValue();
    }

    /** Room left below the cap; 0 when the pool is at or above it. */
    public static double room(Player p) {
        return Math.max(0, maxMana(p) - of(p).mana);
    }

    /**
     * Gives {@code p}'s pool up to {@code amount}, never past the cap; returns what it took. A pool already
     * above the cap (the cap fell under it) keeps its surplus and takes nothing: {@link ManaGrowth} drains it.
     */
    public static double refill(Player p, double amount) {
        double took = Math.min(Math.max(0, amount), room(p));
        of(p).mana += took;
        return took;
    }

    /** Shows an action-bar alert. */
    public static void alert(ServerPlayer p, Component message) {
        p.sendOverlayMessage(message);
    }
}
