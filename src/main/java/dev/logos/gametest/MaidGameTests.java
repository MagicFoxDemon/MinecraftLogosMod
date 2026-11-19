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
import dev.logos.item.StaffItem;
import dev.logos.lang.Status;
import dev.logos.runtime.CreatureMana;
import dev.logos.runtime.Familiars;
import dev.logos.runtime.FreeManaEntity;
import dev.logos.runtime.ManaVessel;
import dev.logos.runtime.ManaVessels;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.StatusData;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The Touhou Little Maid compat: a tamed maid's mana, the 施法 work mode, and summoned maids. Registered (by name, from
 * {@link LogosGameTests}) and loaded only when the maid mod is installed: run with {@code -Ptlm}.
 */
final class MaidGameTests {
    private static final Identifier SPELLCASTING = Identifier.fromNamespaceAndPath(LogosMod.MODID, "spellcasting");

    private MaidGameTests() {}

    static List<String> register(DeferredRegister<Consumer<GameTestHelper>> functions) {
        functions.register("maid_mana_follows_owner", () -> MaidGameTests::manaFollowsOwner);
        functions.register("maid_casts_attack_page", () -> MaidGameTests::castsAttackPage);
        functions.register("maid_heals_owner", () -> MaidGameTests::healsOwner);
        functions.register("maid_self_heal_spares_owner", () -> MaidGameTests::selfHealSparesOwner);
        functions.register("maid_heals_herself", () -> MaidGameTests::healsHerself);
        functions.register("maid_empowers_fighting_owner", () -> MaidGameTests::empowersFightingOwner);
        functions.register("maid_takes_mana_fuel", () -> MaidGameTests::takesManaFuel);
        functions.register("maid_summoned_not_tamed", () -> MaidGameTests::summonedNotTamed);
        functions.register("maid_gathers_free_mana", () -> MaidGameTests::gathersFreeMana);
        functions.register("maid_staff_fills_then_casts", () -> MaidGameTests::staffFillsThenCasts);
        functions.register("maid_staff_tops_up_when_calm", () -> MaidGameTests::staffTopsUpWhenCalm);
        functions.register("maid_forages_free_mana", () -> MaidGameTests::foragesFreeMana);
        functions.register("maid_controls_close_hunter", () -> MaidGameTests::controlsCloseHunter);
        return List.of("maid_mana_follows_owner", "maid_casts_attack_page", "maid_heals_owner", "maid_self_heal_spares_owner", "maid_heals_herself",
                "maid_empowers_fighting_owner", "maid_takes_mana_fuel", "maid_summoned_not_tamed", "maid_gathers_free_mana", "maid_staff_fills_then_casts",
                "maid_staff_tops_up_when_calm", "maid_forages_free_mana", "maid_controls_close_hunter");
    }

    private static EntityMaid maid(GameTestHelper h, Vec3 at) {
        return h.spawn(InitEntities.MAID.get(), at);
    }

    /** A tamed maid in the 施法 mode, working around the clock, with a wand holding {@code pages}. */
    private static EntityMaid caster(GameTestHelper h, ServerPlayer owner, String... pages) {
        return caster(h, owner, LogosMod.WAND.get().getDefaultInstance(), new Vec3(3.5, 2, 3.5), pages);
    }

    /** The same, holding {@code wand} (a staff, say) with {@code pages}, standing at {@code at}. */
    private static EntityMaid caster(GameTestHelper h, ServerPlayer owner, ItemStack wand, Vec3 at, String... pages) {
        EntityMaid m = maid(h, at);
        m.tame(owner);
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

    /** Her owner hurt, she passes over a cheaper heal that reaches only its caster and casts the one that reaches her owner. */
    static void selfHealSparesOwner(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        FamiliarGameTests.forceAround(h, 1);
        EntityMaid m = caster(h, p, "(spell :name \"自愈\" :mana 10 (heal caster 1) (mark caster #mended))",
                "(spell :name \"回春\" :mana 20 (heal (master) 1) (mark (master) #healed))");
        p.getFoodData().setFoodLevel(10);
        p.setHealth(6);
        h.succeedWhen(() -> {
            h.assertTrue(!StatusData.has(m, Status.parse("#mended")), "she should not cast a heal that reaches only herself for her owner");
            h.assertTrue(StatusData.has(p, Status.parse("#healed")), "she should cast the heal that reaches her owner");
        });
    }

    /** Her owner in a fight, she casts her empowering page on them. */
    static void empowersFightingOwner(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        FamiliarGameTests.forceAround(h, 1);
        caster(h, p, "(spell :name \"借力\" :mana 40 (potion (master) strength 10) (mark (master) #lent))");
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(7.5, 2, 7.5));
        husk.setNoAi(true);
        p.setLastHurtMob(husk);
        h.succeedWhen(() -> h.assertTrue(StatusData.has(p, Status.parse("#lent")), "she should empower her owner in a fight"));
    }

    /** Badly hurt, she heals herself with a heal of her own before a group heal that takes her in. */
    static void healsHerself(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        FamiliarGameTests.forceAround(h, 1);
        EntityMaid m = caster(h, p, "(spell :name \"群愈\" :mana 20 (heal (entities :radius 3) 1) (mark caster #group))",
                "(spell :name \"自愈\" :mana 30 (heal caster 1) (mark caster #own))");
        m.setHealth(m.getMaxHealth() * 0.3f);
        h.succeedWhen(() -> {
            h.assertTrue(!StatusData.has(m, Status.parse("#group")), "she should heal herself with her own heal first, not the group one");
            h.assertTrue(StatusData.has(m, Status.parse("#own")), "she should heal herself");
        });
    }

