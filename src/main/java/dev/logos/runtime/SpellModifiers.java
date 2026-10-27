package dev.logos.runtime;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import dev.logos.lang.Category;
import dev.logos.lang.Element;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * What buffs change about spells, as attributes every living entity has. Potions, gear and commands
 * ({@code /attribute}) all feed them the vanilla way, through attribute modifiers; this class reads
 * them where spells deal damage, pay for runes and charge.
 *
 * <ul>
 * <li>{@code spell_power}, {@code <element>_spell_power}: multipliers (base 1) on the damage a spell deals;
 * they multiply each other. Damage, not cost: the rune still pays for the amount written.
 * <li>{@code spell_resistance}, {@code <element>_spell_resistance}: the share (0 to 1) of spell damage the
 * bearer does not take; they stack as {@code (1 − all) × (1 − element)}.
 * <li>{@code spell_vulnerability}, {@code <element>_spell_vulnerability}: how much more spell damage the bearer
 * takes (base 0; 0.5 is half as much again), kept apart from resistance so a debuff and a ward can both hold;
 * they multiply in as {@code (1 + all) × (1 + element)}.
 * <li>{@code spell_cost}, {@code <category>_node_cost}: multipliers (base 1, at least 0.1) on what each rune
 * of a spell pays; they multiply each other.
 * <li>{@code heal_power}: a multiplier (base 1) on the healing and shields a spell gives, drain's included;
 * like power, the rune still pays for the amount written.
 * <li>{@code channel_speed}: how much faster a charge fills (base 1; 2 takes half as long).
 * <li>{@code mana_regeneration}: mana per second a player's pool refills by.
 * </ul>
 *
 * A spell takes its caster's power and cost when it is cast ({@link #of}), so a buff that wears off
 * mid-spell does not change a field or projectile already out.
 */
public final class SpellModifiers {
    private static final List<DeferredHolder<Attribute, Attribute>> ALL = new ArrayList<>();
    private static final Map<Element, DeferredHolder<Attribute, Attribute>> POWER = new EnumMap<>(Element.class);
    private static final Map<Element, DeferredHolder<Attribute, Attribute>> RESISTANCE = new EnumMap<>(Element.class);
    private static final Map<Element, DeferredHolder<Attribute, Attribute>> VULNERABILITY = new EnumMap<>(Element.class);
    private static final Map<Category, DeferredHolder<Attribute, Attribute>> COST = new EnumMap<>(Category.class);

    public static final DeferredHolder<Attribute, Attribute> SPELL_POWER = attribute("spell_power", 1, 0, 64);
    public static final DeferredHolder<Attribute, Attribute> SPELL_RESISTANCE = attribute("spell_resistance", 0, 0, 1);
    public static final DeferredHolder<Attribute, Attribute> SPELL_VULNERABILITY = attribute("spell_vulnerability", 0, 0, 16);
    public static final DeferredHolder<Attribute, Attribute> SPELL_COST = attribute("spell_cost", 1, 0.1, 16);
    public static final DeferredHolder<Attribute, Attribute> HEAL_POWER = attribute("heal_power", 1, 0, 64);
    public static final DeferredHolder<Attribute, Attribute> CHANNEL_SPEED = attribute("channel_speed", 1, 0.1, 16);
    public static final DeferredHolder<Attribute, Attribute> MANA_REGENERATION =
            attribute("mana_regeneration", Limits.MANA_REGEN_PER_TICK * 20, 0, 1e6);

    static {
        for (Element e : Element.values()) {
            POWER.put(e, attribute(name(e) + "_spell_power", 1, 0, 64));
            RESISTANCE.put(e, attribute(name(e) + "_spell_resistance", 0, 0, 1));
            VULNERABILITY.put(e, attribute(name(e) + "_spell_vulnerability", 0, 0, 16));
        }
        for (Category c : Category.values()) COST.put(c, attribute(c.name().toLowerCase(Locale.ROOT) + "_node_cost", 1, 0.1, 16));
    }

    private final double[] damage = new double[Element.values().length];
    private final double[] cost = new double[Category.values().length];
    private final double baseCost;
    private final double heal;

