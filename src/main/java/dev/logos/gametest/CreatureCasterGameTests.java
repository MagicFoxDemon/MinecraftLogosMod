package dev.logos.gametest;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import dev.logos.lang.Status;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Familiars;
import dev.logos.runtime.ManaInfusion;
import dev.logos.runtime.ManaVessel;
import dev.logos.runtime.ManaVessels;
import dev.logos.runtime.SpellInstance;
import dev.logos.runtime.SpellModifiers;
import dev.logos.runtime.StatusData;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.phys.Vec3;

/** Creatures that cast without being familiars (another mod's maid): a tamed one is on its owner's side, and a compat can give one a pool. */
final class CreatureCasterGameTests {
    private CreatureCasterGameTests() {}

    /** Pools the test gives its own creatures, as a compat would; weak, so the lookup forgets them with the test. */
    private static final Map<Entity, Pool> POOLS = Collections.synchronizedMap(new WeakHashMap<>());

    static {
        ManaVessels.register(POOLS::get);
    }

    private static final class Pool implements ManaVessel {
        double mana, max;

        Pool(double mana, double max) {
            this.mana = mana;
            this.max = max;
        }

        @Override
        public double mana() {
            return mana;
        }

        @Override
        public double maxMana() {
            return max;
        }

        @Override
        public double receiveMana(double amount) {
            double took = Math.max(0, Math.min(amount, max - mana));
            mana += took;
            return took;
        }
    }

    private static Wolf tamedWolf(GameTestHelper h, ServerPlayer owner) {
        Wolf w = h.spawn(EntityType.WOLF, new Vec3(3.5, 2, 3.5));
        w.setNoAi(true);
        w.tame(owner);
        return w;
    }

    /** A tamed creature's spell spares its owner's familiar as a lifeform's spares its master's, and (master) is its owner. */
    static void tamedCasterOnOwnerSide(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        Wolf w = tamedWolf(h, p);
        Husk ours = h.spawn(EntityType.HUSK, new Vec3(5.5, 2, 3.5));
        ours.setNoAi(true);
        Familiars.bindForTest(ours, p);
        Husk wild = h.spawn(EntityType.HUSK, new Vec3(3.5, 2, 5.5));
        wild.setNoAi(true);
        Casting.castAs(w, Casting.compile("(spell :mana 100 (mark (enemies :radius 6) #hit) (mark (master) #mine))"), 100, 0, null, null);
        h.assertTrue(StatusData.has(wild, Status.parse("#hit")), "a wild husk is the wolf's enemy");
        h.assertFalse(StatusData.has(ours, Status.parse("#hit")), "its owner's familiar should be spared");
        h.assertTrue(StatusData.has(p, Status.parse("#mine")), "(master) should be the wolf's owner");
        h.assertFalse(StatusData.has(w, Status.parse("#mine")), "(master) should not be the wolf itself");
        h.succeed();
    }

    /** A pool a compat keeps for a creature takes its spells' leftover back, and infusion and mana potions fill it. */
    static void lookedUpPoolTakesMana(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        Wolf w = tamedWolf(h, p);
        Pool pool = new Pool(0, 500);
        POOLS.put(w, pool);
        SpellInstance inst = Casting.castAs(w, Casting.compile("(spell :mana 50 (mark caster #a))"), 50, 0, null, null);
        h.assertTrue(inst.finished(), "the spell should be over");
        h.assertTrue(pool.mana > 40 && pool.mana < 50, "its leftover should be back in its pool: " + pool.mana);
        double before = pool.mana;
        h.assertTrue(ManaInfusion.room(w) == 500 - before && ManaInfusion.give(w, 20) == 20, "infusion should see and fill its pool");
        h.assertTrue(SpellModifiers.restoreMana(w, 10) == 10, "a mana potion should fill its pool");
        h.assertTrue(Math.abs(pool.mana - before - 30) < 1e-6, "its pool should hold the 30 given: " + (pool.mana - before));
        h.succeed();
    }
}
