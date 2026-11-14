package dev.logos.runtime;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 魔力成长: what a player's mana cap gains on top of its permanent base, from how far the player has come,
 * so the cap keeps pace with vanilla progress and with a modpack's bigger numbers alike. Each term is a
 * transient {@code logos:max_mana} modifier, refreshed every tick (written only when its whole number
 * changes); its weight and scale are in {@link LogosConfig}'s {@code growth} section.
 * <ul>
 * <li>修为: experience ever gained, which spending and dying never take back ({@link #trackXp}).
 * <li>体魄: max health above 20, so in vanilla it only grows with what raises max health.
 * <li>护甲, 韧性: the armor attributes; past a full set the gain slows to a logarithm.
 * <li>修行: mana ever drawn from the pool, slowly.
 * </ul>
 * This also refills the pool each tick, and drains a surplus left when the cap falls under the pool.
 */
public final class ManaGrowth {
    /** One growth term: the id of its modifier and its name in {@code /logos mana}. */
    public enum Term {
        XP("xp", "修为（累计经验）"),
        HEALTH("health", "体魄（生命上限）"),
        ARMOR("armor", "护甲"),
        TOUGHNESS("toughness", "盔甲韧性"),
        PRACTICE("practice", "修行（累计耗魔）");

        public final Identifier id;
        public final String zh;

        Term(String id, String zh) {
            this.id = Identifier.fromNamespaceAndPath(LogosMod.MODID, "growth_" + id);
            this.zh = zh;
        }
    }

    private ManaGrowth() {}

    /** One player tick, after everything else that tick moved the pool. */
    public static void tick(ServerPlayer p) {
        PlayerMagic m = PlayerMagic.of(p);
        trackXp(p, m);
        if (!Double.isNaN(m.manaSeen) && m.mana < m.manaSeen) m.manaSpent += m.manaSeen - m.mana;
        update(p);
        double cap = PlayerMagic.maxMana(p);
        if (m.mana < cap) {
            m.mana = Math.min(cap, m.mana + SpellModifiers.regenerationPerTick(p));
        } else if (m.mana > cap) {
            double share = LogosConfig.growth(LogosConfig.OVERFLOW_DRAIN);
            if (share > 0) m.mana = Math.max(cap, m.mana - Math.max(1, (m.mana - cap) * share) / 20);
        }
        m.manaSeen = m.mana;
    }

    /** What {@code t} adds to {@code p}'s cap as things stand. */
    public static double value(Player p, Term t) {
        PlayerMagic m = PlayerMagic.of(p);
        return switch (t) {
            case XP -> logarithmic(m.lifetimeXp, LogosConfig.GROWTH_XP_WEIGHT, LogosConfig.GROWTH_XP_SCALE);
            case HEALTH -> soft(p.getMaxHealth() - 20, LogosConfig.GROWTH_HEALTH_WEIGHT, LogosConfig.GROWTH_HEALTH_SCALE);
            case ARMOR -> soft(p.getAttributeValue(Attributes.ARMOR), LogosConfig.GROWTH_ARMOR_WEIGHT, LogosConfig.GROWTH_ARMOR_SCALE);
            case TOUGHNESS -> soft(p.getAttributeValue(Attributes.ARMOR_TOUGHNESS), LogosConfig.GROWTH_TOUGHNESS_WEIGHT,
                    LogosConfig.GROWTH_TOUGHNESS_SCALE);
            case PRACTICE -> logarithmic(m.manaSpent, LogosConfig.GROWTH_PRACTICE_WEIGHT, LogosConfig.GROWTH_PRACTICE_SCALE);
        };
    }

    /** Puts every term's value, as a whole number, on {@code p}'s cap. */
    static void update(Player p) {
        AttributeInstance cap = p.getAttribute(SpellModifiers.MAX_MANA);
        if (cap == null) return;
        for (Term t : Term.values()) {
            double v = Math.round(value(p, t));
            AttributeModifier old = cap.getModifier(t.id);
            if (old != null ? old.amount() == v : v == 0) continue;
            if (v == 0) cap.removeModifier(t.id);
            else cap.addOrUpdateTransientModifier(new AttributeModifier(t.id, v, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    /**
     * Adds what {@code p} gained in experience since last look to {@link PlayerMagic#lifetimeXp}. Looking
     * at the points held, not at events, catches every source (orbs, quests, other mods setting levels); a
     * drop (enchanting, an anvil, a death) is not counted back. The first look counts what is already held.
     */
    static void trackXp(Player p, PlayerMagic m) {
        double now = points(p);
        if (m.xpSeen < 0) m.lifetimeXp = Math.max(m.lifetimeXp, now);
        else if (now > m.xpSeen) m.lifetimeXp += now - m.xpSeen;
        m.xpSeen = now;
    }

    /** Experience points {@code p} holds: what the level took plus the progress into the next one. */
    public static double points(Player p) {
        return levelPoints(p.experienceLevel) + p.experienceProgress * p.getXpNeededForNextLevel();
    }

    /** Points it takes to reach {@code level} from 0, by {@link Player#getXpNeededForNextLevel}'s steps. */
    public static double levelPoints(int level) {
        double l = Math.max(0, level);
        if (l <= 16) return l * l + 6 * l;
        if (l <= 31) return 2.5 * l * l - 40.5 * l + 360;
        return 4.5 * l * l - 162.5 * l + 2220;
    }

    /** weight × log2(1 + amount / scale). */
    static double logarithmic(double amount, ModConfigSpec.DoubleValue weight, ModConfigSpec.DoubleValue scale) {
        double s = Math.max(1e-9, LogosConfig.growth(scale));
        return LogosConfig.growth(weight) * Math.log1p(Math.max(0, amount) / s) / Math.log(2);
    }

    /** weight × r for r = value / scale up to 1, then weight × (1 + ln r). */
    static double soft(double value, ModConfigSpec.DoubleValue weight, ModConfigSpec.DoubleValue scale) {
        double r = value / Math.max(1e-9, LogosConfig.growth(scale));
        if (!(r > 0)) return 0;
        return LogosConfig.growth(weight) * (r <= 1 ? r : 1 + Math.log(r));
    }
}
