package dev.logos.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.item.PageContainer;
import dev.logos.item.SpellPages;
import dev.logos.lang.Status;
import dev.logos.runtime.CreatureMana;
import dev.logos.runtime.Familiars;
import dev.logos.runtime.FreeManaEntity;
import dev.logos.runtime.ManaVessel;
import dev.logos.runtime.ManaVessels;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.StatusData;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The Touhou Little Maid compat: a tamed maid's mana, the 施法 work mode, and summoned maids. Registered (by name, from
 * {@link LogosGameTests}) and loaded only when the maid mod is installed: run with {@code -Ptlm}.
 */
final class MaidGameTests {
    private static final ResourceLocation SPELLCASTING = ResourceLocation.fromNamespaceAndPath(LogosMod.MODID, "spellcasting");

    private MaidGameTests() {}

    static List<String> register(TestFunctions functions) {
        functions.register("maid_mana_follows_owner", () -> MaidGameTests::manaFollowsOwner);
        functions.register("maid_casts_attack_page", () -> MaidGameTests::castsAttackPage);
        functions.register("maid_heals_owner", () -> MaidGameTests::healsOwner);
        functions.register("maid_takes_mana_fuel", () -> MaidGameTests::takesManaFuel);
        functions.register("maid_summoned_not_tamed", () -> MaidGameTests::summonedNotTamed);
        functions.register("maid_gathers_free_mana", () -> MaidGameTests::gathersFreeMana);
        return List.of("maid_mana_follows_owner", "maid_casts_attack_page", "maid_heals_owner", "maid_takes_mana_fuel", "maid_summoned_not_tamed",
                "maid_gathers_free_mana");
    }

    private static EntityMaid maid(GameTestHelper h, Vec3 at) {
        return h.spawn(InitEntities.MAID.get(), at);
    }

    /** A tamed maid in the 施法 mode, working around the clock, with a wand holding {@code pages}. */
    private static EntityMaid caster(GameTestHelper h, ServerPlayer owner, String... pages) {
        EntityMaid m = maid(h, new Vec3(3.5, 2, 3.5));
        m.tame(owner);
        ItemStack wand = LogosMod.WAND.get().getDefaultInstance();
        for (int i = 0; i < pages.length; i++) PageContainer.set(wand, i, SpellPages.inscribed(pages[i], null));
        m.setItemSlot(EquipmentSlot.MAINHAND, wand);
        m.setSchedule(MaidSchedule.ALL);
        m.setTask(TaskManager.findTask(SPELLCASTING).orElseThrow());
        return m;
    }

    /** A wild maid holds no mana; a tamed one holds her owner's cap, more by favorability, and starts full. */
    static void manaFollowsOwner(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        FamiliarGameTests.forceAround(h, 1);
        PlayerMagic.of(p).baseMaxMana = 1000;
        EntityMaid wild = maid(h, new Vec3(6.5, 2, 6.5));
        h.assertTrue(ManaVessels.of(wild) == null, "a wild maid should hold no mana");
        EntityMaid m = maid(h, new Vec3(3.5, 2, 3.5));
        m.tame(p);
        ManaVessel pool = ManaVessels.of(m);
        h.assertTrue(pool != null && pool.maxMana() == 1000 && pool.mana() == 1000, "a tamed maid should start full at her owner's cap: " + pool);
        m.setFavorability(m.getFavorabilityManager().getPointByLevel(2));
        double wanted = 1000 * (1 + LogosConfig.maidManaPerFavorLevel() * 2);
        h.succeedWhen(() -> h.assertTrue(Math.abs(pool.maxMana() - wanted) < 1e-6, "favorability level 2 should raise her cap to " + wanted + ": " + pool.maxMana()));
    }

