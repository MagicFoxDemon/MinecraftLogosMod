package dev.logos.gametest;

import java.util.List;

import dev.logos.LogosMod;
import dev.logos.item.SpellPages;
import dev.logos.lang.LogosError;
import dev.logos.lang.Status;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.StatusData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
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
}
