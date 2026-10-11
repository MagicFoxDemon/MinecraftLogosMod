package dev.logos.gametest;

import dev.logos.LogosMod;
import dev.logos.runtime.AlchemicalColossus;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Colossi;
import dev.logos.runtime.ColossusMind;
import dev.logos.runtime.Familiars;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The alchemical colossus: armour, execute, its hands and cannon, riding, and the mana frenzy. */
final class ColossusGameTests {
    private ColossusGameTests() {}

    private static AlchemicalColossus raise(GameTestHelper h, ServerPlayer owner, Vec3 rel) {
        String why = Colossi.summon(owner, h.getLevel(), h.absoluteVec(rel), null);
        h.assertTrue(why == null, "summon failed: " + why);
        for (var m : Familiars.of(owner)) if (m instanceof AlchemicalColossus c) return c;
        throw new AssertionError("no colossus after summoning");
    }

    /** 800 health, a full visible pool of 50000, and every blow cut to a quarter plus one. */
    static void armour(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        AlchemicalColossus c = raise(h, p, new Vec3(5, 2, 5));
        h.assertTrue(c.getMaxHealth() == 800 && c.getHealth() == 800, "800 health expected, got " + c.getMaxHealth());
        h.assertTrue(c.mana() == 50000 && c.maxMana() == 50000, "a full visible pool of 50000, got " + c.mana() + "/" + c.maxMana());
        c.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(), 40);
        h.assertTrue(Math.abs(c.getHealth() - 789) < 0.01, "40 damage should take 40/4 + 1 = 11, health " + c.getHealth());
        h.succeed();
    }

    /** execute does not kill a colossus: its mana is wiped and it powers down. */
    static void executeDrains(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        @SuppressWarnings("removal")
        ServerPlayer other = h.makeMockServerPlayerInLevel();
        AlchemicalColossus c = raise(h, other, new Vec3(5, 2, 5));
        Casting.cast(p, Casting.compile("(spell :mana 100000 (execute (nearest :radius 12)))"), null, null);
        h.assertTrue(c.isAlive(), "execute must not kill a colossus");
        h.assertTrue(c.mana() == 0 && c.isDormant(), "its mana wiped and powered down: " + c.mana() + ", dormant " + c.isDormant());
        h.succeed();
    }

    /** What it will not pick up, and where it finishes rather than slams. */
    static void heavyAndFinish(GameTestHelper h) {
        var warden = h.spawn(EntityType.WARDEN, new Vec3(2, 2, 2));
        var golem = h.spawn(EntityType.IRON_GOLEM, new Vec3(6, 2, 2));
        Zombie zombie = h.spawn(EntityType.ZOMBIE, new Vec3(2, 2, 6));
        h.assertTrue(ColossusMind.heavy(warden) && ColossusMind.heavy(golem), "a warden and an iron golem are too heavy to grab");
        h.assertTrue(!ColossusMind.heavy(zombie), "a zombie is not");
        h.assertTrue(ColossusMind.finishable(zombie), "a zombie's health and armour are under 60: finished at once");
        zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        zombie.setHealth(200);
        h.assertTrue(!ColossusMind.finishable(zombie), "a 200-health zombie at full health is slammed instead");
        warden.discard();
        golem.discard();
        zombie.discard();
        h.succeed();
    }

    /** A lesser foe in reach is picked up and, under the finishing line, finished. */
    static void grabFinishes(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        p.setGameMode(GameType.CREATIVE);
        FamiliarGameTests.forceAround(h, 1);
        AlchemicalColossus c = raise(h, p, new Vec3(4, 2, 4));
        Zombie z = h.spawn(EntityType.ZOMBIE, new Vec3(4.5, 2, 6.5));
        z.setNoAi(true);
        Familiars.order(c, z);
        h.succeedWhen(() -> h.assertTrue(!z.isAlive(), "the zombie should be grabbed and finished"));
    }

    /** A sturdier foe, above the finishing line, is slammed into the ground: 80 fall damage. */
    static void grabSlams(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        p.setGameMode(GameType.CREATIVE);
        FamiliarGameTests.forceAround(h, 1);
        AlchemicalColossus c = raise(h, p, new Vec3(4, 2, 4));
        Zombie z = h.spawn(EntityType.ZOMBIE, new Vec3(4.5, 2, 6.5));
        z.setNoAi(true);
        z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        z.setHealth(200);
        Familiars.order(c, z);
        h.succeedWhen(() -> {
            h.assertTrue(z.isAlive(), "slammed, not finished");
            h.assertTrue(z.getHealth() <= 125, "a slam should have taken about 80: health " + z.getHealth());
            h.assertTrue(z.getVehicle() == null, "and let go of it");
        });
    }

    /** A heavy foe in reach gets its fist instead: 50 damage. */
    static void punchesHeavy(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        p.setGameMode(GameType.CREATIVE);
        FamiliarGameTests.forceAround(h, 1);
        AlchemicalColossus c = raise(h, p, new Vec3(4, 2, 3));
        IronGolem g = h.spawn(EntityType.IRON_GOLEM, new Vec3(4.5, 2, 6.5));
        g.setNoAi(true);
        Familiars.order(c, g);
        h.succeedWhen(() -> h.assertTrue(g.getHealth() <= 55 || !g.isAlive(), "the golem should take a 50-damage blow: health " + g.getHealth()));
    }

    /** Out of reach it does not walk up: it charges its palm cannon and burns the target, without knocking it back at first. */
    static void cannonAtRange(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        p.setGameMode(GameType.CREATIVE);
        FamiliarGameTests.forceAround(h, 2);
        AlchemicalColossus c = raise(h, p, new Vec3(1.5, 2, 1.5));
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(1.5, 2, 15.5));
        husk.setNoAi(true);
        husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(400);
        husk.setHealth(400);
        Vec3 start = husk.position();
        Vec3 colossusAt = c.position();
        Familiars.order(c, husk);
        h.succeedWhen(() -> {
            h.assertTrue(husk.getHealth() < 400, "the beam should have struck: health " + husk.getHealth());
            h.assertTrue(husk.position().distanceTo(start) < 0.3, "no knockback in the first seconds");
            h.assertTrue(c.position().distanceTo(colossusAt) < 2.5, "it fired from where it stood instead of walking up");
        });
    }

    /** From raising its palm to firing, its body never drops back to no act between one part and the next (the animation would flash). */
    static void cannonActsUnbroken(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        p.setGameMode(GameType.CREATIVE);
        FamiliarGameTests.forceAround(h, 2);
        AlchemicalColossus c = raise(h, p, new Vec3(1.5, 2, 1.5));
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(1.5, 2, 15.5));
        husk.setNoAi(true);
        husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(400);
        husk.setHealth(400);
        Familiars.order(c, husk);
        boolean[] raised = {false}, gap = {false};
        h.succeedWhen(() -> {
            AlchemicalColossus.Act a = c.act();
            if (a == AlchemicalColossus.Act.CANNON_PREPARE) raised[0] = true;
            else if (raised[0] && a == AlchemicalColossus.Act.NONE) gap[0] = true;
            h.assertTrue(!gap[0], "the act dropped to NONE between cannon parts");
            h.assertTrue(a == AlchemicalColossus.Act.CANNON_FIRE, "not firing yet");
        });
    }

    /** Fed past what it shows it hides the rest;an unstable fuel sends it berserk; struck down berserk, it burns out tempered. */
    static void frenzyAndBurnout(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        AlchemicalColossus c = raise(h, p, new Vec3(5, 2, 5));
        c.feed(10000, p);
        h.assertTrue(c.mana() == 50000 && c.trueMana() == 60000 && !c.isBerserk(), "past 50000 it still shows 50000: " + c.mana() + " / " + c.trueMana());
        p.setGameMode(GameType.SURVIVAL);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.TAINTED_MANA_SHARD.get()));
        c.interact(p, InteractionHand.MAIN_HAND, c.position());
        h.assertTrue(c.isBerserk() && c.isScorched(), "an unstable fuel should send it berserk");
        var adv = h.getLevel().getServer().getAdvancements().get(Colossi.FRENZY_ADVANCEMENT);
        h.assertTrue(adv != null && p.getAdvancements().getOrStartProgress(adv).isDone(), "witnesses earn 《这巨像魔了，是个魔像》");
        c.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(), 100000);
        h.assertTrue(c.isAlive() && !c.isBerserk(), "a frenzy cannot be killed, only burnt out");
        h.assertTrue(c.isTempered() && c.isDormant() && c.maxMana() == 100000, "burnt out: tempered, powered down, its pool now 100000");
        h.assertTrue(c.isScorched(), "it keeps the scorched shell");
        boolean crystal = !h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(c.blockPosition()).inflate(4),
                i -> i.getItem().is(LogosMod.DISORDER_CRYSTAL.get())).isEmpty();
        h.assertTrue(crystal, "it should leave a disorder crystal");
        c.feed(80000, p);
        h.assertTrue(!c.isBerserk(), "tempered, it never goes berserk again");
        h.succeed();
    }

    /** Its master climbs on: lifted onto the left shoulder; wanting down, lifted back to the ground. A rider takes half damage. */
    static void boardAndPutDown(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        p.setGameMode(GameType.SURVIVAL);
        FamiliarGameTests.forceAround(h, 1);
        AlchemicalColossus c = raise(h, p, new Vec3(3.5, 2, 3.5));
        boolean[] asked = {false};
        h.succeedWhen(() -> {
            if (!asked[0]) {
                // Let it finish starting up before asking.
                h.assertTrue(c.act() == AlchemicalColossus.Act.NONE, "still starting up");
                c.interact(p, InteractionHand.MAIN_HAND, c.position());
                asked[0] = true;
            }
            h.assertTrue(p.getVehicle() == c && c.holdOf(p) == AlchemicalColossus.Hold.SEAT_LEFT, "it should lift its master onto its left shoulder");
        });
    }

    /** Seated, a rider who dismounts is lifted down rather than dropped; seated they take half damage. */
    static void riderSheltered(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        p.setGameMode(GameType.SURVIVAL);
        p.connection.markClientLoaded();
        FamiliarGameTests.forceAround(h, 1);
        AlchemicalColossus c = raise(h, p, new Vec3(3.5, 2, 3.5));
        int[] stage = {0};
        float[] before = {0};
        h.succeedWhen(() -> {
            if (stage[0] == 0) {
                h.assertTrue(c.act() == AlchemicalColossus.Act.NONE, "still starting up");
                c.interact(p, InteractionHand.MAIN_HAND, c.position());
                stage[0] = 1;
            }
            if (stage[0] == 1) {
                h.assertTrue(c.holdOf(p) == AlchemicalColossus.Hold.SEAT_LEFT, "not seated yet");
                before[0] = p.getHealth();
                p.invulnerableTime = 0;
                p.hurtServer(h.getLevel(), h.getLevel().damageSources().magic(), 8);
                h.assertTrue(Math.abs(before[0] - p.getHealth() - 4) < 0.01, "seated, 8 damage should take 4: took " + (before[0] - p.getHealth()));
                p.stopRiding();
                h.assertTrue(p.getVehicle() == c, "dismounting does not drop it off at once: it is lifted down");
                stage[0] = 2;
            }
            h.assertTrue(p.getVehicle() == null, "it should set its rider on the ground");
        });
    }
}
