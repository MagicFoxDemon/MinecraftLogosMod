package dev.logos.gametest;

import java.util.List;

import dev.logos.LogosMod;
import dev.logos.item.SpellPages;
import dev.logos.lang.Element;
import dev.logos.lang.LogosError;
import dev.logos.lang.Status;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Effects;
import dev.logos.runtime.ManaWrap;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.StatusData;
import dev.logos.runtime.TimeStop;
import dev.logos.runtime.Vfx;
import dev.logos.runtime.WrapObj;
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

    /** Every slot is used in order, without requiring the item to be conventional armour. */
    static void equipOrder(GameTestHelper h) {
        ServerPlayer p = caster(h);
        p.getInventory().clearContent();
        p.getAbilities().instabuild = true;
        var zombie = mob(h, EntityType.ZOMBIE);
        EquipmentSlot[] slots = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
                EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot slot : slots) zombie.setItemSlot(slot, ItemStack.EMPTY);
        ItemStack material = new ItemStack(Items.STONE, 7);
        var name = net.minecraft.network.chat.Component.literal("授装试验");
        material.set(DataComponents.CUSTOM_NAME, name);
        p.getInventory().setItem(10, material);
        for (int i = 0; i < slots.length; i++) {
            var inst = equip(p, zombie, "minecraft:stone", 20, false);
            h.assertTrue(!inst.dead && inst.mana == 12, "successful equip costs exactly eight mana");
            h.assertTrue(material.getCount() == 6 - i, "one real item is consumed even in creative mode");
            for (int j = 0; j < slots.length; j++) {
                ItemStack worn = zombie.getItemBySlot(slots[j]);
                h.assertTrue(j <= i ? worn.is(Items.STONE) && worn.getCount() == 1 && name.equals(worn.get(DataComponents.CUSTOM_NAME))
                        : worn.isEmpty(), "equip must fill only the first empty slot: " + slots[j]);
            }
        }
        var full = equip(p, zombie, "minecraft:stone", 20, false);
        h.assertTrue(!full.dead && full.mana == 20 && material.getCount() == 1, "full equipment consumes neither item nor mana");
        h.succeed();
    }

    /** Failure is side-effect free; an actual transferred sword keeps durability and enchantments. */
    static void equipMaterials(GameTestHelper h) {
        ServerPlayer p = caster(h);
        p.getInventory().clearContent();
        var zombie = mob(h, EntityType.ZOMBIE);
        zombie.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        var missing = equip(p, zombie, "minecraft:stone", 20, false);
        h.assertTrue(!missing.dead && missing.mana == 20 && zombie.getMainHandItem().isEmpty(), "missing item must not create equipment or charge mana");
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.setDamageValue(17);
        sword.enchant(h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.UNBREAKING), 2);
        var enchantments = sword.getEnchantments();
        p.getInventory().setItem(12, sword);
        var poor = equip(p, zombie, "minecraft:iron_sword", 7, false);
        h.assertTrue(poor.dead && sword.getCount() == 1 && zombie.getMainHandItem().isEmpty(), "mana failure must not consume or equip the item");
        var success = equip(p, zombie, "minecraft:iron_sword", 20, false);
        ItemStack worn = zombie.getMainHandItem();
        h.assertTrue(!success.dead && p.getInventory().getItem(12).isEmpty(), "successful equip consumes the source sword");
        h.assertTrue(worn.getDamageValue() == 17 && worn.getEnchantments().equals(enchantments), "transfer must retain durability and enchantments");
        // Non-player casters have no material inventory and must not fabricate the requested item.
        zombie.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        var nonPlayer = dev.logos.runtime.Casting.castAs(zombie,
                dev.logos.lang.Compiler.compile("(spell :mana 20 (equip caster &minecraft:stone))"), 20, 0, null, null);
        h.assertTrue(!nonPlayer.dead && nonPlayer.mana == 20 && zombie.getMainHandItem().isEmpty(), "non-player casting cannot create materials");
        h.succeed();
    }

    /** Groups pay per real item, and other players remain protected by the spell's players flag. */
    static void equipGroupsAndPlayers(GameTestHelper h) {
        ServerPlayer p = caster(h);
        p.getInventory().clearContent();
        var zombie = mob(h, EntityType.ZOMBIE);
        var husk = mob(h, EntityType.HUSK);
        zombie.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        husk.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        p.getInventory().setItem(10, new ItemStack(Items.STONE));
        var group = Casting.castAs(p, dev.logos.lang.Compiler.compile("(spell :mana 100 (equip (enemies :radius 6) &minecraft:stone))"), 100, 0, null, null);
        int equipped = (zombie.getMainHandItem().is(Items.STONE) ? 1 : 0) + (husk.getMainHandItem().is(Items.STONE) ? 1 : 0);
        h.assertTrue(equipped == 1 && group.mana == 90 && p.getInventory().getItem(10).isEmpty(), "one material equips exactly one group member; selector costs two, equip eight");
        @SuppressWarnings("removal")
        ServerPlayer other = h.makeMockServerPlayerInLevel();
        other.getInventory().clearContent();
        p.getInventory().setItem(10, new ItemStack(Items.STONE));
        var protectedPlayer = equip(p, other, "minecraft:stone", 20, false);
        h.assertTrue(protectedPlayer.mana == 20 && other.getMainHandItem().isEmpty() && p.getInventory().getItem(10).getCount() == 1,
                "players=false protects other players without consuming materials");
        var allowed = equip(p, other, "minecraft:stone", 20, true);
        h.assertTrue(allowed.mana == 12 && other.getMainHandItem().is(Items.STONE) && p.getInventory().getItem(10).isEmpty(),
                "players=true allows an inventory-backed transfer");
        h.succeed();
    }

    private static dev.logos.runtime.SpellInstance equip(ServerPlayer p, net.minecraft.world.entity.LivingEntity target, String item, double mana, boolean players) {
        return Casting.castAs(p, dev.logos.lang.Compiler.compile("(spell :trigger attack :mana 20 :players " + players + " (equip target &" + item + "))"),
                mana, 0, target, null);
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
        // Off the ground a player moves at a fixed air speed, whatever their speed attribute says: the step itself must not run.
        Vec3 at = p.position();
        p.setOnGround(false);
        p.travel(new Vec3(0, 0, 1));
        h.assertTrue(p.position().distanceTo(at) < 1e-9, "a stopped player should not walk, even in the air; moved " + p.position().distanceTo(at));
        float full = p.getHealth();
        p.hurtServer(h.getLevel(), p.damageSources().magic(), 4);
        h.assertTrue(p.getHealth() == full, "the blow should be kept, health " + p.getHealth() + ", kept " + TimeStop.stored(p));
        h.succeedWhen(() -> h.assertTrue(!TimeStop.stopped(p) && p.getAttributeValue(Attributes.MOVEMENT_SPEED) == speed && p.getHealth() == full - 4,
                "once time resumes, the player should move again and take 4; health " + p.getHealth()));
    }

    /** imbue winds a spell about the target's hand: its blows set off on-hit (self is the holder), whatever it holds; it costs 6 to wind. */
    static void imbueMobBlows(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var wrapped = mob(h, EntityType.HUSK);
        var plain = h.spawn(EntityType.HUSK, new Vec3(1.5, 2, 6.5));
        plain.setNoAi(true);
        var hit = h.spawn(EntityType.PIG, new Vec3(4.5, 2, 6.5));
        var control = h.spawn(EntityType.PIG, new Vec3(1.5, 2, 8.5));
        for (var m : List.of(wrapped, plain)) m.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        for (var m : List.of(hit, control)) m.setNoAi(true);

        var inst = Casting.cast(p, Casting.compile(
                "(spell :mana 30 (imbue (nearest :radius 2.5) :life 10 (on-hit (damage target 3 arcane) (mark self #wielder))))"), null, null);
        h.assertTrue(inst != null && Math.abs(inst.mana - (30 - 1 - WrapObj.DEPLOY)) < 1e-6, "imbue should cost " + WrapObj.DEPLOY + ", left " + (inst == null ? "-" : inst.mana));
        ManaWrap w = ManaWrap.of(wrapped);
        h.assertTrue(w != null && w.amount() == 0 && w.element() == Element.ARCANE && ManaWrap.of(plain) == null, "only the nearest husk is wrapped, in arcane");
        // Swapping what the hand holds keeps the wrap: it is on the hand.
        wrapped.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
        plain.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
        float hit0 = hit.getHealth(), control0 = control.getHealth();
        wrapped.doHurtTarget(h.getLevel(), hit);
        plain.doHurtTarget(h.getLevel(), control);
        h.succeedWhen(() -> {
            float extra = (hit0 - hit.getHealth()) - (control0 - control.getHealth());
            h.assertTrue(Math.abs(extra - 3) < 1e-3, "the wrapped blow should deal 3 more, dealt " + extra + " more");
            h.assertTrue(StatusData.stacks(wrapped, new Status("#wielder")) == 1, "self should be the wrapped husk");
        });
    }

    /** A player's blow counts only charged to 90%, and only on the entity aimed at, not on one the sweep catches; left unwired, the hand wrapped is the caster's own. */
    static void imbuePlayerBlow(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var aimed = mob(h, EntityType.HUSK);
        var swept = h.spawn(EntityType.HUSK, new Vec3(2.5, 2, 3.5));
        swept.setNoAi(true);
        Casting.cast(p, Casting.compile("(spell :mana 50 (imbue :life 30 (on-hit (mark target #hit))))"), null, null);
        h.assertTrue(WrapObj.on(p) != null, "the caster's hand should be wrapped");
        // A blow straight after another is weak: it does not count.
        p.resetAttackStrengthTicker();
        blow(h, p, aimed);
        h.runAfterDelay(2, () -> {
            h.assertTrue(StatusData.stacks(aimed, new Status("#hit")) == 0, "a weak blow should not set off on-hit");
            charged(p);
            blow(h, p, aimed);
            swept.hurtServer(h.getLevel(), swept.damageSources().playerAttack(p), 1);
            h.succeedWhen(() -> {
                h.assertTrue(StatusData.stacks(aimed, new Status("#hit")) == 1, "a charged blow should set off on-hit");
                h.assertTrue(StatusData.stacks(swept, new Status("#hit")) == 0, "the swept husk should not");
            });
        });
    }

    /** As a player's attack lands: what they aimed at, then the blow. */
    private static void blow(GameTestHelper h, ServerPlayer p, net.minecraft.world.entity.LivingEntity t) {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.player.AttackEntityEvent(p, t));
        t.invulnerableTime = 0;
        t.hurtServer(h.getLevel(), t.damageSources().playerAttack(p), 1);
    }

    /** Fully charges {@code p}'s next blow (the mock player is not ticked, so its attack timer never runs). */
    private static void charged(ServerPlayer p) {
        try {
            var f = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("attackStrengthTicker");
            f.setAccessible(true);
            f.setInt(p, 1000);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Arrows a wrapped hand looses set off on-hit (a bow's only fully drawn), and so do thrown tridents. */
    static void imbueArrows(GameTestHelper h) {
        FamiliarGameTests.forceAround(h, 1);
        ServerPlayer p = caster(h);
        var full = h.spawn(EntityType.HUSK, new Vec3(2.5, 2, 6.5));
        var weak = h.spawn(EntityType.HUSK, new Vec3(4.5, 2, 6.5));
        var speared = h.spawn(EntityType.HUSK, new Vec3(6.5, 2, 6.5));
        for (var m : List.of(full, weak, speared)) m.setNoAi(true);
        Casting.cast(p, Casting.compile("(spell :mana 50 (imbue caster :life 30 (on-hit (mark target #hit))))"), null, null);
        var level = h.getLevel();
        var crit = new net.minecraft.world.entity.projectile.arrow.Arrow(level, p, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        crit.setCritArrow(true);
        var limp = new net.minecraft.world.entity.projectile.arrow.Arrow(level, p, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        var trident = new net.minecraft.world.entity.projectile.arrow.ThrownTrident(level, p, new ItemStack(Items.TRIDENT));
        loose(h, crit, full);
        loose(h, limp, weak);
        loose(h, trident, speared);
        h.succeedWhen(() -> {
            h.assertTrue(StatusData.stacks(full, new Status("#hit")) == 1, "a full-draw arrow should set off on-hit");
            h.assertTrue(StatusData.stacks(speared, new Status("#hit")) == 1, "a thrown trident should set off on-hit");
            h.assertTrue(weak.getHealth() < weak.getMaxHealth() && StatusData.stacks(weak, new Status("#hit")) == 0,
                    "a half-drawn arrow should hurt without setting off on-hit");
        });
    }

    /** Sends {@code arrow} from just in front of {@code t}, straight at its middle. */
    private static void loose(GameTestHelper h, net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow, net.minecraft.world.entity.LivingEntity t) {
        Vec3 at = t.position().add(0, t.getBbHeight() / 2, -2.5);
        arrow.setPos(at);
        arrow.shoot(0, 0, 1, 1.5f, 0);
        h.getLevel().addFreshEntity(arrow);
    }

    /** A mob's blow is its swing: on-swing then on-hit; a blow that kills sets off on-kill. A player's swings are held to the hand's pace. */
    static void imbueSwingAndKill(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var husk = mob(h, EntityType.HUSK);
        var pig = h.spawn(EntityType.PIG, new Vec3(4.5, 2, 6.5));
        pig.setNoAi(true);
        pig.setHealth(1);
        Casting.cast(p, Casting.compile("(spell :mana 50 (imbue (nearest :radius 2.5) :life 30"
                + " (on-swing (mark self #swung)) (on-kill (mark self #killed))))"), null, null);
        h.assertTrue(WrapObj.on(husk) != null, "the husk should be wrapped");
        husk.doHurtTarget(h.getLevel(), pig);
        // The player's own wrap: each counted swing hurts the nearest enemy (the husk) by 1; a second straight after does not count.
        Casting.cast(p, Casting.compile("(spell :mana 50 (imbue caster :life 30 (on-swing (damage (nearest :radius 3) 1 arcane))))"), null, null);
        float husk0 = husk.getHealth();
        WrapObj own = WrapObj.on(p);
        h.assertTrue(own != null, "the player's hand should be wrapped");
        own.swingOf(p);
        own.swingOf(p);
        h.succeedWhen(() -> {
            h.assertTrue(StatusData.stacks(husk, new Status("#swung")) == 1, "the husk's blow should set off on-swing");
            h.assertTrue(!pig.isAlive() && StatusData.stacks(husk, new Status("#killed")) == 1, "the killing blow should set off on-kill");
            h.assertTrue(Math.abs(husk0 - husk.getHealth() - 1) < 1e-3, "two swings at once should count once, dealt " + (husk0 - husk.getHealth()));
        });
    }

    /** It is kept up like a field: when the spell's mana runs dry the wrap comes off. */
    static void imbueUpkeep(GameTestHelper h) {
        FamiliarGameTests.forceAround(h, 1);
        ServerPlayer p = caster(h);
        var husk = mob(h, EntityType.HUSK);
        // 1 for nearest and 6 to wind leaves 1.5: three pulses of 0.5, then the fourth runs dry.
        Casting.cast(p, Casting.compile("(spell :mana 8.5 (imbue (nearest :radius 2.5) :life 30))"), null, null);
        h.assertTrue(ManaWrap.of(husk) != null, "the wrap should be on");
        h.succeedWhen(() -> h.assertTrue(ManaWrap.of(husk) == null && WrapObj.on(husk) == null, "the wrap should come off once the mana runs dry"));
    }

    /** Life can't be written in a spell, but a wrap given through the API (an addon's creatures) works like any other. */
    static void imbueLifeThroughApi(GameTestHelper h) {
        var wrapped = mob(h, EntityType.HUSK);
        var plain = h.spawn(EntityType.HUSK, new Vec3(1.5, 2, 6.5));
        plain.setNoAi(true);
        var hit = h.spawn(EntityType.PIG, new Vec3(4.5, 2, 6.5));
        var control = h.spawn(EntityType.PIG, new Vec3(1.5, 2, 8.5));
        for (var m : List.of(hit, control)) m.setNoAi(true);
        for (var m : List.of(wrapped, plain)) m.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        ManaWrap.wrap(wrapped, Element.LIFE, 2, 30);
        h.assertTrue(ManaWrap.of(wrapped) != null && ManaWrap.of(wrapped).element() == Element.LIFE, "the life wrap should be on");
        float hit0 = hit.getHealth(), control0 = control.getHealth();
        wrapped.doHurtTarget(h.getLevel(), hit);
        plain.doHurtTarget(h.getLevel(), control);
        h.succeedWhen(() -> {
            float extra = (hit0 - hit.getHealth()) - (control0 - control.getHealth());
            h.assertTrue(Math.abs(extra - 2) < 1e-3, "the life-wrapped blow should deal 2 more, dealt " + extra + " more");
        });
    }

    /** A wrap comes off its holder when its time is up, running on-expire; a new one replaces the old, and a plain one replaces a spell's. */
    static void imbueRunsOut(GameTestHelper h) {
        FamiliarGameTests.forceAround(h, 1);
        ServerPlayer p = caster(h);
        var husk = mob(h, EntityType.HUSK);
        Casting.cast(p, Casting.compile("(spell :mana 20 (imbue (nearest :radius 2.5) :life 30 (on-expire (mark self #replaced))))"), null, null);
        h.assertTrue(WrapObj.on(husk) != null, "the spell wrap should be on");
        ManaWrap.wrap(husk, Element.FIRE, 2, 30);
        h.assertTrue(WrapObj.on(husk) == null && ManaWrap.of(husk).amount() == 2 && StatusData.stacks(husk, new Status("#replaced")) == 1,
                "a plain wrap should replace the spell's, which runs on-expire");
        Casting.cast(p, Casting.compile("(spell :mana 20 (imbue (nearest :radius 2.5) :life 0.1 (on-expire (mark self #ended))))"), null, null);
        h.assertTrue(ManaWrap.of(husk) != null && ManaWrap.of(husk).amount() == 0, "a spell wrap should replace the plain one");
        h.succeedWhen(() -> h.assertTrue(!husk.hasData(LogosMod.MANA_WRAP) && StatusData.stacks(husk, new Status("#ended")) == 1,
                "the wrap should come off after 2 ticks and run on-expire"));
    }
}
