package dev.logos.gametest;

import java.util.List;

import dev.logos.LogosMod;
import dev.logos.item.SpellPages;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Familiars;
import dev.logos.runtime.ManaLifeform;
import dev.logos.runtime.SpellInstance;
import dev.logos.runtime.StatusData;
import dev.logos.lang.Status;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** The mana lifeform: summoning by level and mana, the per-player limit, its attack page, going dormant and being fed. */
final class LifeformGameTests {
    private LifeformGameTests() {}

    private static List<ManaLifeform> lifeforms(ServerPlayer p) {
        return Familiars.of(p).stream().filter(m -> m instanceof ManaLifeform).map(m -> (ManaLifeform) m).toList();
    }

    private static ManaLifeform summon(GameTestHelper h, ServerPlayer p, String src) {
        SpellInstance inst = Casting.cast(p, Casting.compile(src), null, null);
        h.assertTrue(inst != null && !inst.dead, "summon failed: " + (inst == null ? "refused" : inst.endReason()));
        ManaLifeform lf = (ManaLifeform) Familiars.latest(p, null);
        h.assertTrue(lf != null, "no lifeform after summoning");
        return lf;
    }

    private static ItemStack page(String src) {
        return SpellPages.write(new ItemStack(LogosMod.SPELL_PAGE.get()), src, null);
    }

    /**
     * Below 50 × level^1.5 the summoning fails; above, everything the spell has left (up to the pool)
     * becomes its mana, health is 30 + 20 × level; a second one past the limit of one unmakes the first.
     */
    static void summonRules(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        SpellInstance poor = Casting.cast(p, Casting.compile("(spell :mana 2000 (summon mana_lifeform :level 2 :mana 100 :at (pos-of caster)))"), null, null);
        h.assertTrue(poor != null && poor.dead && lifeforms(p).isEmpty(), "100 mana is below a level-2 lifeform's 141: the spell should fail");

        ManaLifeform first = summon(h, p, "(spell :mana 500 (summon mana_lifeform :level 2 :at (pos-of caster) :name \"灵\"))");
        h.assertTrue(first.lifeLevel() == 2, "level 2 expected, got " + first.lifeLevel());
        h.assertTrue(first.mana() > 400 && first.mana() <= 500, "it should take what the spell had left, got " + first.mana());
        h.assertTrue(first.getMaxHealth() == 70 && first.getHealth() == 70, "level 2: 30 + 20 × 2 = 70 health, got " + first.getMaxHealth());
        h.assertTrue(Math.abs(first.maxMana() - 1000 * Math.pow(2, 1.5)) < 1e-6, "its pool is 1000 × 2^1.5");

        ManaLifeform second = summon(h, p, "(spell :mana 200 (summon mana_lifeform :at (pos-of caster)))");
        h.assertTrue(first.isRemoved() && !second.isRemoved() && lifeforms(p).size() == 1, "with a limit of one, the new lifeform unmakes the old");
        h.assertTrue(second.lifeLevel() == 1, "no :level means level 1");
        h.succeed();
    }

