package dev.logos.gametest;

import java.util.List;

import dev.logos.LogosMod;
import dev.logos.runtime.Familiars;
import dev.logos.runtime.FreeManaEntity;
import dev.logos.runtime.ManaLeech;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** The mana leech: whom it hunts, its coil-leap-cling cycle, what it drinks, splitting, and what blows and death let out. */
final class LeechGameTests {
    private LeechGameTests() {}

    /**
     * A player with a full pool of {@code mana} (so its regeneration cannot add to it), standing at
     * (1.5, 2, 1.5) looking +Z, and too hungry to heal (so its health shows each bite).
     */
    private static ServerPlayer prey(GameTestHelper h, double mana) {
        ServerPlayer p = FamiliarGameTests.master(h);
        PlayerMagic m = PlayerMagic.of(p);
        m.maxMana = m.mana = mana;
        p.getFoodData().setFoodLevel(17);
        p.getFoodData().setSaturation(0);
        return p;
    }

    /** A stone floor, for tests without a player (whose fixture lays one). */
    private static void floor(GameTestHelper h) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) h.setBlock(x, 1, z, Blocks.STONE);
    }

    private static ManaLeech leech(GameTestHelper h, double x, double z) {
        return h.spawn(LogosMod.MANA_LEECH.get(), new Vec3(x, 2, z));
    }

    private static float bite(GameTestHelper h, double attack) {
        return (float) (attack * (h.getLevel().getDifficulty() == Difficulty.HARD ? 1.5 : 1));
    }

    /** Of two players it goes for the one holding more mana, not the nearer one. */
    static void huntsRichest(GameTestHelper h) {
        FamiliarGameTests.forceAround(h, 1);
        ServerPlayer poor = prey(h, 5);
        @SuppressWarnings("removal")
        ServerPlayer rich = h.makeMockServerPlayerInLevel();
        Vec3 at = h.absoluteVec(new Vec3(7.5, 2, 7.5));
        rich.snapTo(at.x, at.y, at.z, 0, 0);
        rich.setGameMode(GameType.SURVIVAL);
        PlayerMagic.of(rich).maxMana = PlayerMagic.of(rich).mana = 80;
        ManaLeech leech = leech(h, 2.5, 4.5);
        h.succeedWhen(() -> h.assertTrue(leech.prey() == rich, "it should hunt the richer player, hunts " + leech.prey() + " (poor " + poor + ")"));
    }

    /**
     * Within three blocks it coils, leaps onto the face and clings, the player in darkness: each second it
     * drinks up to 10 mana — never more than the player holds — and bites for its attack, 2 plus 2 per 10
     * mana in it. After three seconds it drops off.
     */
    static void clingsAndDrains(GameTestHelper h) {
        FamiliarGameTests.forceAround(h, 1);
        ServerPlayer p = prey(h, 4);
        ManaLeech leech = leech(h, 1.5, 3.5);
        leech.setMana(25);
        h.assertTrue(leech.attack() == 6, "25 mana should make its attack 6, is " + leech.attack());
        leech.setMana(0);
        float start = p.getHealth();
        int[] phase = {0};
        h.succeedWhen(() -> {
            if (phase[0] == 0 && leech.state() == ManaLeech.State.LATCHED) {
                h.assertTrue(leech.latchedOn() == p, "it should cling to the player");
                phase[0] = 1;
            }
            if (phase[0] == 1 && leech.mana() > 0) {
                h.assertTrue(Math.abs(leech.mana() - 4) < 0.01, "it can take only the 4 the player holds, took " + leech.mana());
                h.assertTrue(PlayerMagic.of(p).mana < 0.31, "the player should be drained, has " + PlayerMagic.of(p).mana);
                h.assertTrue(p.hasEffect(MobEffects.DARKNESS), "a leech on the face should blind with darkness");
                float lost = start - p.getHealth();
                h.assertTrue(Math.abs(lost - bite(h, 2)) < 0.01, "the bite should be its attack of 2, lost " + lost);
                phase[0] = 2;
            }
            h.assertTrue(phase[0] == 2 && leech.state() != ManaLeech.State.LATCHED && leech.latchedOn() == null, "waiting, phase " + phase[0] + ", " + leech.state());
            h.assertTrue(leech.mana() <= 12.01, "three draws of at most 4, has " + leech.mana());
        });
    }

    /** A Knockback weapon strikes it off the face. */
    static void knockedOff(GameTestHelper h) {
        FamiliarGameTests.forceAround(h, 1);
        ServerPlayer p = prey(h, 100);
        ManaLeech leech = leech(h, 1.5, 3.5);
        ItemStack stick = new ItemStack(Items.STICK);
        stick.enchant(h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.KNOCKBACK), 1);
        p.setItemInHand(InteractionHand.MAIN_HAND, stick);
        boolean[] struck = {false};
        h.succeedWhen(() -> {
            if (!struck[0] && leech.state() == ManaLeech.State.LATCHED) {
                leech.hurtServer(h.getLevel(), p.damageSources().playerAttack(p), 1);
                h.assertTrue(leech.state() == ManaLeech.State.FALL && leech.latchedOn() == null, "a Knockback blow should strike it off, is " + leech.state());
                struck[0] = true;
            }
            h.assertTrue(struck[0], "waiting for it to cling");
        });
    }

    /** A summoned familiar has no pool: draining it wounds it by as much, on top of the bite. */
    static void drainsFamiliarHealth(GameTestHelper h) {
        FamiliarGameTests.forceAround(h, 1);
        ServerPlayer p = prey(h, 0);
        p.setGameMode(GameType.CREATIVE); // not hunted itself
        Husk z = h.spawn(EntityType.HUSK, new Vec3(4.5, 2, 1.5));
        z.setNoAi(true);
        Familiars.bindForTest(z, p);
        ManaLeech leech = leech(h, 4.5, 3.5);
        h.succeedWhen(() -> {
            h.assertTrue(leech.mana() >= 10, "waiting for the first draw, " + leech.state());
            h.assertTrue(Math.abs(20 - z.getHealth() - (10 + bite(h, 4))) < 0.01, "10 health and a bite expected, health " + z.getHealth());
        });
    }

    /**
     * Holding 60, a summoned leech splits into two empty offspring that cannot split — but not while
     * five leeches are about.
     */
    static void splits(GameTestHelper h) {
        floor(h);
        FamiliarGameTests.forceAround(h, 1);
        ManaLeech parent = leech(h, 4.5, 4.5);
        parent.setMana(60);
        List<ManaLeech> crowd = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ManaLeech l = leech(h, 1.5 + i * 2, 7.5);
            l.setNoAi(true);
            crowd.add(l);
        }
        h.runAfterDelay(50, () -> {
            h.assertTrue(parent.isAlive() && parent.state() != ManaLeech.State.SPLIT, "five leeches about: it must not split");
            crowd.forEach(l -> l.discard());
            h.succeedWhen(() -> {
                h.assertTrue(parent.isRemoved(), "waiting for the split, " + parent.state());
                List<ManaLeech> kids = h.getLevel().getEntitiesOfClass(ManaLeech.class, parent.getBoundingBox().inflate(4), ManaLeech::isAlive);
                h.assertTrue(kids.size() == 2, "two offspring expected, got " + kids.size());
                for (ManaLeech k : kids) h.assertTrue(k.isOffspring() && k.mana() == 0, "offspring start empty");
            });
        });
    }

    /**
     * Summoned as a familiar it neither drinks from itself nor turns on its master; ordered at a foe, it
     * drinks for its master: the draw goes into their pool and it keeps none.
     */
    static void familiarObeys(GameTestHelper h) {
        FamiliarGameTests.forceAround(h, 1);
        ServerPlayer p = prey(h, 50);
        ManaLeech leech = leech(h, 1.5, 3.5);
        Familiars.bindForTest(leech, p);
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(6.5, 2, 6.5));
        husk.setNoAi(true);
        h.runAfterDelay(60, () -> {
            h.assertTrue(leech.isAlive() && leech.getHealth() == leech.getMaxHealth(), "it must not drink from itself, health " + leech.getHealth());
            h.assertTrue(leech.prey() == null && leech.latchedOn() == null, "with no fight of its master's it must hunt nothing, hunts " + leech.prey());
            PlayerMagic m = PlayerMagic.of(p);
            m.maxMana = 1000;
            m.mana = 0;
            h.assertTrue(Familiars.order(leech, husk), "it should take the order");
            double[] last = {0};
            boolean[] gained = {false};
            h.succeedWhen(() -> {
                double now = m.mana;
                if (now - last[0] >= 9.9) gained[0] = true; // a draw, not the master's own trickle of regeneration
                last[0] = now;
                h.assertTrue(gained[0], "the master should receive a draw of 10, has " + now);
                h.assertTrue(leech.mana() == 0, "it should keep none, has " + leech.mana());
                h.assertTrue(husk.getHealth() <= 20 - 10 - bite(h, 2) + 0.01, "the husk should lose 10 health and a bite, has " + husk.getHealth());
            });
        });
    }

    /** Offspring never split, whatever they hold. */
    static void offspringDoNotSplit(GameTestHelper h) {
        floor(h);
        FamiliarGameTests.forceAround(h, 1);
        ManaLeech kid = leech(h, 4.5, 4.5);
        kid.setOffspring(true);
        kid.setMana(80);
        h.runAfterDelay(60, () -> {
            h.assertTrue(kid.isAlive() && kid.state() != ManaLeech.State.SPLIT, "an offspring must not split");
            h.succeed();
        });
    }

    /** Bane of Arthropods III scatters 40% of its mana a blow; dying it lets out 80% of the rest; offspring drop two shards. */
    static void baneAndDeath(GameTestHelper h) {
        ServerPlayer p = prey(h, 0);
        p.setGameMode(GameType.CREATIVE);
        ItemStack sword = new ItemStack(Items.WOODEN_SWORD);
        sword.enchant(h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.BANE_OF_ARTHROPODS), 3);
        p.setItemInHand(InteractionHand.MAIN_HAND, sword);
        ManaLeech leech = leech(h, 4.5, 4.5);
        leech.setNoAi(true);
        leech.setOffspring(true);
        leech.setMana(50);
        leech.hurtServer(h.getLevel(), p.damageSources().playerAttack(p), 1);
        h.assertTrue(Math.abs(leech.mana() - 30) < 0.01, "Bane III should scatter 40%, left " + leech.mana());
        h.assertTrue(Math.abs(freeMana(h, leech) - 20) < 0.01, "the scattered 20 should be free mana, got " + freeMana(h, leech));
        leech.invulnerableTime = 0;
        leech.hurtServer(h.getLevel(), h.getLevel().damageSources().genericKill(), Float.MAX_VALUE);
        h.assertTrue(Math.abs(freeMana(h, leech) - (20 + 24)) < 0.01, "80% of the 30 left should be set free, got " + freeMana(h, leech));
        h.succeedWhen(() -> {
            int shards = h.getLevel().getEntitiesOfClass(ItemEntity.class, leech.getBoundingBox().inflate(4),
                    e -> e.getItem().is(LogosMod.TAINTED_MANA_SHARD.get())).stream().mapToInt(e -> e.getItem().getCount()).sum();
            h.assertTrue(shards >= 2, "an offspring should drop two tainted shards, got " + shards);
        });
    }

    /** Free mana around the leech only: spell tests nearby leave theirs too. */
    private static double freeMana(GameTestHelper h, ManaLeech leech) {
        return h.getLevel().getEntitiesOfClass(FreeManaEntity.class, leech.getBoundingBox().inflate(2)).stream().mapToDouble(FreeManaEntity::amount).sum();
    }
}