    private SpellModifiers(LivingEntity caster) {
        double power = value(caster, SPELL_POWER);
        for (Element e : Element.values()) damage[e.ordinal()] = power * value(caster, POWER.get(e));
        baseCost = value(caster, SPELL_COST);
        for (Category c : Category.values()) cost[c.ordinal()] = baseCost * value(caster, COST.get(c));
        heal = value(caster, HEAL_POWER);
    }

    /** Called once while the mod is constructed, so the attributes are registered with the rest. */
    public static void init() {}

    /** Every living entity gets every spell attribute: any of them may cast, and any may be hit. */
    public static void addTo(EntityAttributeModificationEvent e) {
        for (var type : e.getTypes()) {
            for (var a : ALL) if (!e.has(type, a)) e.add(type, a);
        }
    }

    /** {@code caster}'s power and cost as they stand now; a spell keeps this for its whole life. */
    public static SpellModifiers of(LivingEntity caster) {
        return new SpellModifiers(caster);
    }

    /** The multiplier on damage of {@code element} the caster deals. */
    public double damage(Element element) {
        return damage[element.ordinal()];
    }

    /** The multiplier on the healing and shields the caster gives. */
    public double heal() {
        return heal;
    }

    /** The multiplier on what a rune of {@code category} costs; with no category, only the overall one. */
    public double cost(@Nullable Category category) {
        return category == null ? baseCost : cost[category.ordinal()];
    }

    /** The share of {@code element} spell damage that {@code target} takes: less by its resistances, more by its vulnerabilities. */
    public static double taken(LivingEntity target, Element element) {
        return (1 - value(target, SPELL_RESISTANCE)) * (1 - value(target, RESISTANCE.get(element)))
                * (1 + value(target, SPELL_VULNERABILITY)) * (1 + value(target, VULNERABILITY.get(element)));
    }

    /** How much faster {@code caster} charges: flow scales by it and surge by its square, so a charge takes 1/speed as long. */
    public static double channelSpeed(LivingEntity caster) {
        return value(caster, CHANNEL_SPEED);
    }

    /** Mana per tick a player's pool refills by. */
    public static double regenerationPerTick(ServerPlayer player) {
        return value(player, MANA_REGENERATION) / 20;
    }

    /**
     * Gives {@code entity} up to {@code amount} mana at once (an instant mana potion): a player's pool, or
     * a vessel's (a mana lifeform). Returns how much it took; anything else takes none.
     */
    public static double restoreMana(LivingEntity entity, double amount) {
        if (amount <= 0) return 0;
        if (entity instanceof ServerPlayer p) {
            PlayerMagic m = PlayerMagic.of(p);
            double took = Math.max(0, Math.min(amount, m.maxMana - m.mana));
            m.mana += took;
            return took;
        }
        return entity instanceof ManaVessel v ? v.receiveMana(amount) : 0;
    }

    /** The attribute raising (or lowering) the damage of {@code element}. */
    public static Holder<Attribute> powerAttribute(Element element) {
        return POWER.get(element);
    }

    /** The attribute turning aside spell damage of {@code element}. */
    public static Holder<Attribute> resistanceAttribute(Element element) {
        return RESISTANCE.get(element);
    }

    /** The attribute making its bearer take more spell damage of {@code element}. */
    public static Holder<Attribute> vulnerabilityAttribute(Element element) {
        return VULNERABILITY.get(element);
    }

    /** The attribute scaling what runes of {@code category} cost. */
    public static Holder<Attribute> costAttribute(Category category) {
        return COST.get(category);
    }

    private static double value(LivingEntity e, Holder<Attribute> attribute) {
        AttributeInstance i = e.getAttribute(attribute);
        return i != null ? i.getValue() : attribute.value().getDefaultValue();
    }

    private static String name(Element e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    private static DeferredHolder<Attribute, Attribute> attribute(String id, double base, double min, double max) {
        var holder = LogosMod.ATTRIBUTES.register(id, () -> new RangedAttribute("attribute.logos." + id, base, min, max).setSyncable(true));
        ALL.add(holder);
        return holder;
    }
}
