package dev.logos.gametest;

import java.util.List;

import dev.logos.LogosMod;
import dev.logos.item.SpellPages;
import dev.logos.lang.LogosError;
import dev.logos.lang.Status;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Effects;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.StatusData;
import dev.logos.runtime.TimeStop;
import dev.logos.runtime.Vfx;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** In-world tests for passives carried without selecting them, disarm and potion effects. */
final class RuneGameTests {
    private RuneGameTests() {}

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

    private static <T extends Mob> T mob(GameTestHelper h, EntityType<T> type) {
        T e = h.spawn(type, new Vec3(4.5, 2, 3.5));
        e.setNoAi(true);
        return e;
    }

    /** A codex held on its own (no wand) fires its passive spells, without selecting them. */
    static void codexPassiveWithoutWand(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var husk = mob(h, EntityType.HUSK);
        ItemStack codex = new ItemStack(LogosMod.SPELL_CODEX.get());
        codex.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                SpellPages.inscribed("(spell :name \"被选中\" :mana 10 (mark caster #cast))", null),
                SpellPages.inscribed("(spell :name \"攻\" :trigger attack :mana 10 (mark target #hit))", null))));
        p.setItemInHand(InteractionHand.MAIN_HAND, codex);
        husk.hurtServer(h.getLevel(), husk.damageSources().playerAttack(p), 1);
        h.succeedWhen(() -> h.assertTrue(StatusData.stacks(husk, new Status("#hit")) == 1,
                "the attack passive in a codex held without a wand did not run exactly once"));
    }

    /** Disarm takes the main hand first, then the off hand, then armour from the head down, skipping cursed armour. */
    static void disarmOrder(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var zombie = mob(h, EntityType.ZOMBIE);
        zombie.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        zombie.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        ItemStack cursed = new ItemStack(Items.IRON_HELMET);
        cursed.enchant(h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.BINDING_CURSE), 1);
        zombie.setItemSlot(EquipmentSlot.HEAD, cursed);
        zombie.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        String disarm = "(spell :mana 20 (disarm (nearest :radius 6)))";

        Casting.cast(p, Casting.compile(disarm), null, null);
        h.assertTrue(zombie.getMainHandItem().isEmpty() && !zombie.getOffhandItem().isEmpty(), "first the main hand");
        Casting.cast(p, Casting.compile(disarm), null, null);
        h.assertTrue(zombie.getOffhandItem().isEmpty(), "then the off hand");
        Casting.cast(p, Casting.compile(disarm), null, null);
        h.assertTrue(!zombie.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "a helmet with Curse of Binding stays on");
        h.assertTrue(zombie.getItemBySlot(EquipmentSlot.FEET).isEmpty(), "so the boots go next");
        var dropped = h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, zombie.getBoundingBox().inflate(3))
                .stream().map(e -> e.getItem().getItem()).toList();
        h.assertTrue(dropped.containsAll(List.of(Items.IRON_SWORD, Items.SHIELD, Items.IRON_BOOTS)), "expected the three items on the ground, got " + dropped);
        // Nothing left to take (only the cursed helmet): disarm charges nothing; only nearest's 1 is spent.
        double before = PlayerMagic.of(p).mana;
        var inst = Casting.cast(p, Casting.compile("(spell :mana 20 (disarm (nearest :radius 6)))"), null, null);
        h.assertTrue(inst != null && inst.mana == 19, "disarming nothing should cost nothing, left " + (inst == null ? "-" : inst.mana));
        h.assertTrue(PlayerMagic.of(p).mana == before - 20, "the injection is still paid");
        h.succeed();
    }

    /**
     * damage-all pays 3× the single price up to radius 5, then × (r / 5)^1.5 for the radius of the
     * selector that gathered its group — through a variable too; a radius past 64 searches and costs as 64.
     */
    static void groupRadiusCost(GameTestHelper h) {
        ServerPlayer p = caster(h);
        PlayerMagic.of(p).maxMana = PlayerMagic.of(p).mana = 1e6;
        mob(h, EntityType.HUSK);
        // enemies 2, then 1 physical point (0.8) × 3 × max(1, (r/5)^1.5).
        java.util.function.DoubleUnaryOperator expected = r -> 2 + 0.8 * 3 * Math.max(1, Math.pow(r / 5, 1.5));
        java.util.function.BiConsumer<String, Double> check = (targets, r) -> {
            var inst = Casting.cast(p, Casting.compile("(spell :mana 200 " + targets.replace("$G", "(damage-all $g 1)") + ")"), null, null);
            h.assertTrue(inst != null && !inst.dead, "cast failed: " + targets + (inst == null ? "" : " — " + inst.endReason()));
            double spent = inst.injected - inst.mana;
            h.assertTrue(Math.abs(spent - expected.applyAsDouble(r)) < 1e-6, targets + " should cost " + expected.applyAsDouble(r) + ", cost " + spent);
        };
        check.accept("(let $g (enemies :radius 3) $G)", 3.0);
        check.accept("(let $g (enemies) $G)", 5.0);
        check.accept("(let $g (enemies :radius 20) $G)", 20.0);
        check.accept("(let $g (enemies :radius 200) $G)", 64.0);
        h.succeed();
    }

    /** potion applies any registered effect by id, at the given level and duration; unknown ids do not compile. */
    static void potionEffects(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var husk = mob(h, EntityType.HUSK);
        var inst = Casting.cast(p, Casting.compile("(spell :mana 40 (potion (nearest :radius 6) slowness 10 2))"), null, null);
        var slow = husk.getEffect(MobEffects.SLOWNESS);
        h.assertTrue(slow != null && slow.getAmplifier() == 1 && slow.getDuration() == 200, "expected Slowness II for 10s, got " + slow);
        h.assertTrue(inst != null && Math.abs(inst.mana - (40 - 1 - 10 * 2 * 0.8)) < 1e-6, "expected 1 (nearest) + 16 (10s × level 2 × 0.8) spent, left " + (inst == null ? "-" : inst.mana));
        Casting.cast(p, Casting.compile("(spell :mana 40 (potion caster minecraft:night_vision 30))"), null, null);
        h.assertTrue(p.hasEffect(MobEffects.NIGHT_VISION), "the caster should have night vision");
        try {
            Casting.compile("(potion caster logos:no_such_effect 5)");
            h.fail("an unknown effect id should not compile");
        } catch (LogosError expected) {
            h.assertTrue(expected.getMessage().contains("no_such_effect"), "the error should name the id: " + expected.getMessage());
        }
        h.succeed();
    }

    /** A spell shield is marked (for the ward sphere) while it holds, even inside existing headroom, and unmarked once broken. */
    static void shieldMarksWard(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var husk = mob(h, EntityType.HUSK);
        husk.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 600, 3));
        Casting.cast(p, Casting.compile("(spell :mana 40 (shield (nearest :radius 6) 2))"), null, null);
        h.assertTrue(Effects.shielded(husk), "a shield inside absorption headroom should still be marked");
        husk.hurtServer(h.getLevel(), husk.damageSources().generic(), 100);
        h.assertTrue(husk.getAbsorptionAmount() <= 0 && !Effects.shielded(husk), "a broken shield should be unmarked");
        h.succeed();
    }

    /** Potion bursts read vanilla effects by category; neutral or unregistered (another mod's) ones read neutral. */
    static void potionBurstPolarity(GameTestHelper h) {
        h.assertTrue(Vfx.potion(MobEffects.SPEED) == Vfx.Burst.POTION_BENEFICIAL, "speed should read beneficial");
        h.assertTrue(Vfx.potion(MobEffects.POISON) == Vfx.Burst.POTION_HARMFUL, "poison should read harmful");
        h.assertTrue(Vfx.potion(MobEffects.GLOWING) == Vfx.Burst.POTION_NEUTRAL, "glowing should read neutral");
        Holder<MobEffect> foreign = Holder.direct(new MobEffect(MobEffectCategory.BENEFICIAL, 0) {});
        h.assertTrue(Vfx.potion(foreign) == Vfx.Burst.POTION_NEUTRAL, "an effect outside vanilla should read neutral");
        h.succeed();
    }

    /** A stopped mob hangs where it is and takes no damage; when time resumes the kept blows all land, and it falls. */
    static void timeStopMob(GameTestHelper h) {
        ServerPlayer p = caster(h);
        FamiliarGameTests.forceAround(h, 1);
        var husk = h.spawn(EntityType.HUSK, new Vec3(4.5, 5, 3.5));
        var inst = Casting.cast(p, Casting.compile("(spell :mana 60 (time-stop (nearest :radius 8) 2))"), null, null);
        h.assertTrue(inst != null && !inst.dead && TimeStop.stopped(husk), "the husk should be stopped");
        h.assertTrue(Math.abs(inst.injected - inst.mana - 9) < 1e-6, "nearest 1 + 2 s × 4 should cost 9, cost " + (inst.injected - inst.mana));
        double y = husk.getY();
        float full = husk.getHealth();
        husk.hurtServer(h.getLevel(), husk.damageSources().magic(), 3);
        husk.hurtServer(h.getLevel(), husk.damageSources().magic(), 2);
        h.assertTrue(husk.getHealth() == full && TimeStop.stored(husk) == 5, "both blows should be kept, kept " + TimeStop.stored(husk));
        h.runAfterDelay(20, () -> h.assertTrue(TimeStop.stopped(husk) && husk.getY() == y, "a stopped husk should hang still, moved to " + husk.getY()));
        // Both land, the second not turned away by the first one's invulnerability.
        h.succeedWhen(() -> h.assertTrue(!TimeStop.stopped(husk) && husk.getHealth() == full - 5 && husk.getY() < y,
                "once time resumes, the husk should take 5 and fall; health " + husk.getHealth() + ", y " + husk.getY()));
    }

    /** A stopped player cannot move (speed, jump and gravity held at zero) or attack, and their kept damage lands at the end. */
    static void timeStopPlayer(GameTestHelper h) {
        ServerPlayer p = caster(h);
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        p.connection.markClientLoaded();
        FamiliarGameTests.forceAround(h, 1);
        var husk = mob(h, EntityType.HUSK);
        double speed = p.getAttributeValue(Attributes.MOVEMENT_SPEED);
        Casting.cast(p, Casting.compile("(spell :mana 60 (time-stop caster 1))"), null, null);
        h.assertTrue(TimeStop.stopped(p), "the caster should be stopped");
        for (var a : List.of(Attributes.MOVEMENT_SPEED, Attributes.JUMP_STRENGTH, Attributes.GRAVITY))
            h.assertTrue(p.getAttributeValue(a) == 0, a.getRegisteredName() + " should be held at 0, is " + p.getAttributeValue(a));
        h.assertTrue(net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.player.AttackEntityEvent(p, husk)).isCanceled(), "a stopped player's attack should be refused");
        float full = p.getHealth();
        p.hurtServer(h.getLevel(), p.damageSources().magic(), 4);
        h.assertTrue(p.getHealth() == full, "the blow should be kept, health " + p.getHealth() + ", kept " + TimeStop.stored(p));
        h.succeedWhen(() -> h.assertTrue(!TimeStop.stopped(p) && p.getAttributeValue(Attributes.MOVEMENT_SPEED) == speed && p.getHealth() == full - 4,
                "once time resumes, the player should move again and take 4; health " + p.getHealth()));
    }
}
