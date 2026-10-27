package dev.logos.gametest;

import java.util.List;

import dev.logos.LogosMod;
import dev.logos.item.SpellPages;
import dev.logos.lang.Category;
import dev.logos.lang.Element;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Charging;
import dev.logos.runtime.Effects;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellInstance;
import dev.logos.runtime.SpellModifiers;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** In-world tests for the spell attributes buffs feed: damage, resistance, rune cost, charge speed and mana. */
final class SpellModifierGameTests {
    private static final Identifier BUFF = Identifier.fromNamespaceAndPath(LogosMod.MODID, "test_buff");

    private SpellModifierGameTests() {}

    private static ServerPlayer caster(GameTestHelper h) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) h.setBlock(x, 1, z, Blocks.STONE);
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 pos = h.absoluteVec(new Vec3(4.5, 2, 1.5));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0);
        PlayerMagic m = PlayerMagic.of(p);
        m.maxMana = m.mana = 1000;
        m.trace = false;
        return p;
    }

    /** A cow (10 health, no armour) standing still two blocks ahead of the caster. */
    private static Cow cow(GameTestHelper h) {
        Cow c = h.spawn(EntityType.COW, new Vec3(4.5, 2, 3.5));
        c.setNoAi(true);
        return c;
    }

    private static void buff(LivingEntity e, Holder<Attribute> attribute, double amount) {
        e.getAttribute(attribute).addPermanentModifier(new AttributeModifier(BUFF, amount, AttributeModifier.Operation.ADD_VALUE));
    }

    private static SpellInstance cast(ServerPlayer p, String body) {
        return Casting.cast(p, Casting.compile("(spell :mana 50 " + body + ")"), null, null);
    }

    private static void near(GameTestHelper h, double actual, double expected, String what) {
        h.assertTrue(Math.abs(actual - expected) < 1e-4, what + ": expected " + expected + ", got " + actual);
    }

    /** Fire power ×2 doubles fire damage and leaves physical alone; the rune still pays for the damage written. */
    static void elementPowerBoostsItsElement(GameTestHelper h) {
        ServerPlayer p = caster(h);
        Cow c = cow(h);
        buff(p, SpellModifiers.powerAttribute(Element.FIRE), 1);
        SpellInstance fire = cast(p, "(damage (nearest :radius 6) 2 fire)");
        near(h, c.getHealth(), 6, "2 fire at ×2");
        near(h, fire.mana, 50 - 1 - 1.4, "mana left after nearest (1) and 2 fire (1.4)");
        cast(p, "(damage (nearest :radius 6) 2)");
        near(h, c.getHealth(), 4, "2 physical, unboosted");
        buff(p, SpellModifiers.SPELL_POWER, 0.5);
        cast(p, "(damage (nearest :radius 6) 2)");
        near(h, c.getHealth(), 1, "2 physical at overall ×1.5");
        h.succeed();
    }

    /** Overall and frost resistance of ½ each let a quarter of frost through, and all of fire. */
    static void resistanceTurnsAsideItsElement(GameTestHelper h) {
        ServerPlayer p = caster(h);
        Cow c = cow(h);
        buff(c, SpellModifiers.resistanceAttribute(Element.FROST), 0.5);
        buff(c, SpellModifiers.SPELL_RESISTANCE, 0.5);
        cast(p, "(damage (nearest :radius 6) 4 frost)");
        near(h, c.getHealth(), 9, "4 frost, a quarter taken");
        cast(p, "(damage (nearest :radius 6) 4 fire)");
        near(h, c.getHealth(), 7, "4 fire, half taken");
        h.succeed();
    }

    /** Fire is vanilla fire: armour turns part of it aside and fire resistance all of it, while frost (magic) goes through armour. */
    static void fireIsVanillaFire(GameTestHelper h) {
        ServerPlayer p = caster(h);
        Cow c = cow(h);
        buff(c, Attributes.ARMOR, 20);
        cast(p, "(damage (nearest :radius 6) 4 frost)");
        near(h, c.getHealth(), 6, "4 frost through armour");
        cast(p, "(damage (nearest :radius 6) 4 fire)");
        h.assertTrue(c.getHealth() > 2.5f && c.getHealth() < 5.5f, "armour should turn aside most of 4 fire; health " + c.getHealth());
        float before = c.getHealth();
        c.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 200));
        cast(p, "(damage (nearest :radius 6) 4 fire)");
        near(h, c.getHealth(), before, "fire resistance stops fire");
        h.succeed();
    }

    /** Arcane is a writable damage element with its own power, at 1 mana a point. */
    static void arcaneDamageHasItsOwnPower(GameTestHelper h) {
        ServerPlayer p = caster(h);
        Cow c = cow(h);
        buff(p, SpellModifiers.powerAttribute(Element.ARCANE), 1);
        SpellInstance arcane = cast(p, "(damage (nearest :radius 6) 2 arcane)");
        near(h, c.getHealth(), 6, "2 arcane at ×2");
        near(h, arcane.mana, 50 - 1 - 2, "mana left after nearest (1) and 2 arcane (2)");
        h.succeed();
    }

    /**
     * A creature deals life damage through the entry other mods use: its life power and the target's life
     * resistance apply, and vanilla's hurt cooldown turns aside an equal second hit.
     */
    static void creatureLifeDamage(GameTestHelper h) {
        Cow attacker = h.spawn(EntityType.COW, new Vec3(4.5, 2, 1.5));
        attacker.setNoAi(true);
        Cow c = cow(h);
        buff(attacker, SpellModifiers.powerAttribute(Element.LIFE), 1);
        buff(c, SpellModifiers.resistanceAttribute(Element.LIFE), 0.5);
        Effects.damage(attacker, c, 4, Element.LIFE);
        near(h, c.getHealth(), 6, "4 life at ×2, half taken");
        Effects.damage(attacker, c, 4, Element.LIFE);
        near(h, c.getHealth(), 6, "an equal hit during the hurt cooldown");
        h.succeed();
    }

    /** Heal power ×2 doubles healing, drain's healing of the caster and shields; the runes still pay for the amount written. */
    static void healPowerScalesHealing(GameTestHelper h) {
        ServerPlayer p = caster(h);
        Cow c = cow(h);
        buff(p, SpellModifiers.HEAL_POWER, 1);
        c.setHealth(4);
        SpellInstance heal = cast(p, "(heal (nearest :radius 6) 2)");
        near(h, c.getHealth(), 8, "2 healing at ×2");
        near(h, heal.mana, 50 - 1 - 3, "mana left after nearest (1) and 2 healing (3)");
        cast(p, "(shield (nearest :radius 6) 1.5)");
        near(h, c.getAbsorptionAmount(), 3, "1.5 shield at ×2");
        p.setHealth(10);
        cast(p, "(drain (nearest :radius 6) 2)");
        near(h, p.getHealth(), 12, "drain heals half of 2 at ×2");
        h.succeed();
    }

    /**
     * Overall and void vulnerability of ½ each make void damage 2.25 times as much; physical vulnerability of 1
     * against physical resistance of ½ leaves physical damage as it was.
     */
    static void vulnerabilityAddsToDamageTaken(GameTestHelper h) {
        ServerPlayer p = caster(h);
        Cow c = cow(h);
        buff(c, SpellModifiers.SPELL_VULNERABILITY, 0.5);
        buff(c, SpellModifiers.vulnerabilityAttribute(Element.VOID), 0.5);
        cast(p, "(damage (nearest :radius 6) 2 void)");
        near(h, c.getHealth(), 10 - 4.5, "2 void at 1.5 × 1.5");
        c.getAttribute(SpellModifiers.SPELL_VULNERABILITY).removeModifier(BUFF);
        buff(c, SpellModifiers.vulnerabilityAttribute(Element.PHYSICAL), 1);
        buff(c, SpellModifiers.resistanceAttribute(Element.PHYSICAL), 0.5);
        cast(p, "(damage (nearest :radius 6) 2)");
        near(h, c.getHealth(), 10 - 4.5 - 2, "2 physical at ½ × 2");
        h.succeed();
    }

    /** Effect runes at ½ cost pay half; a selector (binding) still pays in full until the overall cost drops too. */
    static void nodeCostScalesItsCategory(GameTestHelper h) {
        ServerPlayer p = caster(h);
        cow(h);
        buff(p, SpellModifiers.costAttribute(Category.EFFECT), -0.5);
        near(h, cast(p, "(damage (nearest :radius 6) 1 fire)").mana, 50 - 1 - 0.35, "nearest 1, damage ½ of 0.7");
        buff(p, SpellModifiers.SPELL_COST, -0.5);
        near(h, cast(p, "(damage (nearest :radius 6) 1 fire)").mana, 50 - 0.5 - 0.175, "nearest ½, damage ¼ of 0.7");
        h.succeed();
    }

    /** Channel speed 2 fills the same charge in half the time. */
    static void channelSpeedHalvesCharge(GameTestHelper h) {
        ServerPlayer p = caster(h);
        ItemStack wand = new ItemStack(LogosMod.WOOD_WANDS.getFirst().get());
        wand.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                SpellPages.inscribed(dev.logos.item.PageTier.CRYSTAL, "(spell :name \"蓄\" :mana 100 (mark caster #charged))", null))));
        p.setItemInHand(InteractionHand.MAIN_HAND, wand);
        buff(p, SpellModifiers.CHANNEL_SPEED, 1);
        h.assertTrue(Charging.begin(p, wand, InteractionHand.MAIN_HAND), "charging did not start");
        // Oak wand: flow 40, surge 20 × (1 + 1000/1000); at speed 2 that is 80 and 160.
        near(h, Charging.seconds(100, 80, 160), Charging.seconds(100, 40, 40) / 2, "seconds at speed 2");
        int ticks = Charging.of(p).ticks;
        h.assertTrue(ticks == Charging.ticks(100, 80, 160), "charge should take " + Charging.ticks(100, 80, 160) + " ticks, not " + ticks);
        h.succeed();
    }

    /** Regeneration defaults to the old 0.3 a tick and follows its attribute; an instant restore stops at the pool's top. */
    static void manaRegenerationAndRestore(GameTestHelper h) {
        ServerPlayer p = caster(h);
        near(h, SpellModifiers.regenerationPerTick(p), 0.3, "default regeneration per tick");
        buff(p, SpellModifiers.MANA_REGENERATION, 14);
        near(h, SpellModifiers.regenerationPerTick(p), 1, "regeneration with +14 a second");
        PlayerMagic m = PlayerMagic.of(p);
        m.mana = 900;
        near(h, SpellModifiers.restoreMana(p, 250), 100, "restored");
        near(h, m.mana, 1000, "pool after restoring");
        h.succeed();
    }
}