    /** Ordered onto a husk, it casts its attack page at it, paying from its own pool; the core takes only the right pages. */
    static void castsAttackPage(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        FamiliarGameTests.forceAround(h, 1);
        ManaLifeform lf = summon(h, p, "(spell :mana 300 (summon mana_lifeform :at (pos-of caster)))");
        ItemStack attack = page("(spell :name \"魔弹\" :mana 20 (damage (foe) 4))");
        ItemStack passive = page("(spell :name \"护主\" :trigger hurt :mana 10 (heal (master) 2))");
        h.assertTrue(!lf.core.accepts(0, passive) && lf.core.accepts(0, attack), "the attack slot takes active pages only");
        h.assertTrue(!lf.core.accepts(1, passive), "a level-1 lifeform has no passive slot");
        lf.core.setItem(0, attack);
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(6.5, 2, 6.5));
        husk.setNoAi(true);
        double before = lf.mana();
        h.assertTrue(Familiars.order(lf, husk), "it should take the order");
        h.succeedWhen(() -> {
            h.assertTrue(husk.getHealth() < husk.getMaxHealth(), "its attack page should have hurt the husk");
            h.assertTrue(lf.mana() < before, "and the cast should be paid from its own mana");
        });
    }

    /** Run dry it goes dormant and falls; fed amethyst past its minimum (50 at level 1) it wakes. */
    static void dormantAndFed(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        FamiliarGameTests.forceAround(h, 1);
        ManaLifeform lf = summon(h, p, "(spell :mana 100 (summon mana_lifeform :at (pos-of caster)))");
        lf.setMana(0.01);
        p.setGameMode(GameType.SURVIVAL);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.AMETHYST_SHARD, 3));
        boolean[] fed = {false};
        h.succeedWhen(() -> {
            if (!fed[0]) {
                h.assertTrue(lf.isDormant(), "out of mana it should go dormant");
                h.assertTrue(!lf.isNoGravity(), "and fall");
                lf.interact(p, InteractionHand.MAIN_HAND, lf.position());
                h.assertTrue(lf.isDormant(), "25 mana is below its minimum of 50: still dormant");
                lf.interact(p, InteractionHand.MAIN_HAND, lf.position());
                h.assertTrue(!lf.isDormant() && lf.isNoGravity(), "fed to 50 it should wake and rise");
                h.assertTrue(p.getMainHandItem().getCount() == 1, "each feeding uses one shard");
                fed[0] = true;
            }
        });
    }

    /** Its mind keeps it in the air at its master's shoulder: set down on the ground across the room, it rises and comes over. */
    static void hoversAtMaster(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        FamiliarGameTests.forceAround(h, 1);
        ManaLifeform lf = summon(h, p, "(spell :mana 200 (summon mana_lifeform :at (pos-of caster)))");
        Vec3 start = h.absoluteVec(new Vec3(7.5, 2, 7.5));
        lf.snapTo(start.x, start.y, start.z, 0, 0);
        double floor = h.absoluteVec(new Vec3(0, 2, 0)).y;
        h.succeedWhen(() -> {
            h.assertTrue(lf.distanceTo(p) < 3.5, "it should come to its master, " + lf.distanceTo(p) + " away");
            h.assertTrue(lf.getY() - floor >= 0.8, "hovering at least a block up, not on the ground: " + (lf.getY() - floor));
        });
    }

    /** A spell it casts on itself takes hold: executing itself, it dies (no friendly fire to spare it from itself). */
    static void executesItself(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        ManaLifeform lf = summon(h, p, "(spell :mana 200 (summon mana_lifeform :at (pos-of caster)))");
        Casting.castAs(lf, Casting.compile("(spell :mana 1000 (execute caster))"), 1000, 0, null, null);
        h.assertTrue(!lf.isAlive(), "executing itself, it should die");
        h.succeed();
    }

    /** Run dry while flying across to its master, it drops to the ground and lies there, instead of hanging in the air. */
    static void fallsWhenDry(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        FamiliarGameTests.forceAround(h, 1);
        ManaLifeform lf = summon(h, p, "(spell :mana 200 (summon mana_lifeform :at (pos-of caster)))");
        Vec3 start = h.absoluteVec(new Vec3(7.5, 4, 7.5));
        lf.snapTo(start.x, start.y, start.z, 0, 0);
        double floor = h.absoluteVec(new Vec3(0, 2, 0)).y;
        int[] stage = {0};
        h.succeedWhen(() -> {
            if (stage[0] == 0) {
                // Once it is on its way (its flight control busy), its mana runs out.
                h.assertTrue(lf.getNavigation().isInProgress() || lf.getDeltaMovement().horizontalDistance() > 0.02, "not flying yet");
                lf.setMana(0.01);
                stage[0] = 1;
            }
            h.assertTrue(lf.isDormant(), "out of mana it should go dormant");
            h.assertTrue(lf.onGround() && lf.getY() - floor < 0.3, "it should lie on the ground, not hang " + (lf.getY() - floor) + " up");
            h.assertTrue(++stage[0] > 40, "and stay there");
            h.assertTrue(!lf.isNoGravity(), "still falling under its own weight");
        });
    }

    /** Two attack pages: the cheaper is its attack, and with a third of its pool to spare it looses the dearer one too. */
    static void comboWhenRich(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        FamiliarGameTests.forceAround(h, 1);
        ManaLifeform lf = summon(h, p, "(spell :mana 2000 (summon mana_lifeform :level 3 :at (pos-of caster)))");
        lf.core.setItem(0, page("(spell :name \"轻\" :intent strike :mana 5 (mark (foe) #weak))"));
        lf.core.setItem(1, page("(spell :name \"重\" :intent strike :mana 30 (mark (foe) #strong))"));
        h.assertTrue(lf.attackSlots().equals(List.of(0, 1)), "the cheaper page comes first: " + lf.attackSlots());
        lf.setMana(0.3 * lf.maxMana() + 20);
        h.assertTrue(!lf.comboAffordable(0, 1), "with less than a third to spare after both, no combo");
        lf.setMana(0.8 * lf.maxMana());
        h.assertTrue(lf.comboAffordable(0, 1), "with plenty, the combo");
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(6.5, 2, 6.5));
        husk.setNoAi(true);
        Familiars.order(lf, husk);
        h.succeedWhen(() -> {
            h.assertTrue(StatusData.has(husk, Status.parse("#weak")), "its attack should land");
            h.assertTrue(StatusData.has(husk, Status.parse("#strong")), "and the dearer page with it");
        });
    }

    /** Its master hurt, it uses its HEAL page on them. */
    static void healsMaster(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        FamiliarGameTests.forceAround(h, 1);
        ManaLifeform lf = summon(h, p, "(spell :mana 300 (summon mana_lifeform :at (pos-of caster)))");
        // Declared a heal so the test can see it was cast (a mark), whatever the difficulty does to health.
        lf.core.setItem(0, page("(spell :name \"回春\" :intent heal :mana 10 (mark (master) #healed))"));
        p.setHealth(6);
        h.succeedWhen(() -> h.assertTrue(StatusData.has(p, Status.parse("#healed")), "it should cast its heal page on its master"));
    }

    /** A healing projectile, ray or beam it aims at its master lands on them instead of passing through. */
    static void healProjectileHitsMaster(GameTestHelper h) {
        healFormHitsMaster(h, "projectile");
    }

    static void healRayHitsMaster(GameTestHelper h) {
        healFormHitsMaster(h, "ray");
    }

    static void healBeamHitsMaster(GameTestHelper h) {
        healFormHitsMaster(h, "beam");
    }

    private static void healFormHitsMaster(GameTestHelper h, String form) {
        ServerPlayer p = FamiliarGameTests.master(h);
        FamiliarGameTests.forceAround(h, 1);
        ManaLifeform lf = summon(h, p, "(spell :mana 300 (summon mana_lifeform :at (pos-of caster)))");
        lf.setNoAi(true);
        lf.snapTo(p.getX() + 3, p.getY() + 1, p.getZ(), 0, 0);
        lf.core.setItem(0, page("(spell :name \"愈\" :players true :mana 30 (" + form + " :dir (toward here (pos-of (master))) (on-hit (heal target 4))))"));
        // Too hungry to regenerate on its own, so only the heal can raise its health.
        p.getFoodData().setFoodLevel(10);
        p.setHealth(6);
        h.assertTrue(lf.castSlot(0, p), "it should cast its heal page");
        h.succeedWhen(() -> h.assertTrue(p.getHealth() >= 10, "the " + form + " should heal its master: " + p.getHealth()));
    }

    /** Free mana nearby and nothing to fight: it flies over and drinks it. */
    static void foragesFreeMana(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        FamiliarGameTests.forceAround(h, 1);
        ManaLifeform lf = summon(h, p, "(spell :mana 200 (summon mana_lifeform :at (pos-of caster)))");
        double before = lf.mana();
        Casting.cast(p, Casting.compile("(spell :mana 41 (mark caster #a))"), null, null);
        // Its master full, so the free mana is not drawn to them instead.
        dev.logos.runtime.PlayerMagic m = dev.logos.runtime.PlayerMagic.of(p);
        m.mana = m.maxMana;
        h.succeedWhen(() -> h.assertTrue(lf.mana() > before + 20, "it should have drunk the free mana: " + lf.mana() + " from " + before));
    }

    /** stance :role sets how it weighs its choices; an unknown role puts the spell out. */
    static void roleByStance(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        ManaLifeform lf = summon(h, p, "(spell :mana 200 (summon mana_lifeform :at (pos-of caster)))");
        h.assertTrue(lf.role() == ManaLifeform.Role.AUTO, "it starts out balanced");
        Casting.cast(p, Casting.compile("(stance :role \"guard\")"), null, null);
        h.assertTrue(lf.role() == ManaLifeform.Role.GUARD, "stance :role guard should make it a guard");
        SpellInstance bad = Casting.cast(p, Casting.compile("(stance :role \"dance\")"), null, null);
        h.assertTrue(bad != null && bad.dead && lf.role() == ManaLifeform.Role.GUARD, "an unknown role is refused");
        h.succeed();
    }
}
