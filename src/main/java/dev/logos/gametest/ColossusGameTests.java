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
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.warden.Warden;
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

    /** Only the ender dragon is too big to pick up; and where it finishes rather than slams — never a boss. */
    static void heavyAndFinish(GameTestHelper h) {
        var warden = h.spawn(EntityType.WARDEN, new Vec3(2, 2, 2));
        var golem = h.spawn(EntityType.IRON_GOLEM, new Vec3(6, 2, 2));
        var wither = h.spawn(EntityType.WITHER, new Vec3(6, 2, 6));
        var dragon = h.spawn(EntityType.ENDER_DRAGON, new Vec3(2, 6, 2));
        Zombie zombie = h.spawn(EntityType.ZOMBIE, new Vec3(2, 2, 6));
        h.assertTrue(!ColossusMind.heavy(warden) && !ColossusMind.heavy(golem) && !ColossusMind.heavy(wither), "a warden, an iron golem and a wither can be picked up");
        h.assertTrue(ColossusMind.heavy(dragon), "the ender dragon cannot");
        h.assertTrue(!ColossusMind.heavy(zombie), "a zombie can");
        wither.setHealth(10);
        h.assertTrue(!ColossusMind.finishable(wither), "a boss is never finished, even nearly dead: only slammed");
        h.assertTrue(ColossusMind.finishable(zombie), "a zombie's health and armour are under 60: finished at once");
        zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        zombie.setHealth(200);
        h.assertTrue(!ColossusMind.finishable(zombie), "a 200-health zombie at full health is slammed instead");
        warden.discard();
        golem.discard();
        wither.discard();
        dragon.discard();
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
            // The blow counts as its master's, but the zombie's anger goes to the colossus.
            h.assertTrue(z.getLastHurtByPlayer() == p, "its master should be credited with the blow");
            h.assertTrue(z.getLastHurtByMob() == c, "the zombie should blame the colossus, not its master: " + z.getLastHurtByMob());
        });
    }

    /** Three lesser foes in front of it: it sweeps them with its fist rather than picking one up. */
    static void punchesCrowd(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        p.setGameMode(GameType.CREATIVE);
        FamiliarGameTests.forceAround(h, 1);
        AlchemicalColossus c = raise(h, p, new Vec3(4.5, 2, 3.5));
        // Husks: they do not burn by day, so only its blow can hurt them.
        Husk[] crowd = new Husk[3];
        double[] xs = {3.5, 4.5, 5.5};
        for (int i = 0; i < 3; i++) {
            crowd[i] = h.spawn(EntityType.HUSK, new Vec3(xs[i], 2, 6.5));
            crowd[i].setNoAi(true);
        }
        Familiars.order(c, crowd[1]);
        boolean[] grabbed = {false};
        h.succeedWhen(() -> {
            if (c.act() == AlchemicalColossus.Act.GRAB_REACH) grabbed[0] = true;
            h.assertTrue(!grabbed[0], "it should not grab one out of a crowd");
            int struck = 0;
            for (Husk z : crowd) if (!z.isAlive() || z.getHealth() < z.getMaxHealth()) struck++;
            h.assertTrue(struck == 3, "one blow should catch all three: " + struck);
        });
    }

    /** Killed while it holds a foe up, it lets go: the foe drops free with its own will back, not frozen in the air. */
    static void letsGoWhenKilled(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        p.setGameMode(GameType.CREATIVE);
        FamiliarGameTests.forceAround(h, 1);
        AlchemicalColossus c = raise(h, p, new Vec3(4.5, 2, 3.5));
        Zombie z = h.spawn(EntityType.ZOMBIE, new Vec3(4.5, 2, 6.5));
        z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        z.setHealth(200);
        Familiars.order(c, z);
        boolean[] killed = {false};
        h.succeedWhen(() -> {
            if (!killed[0]) {
                h.assertTrue(c.holdOf(z) == AlchemicalColossus.Hold.GRIP, "not holding it yet");
                c.kill(h.getLevel());
                killed[0] = true;
            }
            h.assertTrue(z.getVehicle() == null, "its hand should open");
            h.assertTrue(!z.isNoAi(), "and the zombie get its own will back");
        });
    }

    /** A big foe is picked up too: a warden, too sturdy to finish, is slammed for 80. */
    static void grabsBig(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        p.setGameMode(GameType.CREATIVE);
        FamiliarGameTests.forceAround(h, 1);
        AlchemicalColossus c = raise(h, p, new Vec3(4, 2, 3));
        Warden w = h.spawn(EntityType.WARDEN, new Vec3(4.5, 2, 6.5));
        w.setNoAi(true);
        Familiars.order(c, w);
        h.succeedWhen(() -> {
            h.assertTrue(w.isAlive() && w.getHealth() <= 500 - 75, "the warden should be slammed: health " + w.getHealth());
            h.assertTrue(w.getVehicle() == null, "and let go of");
        });
    }

    /**
     * The ender dragon, too big to hold, gets its fist instead — and feels it: only players can hurt the
     * dragon, and the colossus's blows count as its master's.
     */
    static void punchesDragon(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        p.setGameMode(GameType.CREATIVE);
        FamiliarGameTests.forceAround(h, 2);
        AlchemicalColossus c = raise(h, p, new Vec3(4, 2, 3));
        EnderDragon d = h.spawn(EntityType.ENDER_DRAGON, new Vec3(4.5, 3, 9.5));
        d.setNoAi(true);
        float start = d.getHealth();
        Familiars.order(c, d);
        h.succeedWhen(() -> {
            h.assertTrue(d.getVehicle() == null && c.act() != AlchemicalColossus.Act.GRAB_REACH, "the dragon must never be grabbed at");
            h.assertTrue(d.getHealth() < start, "the dragon should take its blow: health " + d.getHealth());
        });
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

    /** The beam strikes the moment it appears (5 physical + 10 fire), not half a second later. */
    static void cannonStrikesAtOnce(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        p.setGameMode(GameType.CREATIVE);
        FamiliarGameTests.forceAround(h, 2);
        AlchemicalColossus c = raise(h, p, new Vec3(1.5, 2, 1.5));
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(1.5, 2, 15.5));
        husk.setNoAi(true);
        husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(400);
        husk.setHealth(400);
        husk.getAttribute(Attributes.ARMOR).setBaseValue(0); // so the strike lands whole
        Familiars.order(c, husk);
        int[] firing = {0};
        h.succeedWhen(() -> {
            if (c.act() == AlchemicalColossus.Act.CANNON_FIRE || firing[0] > 0) firing[0]++;
            h.assertTrue(firing[0] > 0, "not firing yet");
            if (firing[0] > 4) h.fail("no strike in the beam's first ticks: health " + husk.getHealth());
            h.assertTrue(husk.getHealth() < 400, "waiting for the opening strike");
            h.assertTrue(Math.abs(400 - husk.getHealth() - 15) < 0.01, "the opening strike should deal 5 + 10: took " + (400 - husk.getHealth()));
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

    /**
     * Driven, its rider's client moves it and vanilla runs none of its AI on the server; held right
     * button still raises and charges the cannon, letting go fires it, and afterwards a left click is
     * still a blow.
     */
    static void driveControls(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        p.setGameMode(GameType.SURVIVAL);
        FamiliarGameTests.forceAround(h, 1);
        AlchemicalColossus c = raise(h, p, new Vec3(3.5, 2, 3.5));
        int[] stage = {0};
        h.succeedWhen(() -> {
            AlchemicalColossus.Act a = c.act();
            if (stage[0] == 0) {
                h.assertTrue(a == AlchemicalColossus.Act.NONE, "still starting up");
                c.interact(p, InteractionHand.MAIN_HAND, c.position());
                stage[0] = 1;
            }
            if (stage[0] == 1) {
                h.assertTrue(c.holdOf(p) == AlchemicalColossus.Hold.SEAT_LEFT && a == AlchemicalColossus.Act.NONE, "not seated yet");
                Colossi.control(p, Colossi.TOGGLE_DRIVE);
                h.assertTrue(c.driver() == p, "its master should be driving");
                Colossi.control(p, Colossi.CHARGE);
                stage[0] = 2;
            }
            if (stage[0] == 2) {
                // Charged and held: it waits for the release.
                h.assertTrue(a == AlchemicalColossus.Act.CANNON_CHARGE && c.actTicks() > 40, "the cannon should charge and stay raised: " + a);
                Colossi.control(p, Colossi.LOOSE);
                stage[0] = 3;
            }
            if (stage[0] == 3) {
                h.assertTrue(a == AlchemicalColossus.Act.CANNON_FIRE, "letting go should fire: " + a);
                stage[0] = 4;
            }
            if (stage[0] == 4) {
                h.assertTrue(c.mind().doingZh().equals("听从驾驶"), "the shot should end: " + c.mind().doingZh());
                Colossi.control(p, Colossi.STRIKE);
                stage[0] = 5;
            }
            h.assertTrue(a == AlchemicalColossus.Act.PUNCH, "a left click afterwards should still be a blow: " + a);
        });
    }

    /** Carrying its master (riding along, not driving), it stands its ground instead of walking after a spot beside them that moves with it. */
    static void carriesMasterStill(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        p.setGameMode(GameType.SURVIVAL);
        FamiliarGameTests.forceAround(h, 1);
        AlchemicalColossus c = raise(h, p, new Vec3(3.5, 2, 3.5));
        int[] stage = {0};
        Vec3[] seatedAt = {null};
        double[] drift = {0};
        h.succeedWhen(() -> {
            if (stage[0] == 0) {
                h.assertTrue(c.act() == AlchemicalColossus.Act.NONE, "still starting up");
                c.interact(p, InteractionHand.MAIN_HAND, c.position());
                stage[0] = 1;
            }
            if (stage[0] == 1) {
                h.assertTrue(c.holdOf(p) == AlchemicalColossus.Hold.SEAT_LEFT && c.act() == AlchemicalColossus.Act.NONE, "not seated yet");
                // Facing back over its shoulder: the spot "at its master's side" is then well behind it.
                p.setYRot(c.getYRot() + 180);
                seatedAt[0] = c.position();
                stage[0] = 2;
            }
            drift[0] = Math.max(drift[0], c.position().distanceTo(seatedAt[0]));
            h.assertTrue(drift[0] < 0.5, "it wandered off with its master aboard: " + drift[0]);
            h.assertTrue(++stage[0] > 80, "watching it stand");
        });
    }

    /** Once it has offered a flower it will not want to offer another for minutes, however much it is fed. */
    static void flowerCooldown(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        p.setGameMode(GameType.SURVIVAL);
        FamiliarGameTests.forceAround(h, 1);
        AlchemicalColossus c = raise(h, p, new Vec3(3.5, 2, 3.5));
        boolean[] asked = {false};
        h.succeedWhen(() -> {
            if (!asked[0]) {
                h.assertTrue(c.act() == AlchemicalColossus.Act.NONE, "still starting up");
                c.mind().maybeOfferFlower(p, 1);
                h.assertTrue(c.mind().wantsFlower(), "a sure chance should make it want to offer one");
                asked[0] = true;
            }
            h.assertTrue(c.act() == AlchemicalColossus.Act.FLOWER_OFFER, "it should kneel to offer it");
            c.mind().maybeOfferFlower(p, 1);
            h.assertTrue(!c.mind().wantsFlower(), "right after one flower it should not want another");
        });
    }

    /** Seated, a rider who sneaks twice in quick succession jumps straight off, without waiting to be lifted down. */
    static void jumpsOff(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        p.setGameMode(GameType.SURVIVAL);
        FamiliarGameTests.forceAround(h, 1);
        AlchemicalColossus c = raise(h, p, new Vec3(3.5, 2, 3.5));
        int[] stage = {0};
        h.succeedWhen(() -> {
            if (stage[0] == 0) {
                h.assertTrue(c.act() == AlchemicalColossus.Act.NONE, "still starting up");
                c.interact(p, InteractionHand.MAIN_HAND, c.position());
                stage[0] = 1;
            }
            if (stage[0] == 1) {
                h.assertTrue(c.holdOf(p) == AlchemicalColossus.Hold.SEAT_LEFT && c.act() == AlchemicalColossus.Act.NONE, "not seated yet");
                p.stopRiding(); // first sneak: it starts lifting them down
                stage[0] = 2;
                return;
            }
            if (stage[0] == 2) {
                h.assertTrue(p.getVehicle() == c, "a single sneak does not drop them off at once");
                p.stopRiding(); // second sneak
                stage[0] = 3;
            }
            if (stage[0] >= 3 && stage[0]++ > 10) h.fail("still riding after a double sneak");
            h.assertTrue(p.getVehicle() == null, "a double sneak should let them jump straight off");
            h.assertTrue(c.act() != AlchemicalColossus.Act.PUTDOWN_LEFT, "without the lifting down");
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