    /** In the 施法 mode her owner refills her with mana fuel, one at a time. */
    static void takesManaFuel(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        EntityMaid m = caster(h, p);
        CreatureMana pool = (CreatureMana) ManaVessels.of(m);
        h.assertTrue(pool != null, "a tamed maid should hold mana");
        pool.setMana(0);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LAPIS_LAZULI, 3));
        p.interactOn(m, InteractionHand.MAIN_HAND, m.position());
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
        p.interactOn(summoned, InteractionHand.MAIN_HAND, summoned.position());
        h.assertFalse(summoned.isTame(), "a summoned maid should not be tamed");
        h.assertTrue(p.getMainHandItem().getCount() == 2, "the cake should not be eaten");
        EntityMaid wild = maid(h, new Vec3(6.5, 2, 6.5));
        p.interactOn(wild, InteractionHand.MAIN_HAND, wild.position());
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

    /** A staff in her hand starts empty: she fills it from her pool, then casts her attack page out of it at once. */
    static void staffFillsThenCasts(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        FamiliarGameTests.forceAround(h, 1);
        PlayerMagic.of(p).baseMaxMana = 1000;
        ItemStack staff = LogosMod.WOOD_STAFFS.getFirst().get().getDefaultInstance();
        EntityMaid m = caster(h, p, staff, new Vec3(3.5, 2, 3.5), "(spell :name \"灼\" :intent strike :mana 20 (damage (foe) 1) (mark (foe) #struck))");
        CreatureMana pool = (CreatureMana) ManaVessels.of(m);
        h.assertTrue(pool != null && StaffItem.stored(m.getMainHandItem()) == 0, "her staff should start empty");
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(7.5, 2, 7.5));
        husk.setNoAi(true);
        h.succeedWhen(() -> {
            h.assertTrue(StatusData.has(husk, Status.parse("#struck")), "she should fill her staff and cast at the husk (staff "
                    + StaffItem.stored(m.getMainHandItem()) + ", pool " + pool.mana() + ", doing " + doing(m) + ")");
            h.assertTrue(pool.mana() < pool.maxMana() - 20, "the staff's mana should come out of her pool: " + pool.mana());
        });
    }

    /** Nothing to fight, she tops her staff up from her pool. */
    static void staffTopsUpWhenCalm(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        FamiliarGameTests.forceAround(h, 1);
        PlayerMagic.of(p).baseMaxMana = 1000;
        ItemStack staff = LogosMod.WOOD_STAFFS.getFirst().get().getDefaultInstance();
        EntityMaid m = caster(h, p, staff, new Vec3(3.5, 2, 3.5), "(spell :name \"灼\" :intent strike :mana 20 (damage (foe) 1))");
        double capacity = ((StaffItem) staff.getItem()).capacity();
        h.succeedWhen(() -> h.assertTrue(StaffItem.stored(m.getMainHandItem()) >= capacity - 1e-6, "she should fill her staff to "
                + capacity + ": " + StaffItem.stored(m.getMainHandItem()) + ", doing " + doing(m)));
    }

    /** Free mana beyond its own pull (8 blocks) but within 12: she walks to it, and it flows into her pool. */
    static void foragesFreeMana(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        FamiliarGameTests.forceAround(h, 1);
        PlayerMagic.of(p).mana = PlayerMagic.maxMana(p); // a full owner draws none of it
        EntityMaid m = caster(h, p, LogosMod.WAND.get().getDefaultInstance(), new Vec3(7.5, 2, 7.5), "(spell :name \"灼\" :intent strike :mana 20 (damage (foe) 1))");
        CreatureMana pool = (CreatureMana) ManaVessels.of(m);
        pool.setMana(0);
        FreeManaEntity.release(h.getLevel(), h.absoluteVec(new Vec3(0.5, 2.5, 0.5)), 40);
        h.succeedWhen(() -> h.assertTrue(h.getLevel().getEntitiesOfClass(FreeManaEntity.class, m.getBoundingBox().inflate(16)).isEmpty(),
                "she should walk to the free mana and take it in (pool " + pool.mana() + ", doing " + doing(m) + ")"));
    }

    /**
     * Something hunting her at arm's length: she casts her control page at it rather than her attack. (The same holds for
     * one hunting her owner, but no mob takes a test's mock player, which counts as creative, for its target.)
     */
    static void controlsCloseHunter(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        FamiliarGameTests.forceAround(h, 1);
        EntityMaid m = caster(h, p, LogosMod.WAND.get().getDefaultInstance(), new Vec3(5.5, 2, 5.5),
                "(spell :name \"灼\" :intent strike :mana 20 (damage (foe) 1) (mark (foe) #struck))",
                "(spell :name \"缚\" :intent control :mana 10 (mark (foe) #held))");
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(5.5, 2, 3.5));
        husk.setNoAi(true);
        husk.setTarget(m);
        long[] first = {-1, -1};
        h.onEachTick(() -> {
            if (first[0] < 0 && StatusData.has(husk, Status.parse("#held"))) first[0] = h.getTick();
            if (first[1] < 0 && StatusData.has(husk, Status.parse("#struck"))) first[1] = h.getTick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(StatusData.has(husk, Status.parse("#held")), "she should cast her control page at the husk hunting her");
            h.assertFalse(StatusData.has(husk, Status.parse("#struck")), "her control page should come first (held at " + first[0] + ", struck at " + first[1] + ", husk target " + husk.getTarget() + ")");
        });
    }

    private static String doing(EntityMaid m) {
        String mind = dev.logos.compat.tlm.MaidMind.describe(m);
        return mind == null ? "no mind" : mind;
    }
}