    /** In the 施法 mode she takes on a hostile mob and casts her attack page at it. */
    static void castsAttackPage(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        FamiliarGameTests.forceAround(h, 1);
        EntityMaid m = caster(h, p, "(spell :name \"灼\" :intent strike :mana 20 (damage (foe) 1) (mark (foe) #struck))");
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(7.5, 2, 7.5));
        husk.setNoAi(true);
        h.succeedWhen(() -> h.assertTrue(StatusData.has(husk, Status.parse("#struck")), "she should cast her attack page at the husk (target "
                + m.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).map(e -> e.getType().toShortString()).orElse("none")
                + ", sees " + m.getBrain().getMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES).map(List::size).orElse(-1)
                + ", activity " + m.getBrain().getActiveNonCoreActivity().map(Object::toString).orElse("none")
                + ", mana " + ManaVessels.of(m).mana() + ", husk hp " + husk.getHealth() + ")"));
    }

    /** Her owner hurt, she casts her HEAL page; (master) in it is her owner. */
    static void healsOwner(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        FamiliarGameTests.forceAround(h, 1);
        caster(h, p, "(spell :name \"回春\" :intent heal :mana 10 (mark (master) #healed))");
        p.getFoodData().setFoodLevel(10); // too hungry to heal by itself
        p.setHealth(6);
        h.succeedWhen(() -> h.assertTrue(StatusData.has(p, Status.parse("#healed")), "she should cast her heal page on her owner"));
    }

    /** In the 施法 mode her owner refills her with mana fuel, one at a time. */
    static void takesManaFuel(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        EntityMaid m = caster(h, p);
        CreatureMana pool = (CreatureMana) ManaVessels.of(m);
        h.assertTrue(pool != null, "a tamed maid should hold mana");
        pool.setMana(0);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LAPIS_LAZULI, 3));
        p.interactOn(m, InteractionHand.MAIN_HAND);
        h.assertTrue(Math.abs(pool.mana() - 50) < 1e-6, "lapis should give her 50 mana: " + pool.mana());
        h.assertTrue(p.getMainHandItem().getCount() == 2, "one lapis should be used");
        h.succeed();
    }

    /** A maid summoned as a familiar refuses the cake that tames a wild maid; one that is not a familiar takes it. */
    static void summonedNotTamed(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        EntityMaid summoned = maid(h, new Vec3(3.5, 2, 3.5));
        Familiars.bindForTest(summoned, p);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CAKE, 2));
        p.interactOn(summoned, InteractionHand.MAIN_HAND);
        h.assertFalse(summoned.isTame(), "a summoned maid should not be tamed");
        h.assertTrue(p.getMainHandItem().getCount() == 2, "the cake should not be eaten");
        EntityMaid wild = maid(h, new Vec3(6.5, 2, 6.5));
        p.interactOn(wild, InteractionHand.MAIN_HAND);
        h.assertTrue(wild.isTame() && wild.isOwnedBy(p), "a wild maid that is not a familiar should still be tamed by cake");
        h.succeed();
    }

    /**
     * Free mana drifts to a tamed maid with room in her pool and goes in at the default absorption (half). Her own
     * regeneration fills it too meanwhile, so what shows it was her is the free mana being used up.
     */
    static void gathersFreeMana(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        FamiliarGameTests.forceAround(h, 1);
        PlayerMagic.of(p).mana = PlayerMagic.maxMana(p); // a full owner draws none of it
        EntityMaid m = maid(h, new Vec3(3.5, 2, 3.5));
        m.tame(p);
        m.setNoAi(true);
        CreatureMana pool = (CreatureMana) ManaVessels.of(m);
        h.assertTrue(pool != null && pool.maxMana() >= 40, "a tamed maid should hold mana: " + pool);
        pool.setMana(0);
        FreeManaEntity.release(h.getLevel(), h.absoluteVec(new Vec3(7.5, 2.5, 7.5)), 40);
        h.succeedWhen(() -> {
            h.assertTrue(pool.mana() >= 20 - 1e-6, "she should gather the free mana, half of it kept: " + pool.mana());
            h.assertTrue(h.getLevel().getEntitiesOfClass(FreeManaEntity.class, m.getBoundingBox().inflate(12)).isEmpty(), "the free mana should be used up: " + h.getLevel().getEntitiesOfClass(FreeManaEntity.class, m.getBoundingBox().inflate(12)).stream().map(o -> o.amount() + " at " + o.distanceTo(m)).toList());
        });
    }
}
