package dev.logos.gametest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import dev.logos.LogosMod;
import dev.logos.runtime.AlchemicalColossus;
import dev.logos.runtime.Familiars;
import dev.logos.runtime.FreeManaEntity;
import dev.logos.runtime.ManaLeech;
import dev.logos.runtime.ManaLeechBane;
import dev.logos.runtime.ManaLifeform;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Isolated predator encounters, exact feeding boundaries, and conservation of stored mana. */
final class ManaLeechGameTests {
    private static int nextFixture;
    private static final Map<GameTestHelper, Vec3> ORIGINS = new IdentityHashMap<>();
    private static final Set<GameTestHelper> READY = Collections.newSetFromMap(new IdentityHashMap<>());
    private ManaLeechGameTests() {}

    /** Rich players in other simultaneous GameTests must not become this fixture's prey. */
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper h;
        final ServerLevel level;
        final Vec3 origin;
        final Difficulty previousDifficulty;
        final List<Entity> entities = new ArrayList<>();
        final List<BlockPos> forced = new ArrayList<>();

        Fixture(GameTestHelper helper) {
            h = helper;
            level = h.getLevel();
            previousDifficulty = level.getDifficulty();
            if (previousDifficulty == Difficulty.PEACEFUL) level.getServer().setDifficulty(Difficulty.NORMAL, true);
            origin = ORIGINS.computeIfAbsent(h, key -> new Vec3(-100000 + nextFixture++ * 128, 120, -100000));
            for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++)
                level.setBlock(BlockPos.containing(origin.add(x, -1, z)), Blocks.STONE.defaultBlockState(), 3);
            force();
        }

        Vec3 at(double x, double z) { return origin.add(x, 0, z); }

        @SuppressWarnings("removal")
        ServerPlayer player(double x, double z, double mana) {
            ServerPlayer p = h.makeMockServerPlayerInLevel();
            Vec3 pos = at(x, z);
            p.snapTo(pos.x, pos.y, pos.z, 0, 0);
            p.setGameMode(GameType.SURVIVAL);
            p.connection.markClientLoaded();
            p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
            p.setHealth(200);
            PlayerMagic.of(p).maxMana = Math.max(100, mana);
            PlayerMagic.of(p).mana = mana;
            PlayerMagic.of(p).trace = false;
            entities.add(p);
            return p;
        }

        <T extends Mob> T mob(EntityType<T> type, double x, double z) {
            T mob = type.create(level, EntitySpawnReason.MOB_SUMMONED);
            h.assertTrue(mob != null, "entity creation failed: " + type);
            Vec3 pos = at(x, z);
            mob.snapTo(pos.x, pos.y, pos.z, 0, 0);
            mob.setNoAi(true);
            level.addFreshEntity(mob);
            entities.add(mob);
            return mob;
        }

        ManaLeech leech(double x, double z) { return mob(LogosMod.MANA_LEECH.get(), x, z); }

        ManaLeech natural(double x, double z) {
            ManaLeech leech = leech(x, z);
            leech.finalizeSpawn(level, level.getCurrentDifficultyAt(leech.blockPosition()), EntitySpawnReason.NATURAL, null);
            return leech;
        }

        void force() {
            BlockPos center = BlockPos.containing(origin);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                BlockPos chunk = center.offset(x * 16, 0, z * 16);
                level.setChunkForced(chunk.getX() >> 4, chunk.getZ() >> 4, true);
                forced.add(chunk);
            }
        }

        AABB bounds() { return new AABB(origin, origin).inflate(40); }

        double freeMana() {
            return level.getEntitiesOfClass(FreeManaEntity.class, bounds(), Entity::isAlive)
                    .stream().mapToDouble(FreeManaEntity::amount).sum();
        }

        int shards() {
            return level.getEntitiesOfClass(ItemEntity.class, bounds(), Entity::isAlive).stream()
                    .filter(e -> e.getItem().is(LogosMod.TAINTED_MANA_SHARD.get()))
                    .mapToInt(e -> e.getItem().getCount()).sum();
        }

        @Override public void close() {
            for (Entity e : entities) if (!e.isRemoved()) e.discard();
            for (Entity e : level.getEntitiesOfClass(Entity.class, bounds(), e -> e instanceof ManaLeech
                    || e instanceof FreeManaEntity || e instanceof ItemEntity)) e.discard();
            for (BlockPos chunk : forced) level.setChunkForced(chunk.getX() >> 4, chunk.getZ() >> 4, false);
            level.getServer().setDifficulty(previousDifficulty, true);
        }
    }

    /** Force-loaded chunks become queryable asynchronously; poll a real entity before running an encounter. */
    private static boolean awaitWorld(GameTestHelper h, Consumer<GameTestHelper> test) {
        if (READY.contains(h)) return false;
        Fixture primer = new Fixture(h);
        ArmorStand marker = EntityType.ARMOR_STAND.create(primer.level, EntitySpawnReason.MOB_SUMMONED);
        h.assertTrue(marker != null, "could not create the chunk-readiness marker");
        marker.setPos(primer.origin.x, primer.origin.y, primer.origin.z);
        marker.setNoGravity(true);
        marker.setInvulnerable(true);
        primer.level.addFreshEntity(marker);
        primer.entities.add(marker);
        h.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) {}
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { clean(); }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { clean(); }
            @Override public void testAddedForRerun(GameTestInfo original, GameTestInfo copy, GameTestRunner runner) {}
            private void clean() {
                primer.close();
                READY.remove(h);
                ORIGINS.remove(h);
            }
        });
        h.startSequence().thenWaitUntil(() -> h.assertTrue(
                primer.level.getEntitiesOfClass(ArmorStand.class, primer.bounds()).contains(marker),
                "isolated fixture chunks must become accessible to world entity queries"))
                .thenExecute(() -> {
                    marker.discard();
                    READY.add(h);
                    test.accept(h);
                });
        return true;
    }

    private static void near(GameTestHelper h, double actual, double expected, String message) {
        h.assertTrue(Math.abs(actual - expected) < .001, message + ": expected " + expected + ", got " + actual);
    }

    /** Tick the prey too, so vanilla damage immunity expires between one-second feeding pulses. */
    private static void step(ManaLeech leech) {
        LivingEntity prey = leech.attachedTarget() != null ? leech.attachedTarget() : leech.getTarget();
        if (prey instanceof ServerPlayer player) {
            player.tick();
        } else if (prey != null && prey.isAlive()) prey.tick();
        leech.tick();
    }

    /** These steps occur in one callback; the server cannot add an extra entity tick between them. */
    private static void steps(ManaLeech leech, int ticks) {
        for (int i = 0; i < ticks; i++) step(leech);
    }

    private static void charge(Fixture f, ManaLeech leech, LivingEntity prey) {
        leech.setNoAi(false);
        leech.setTarget(prey);
        step(leech);
        f.h.assertTrue(leech.state() == ManaLeech.State.CHARGE && leech.stateTicks() == 0,
                "prey in reach should start a fresh charge: " + leech.state() + "/" + leech.stateTicks());
    }

    private static void attach(Fixture f, ManaLeech leech, LivingEntity prey) {
        charge(f, leech, prey);
        steps(leech, 29);
        f.h.assertTrue(leech.state() == ManaLeech.State.CHARGE && leech.stateTicks() == 29,
                "charge must last 30 ticks");
        step(leech);
        f.h.assertTrue(leech.state() == ManaLeech.State.POUNCE, "the thirtieth charge tick should start a pounce");
        for (int i = 0; i < 20 && leech.state() == ManaLeech.State.POUNCE; i++) step(leech);
        f.h.assertTrue(leech.state() == ManaLeech.State.ATTACHED && leech.attachedTarget() == prey,
                "pounce should make actual face contact, got " + leech.state() + " at " + leech.position());
        f.h.assertTrue(leech.stateTicks() == 0, "contact must start a new 60-tick attachment clock");
    }

    private static CompoundTag save(Fixture f, ManaLeech leech) {
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, f.level.registryAccess());
        leech.saveWithoutId(out);
        return out.buildResult();
    }

    private static void load(Fixture f, ManaLeech leech, CompoundTag tag) {
        leech.load(TagValueInput.create(ProblemReporter.DISCARDING, f.level.registryAccess(), tag));
    }

    static void richestPrey(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::richestPrey)) return;
        try (Fixture f = new Fixture(h)) {
            ManaLeech leech = f.leech(0, 0);
            ServerPlayer poor = f.player(2, 0, 20), rich = f.player(10, 0, 50);
            ServerPlayer outside = f.player(24.1, 0, 1000);
            ServerPlayer creative = f.player(3, 0, 2000);
            creative.setGameMode(GameType.CREATIVE);
            ServerPlayer spectator = f.player(4, 0, 3000);
            spectator.setGameMode(GameType.SPECTATOR);
            var wild = f.mob(EntityType.IRON_GOLEM, 5, 0);
            h.assertTrue(leech.findPrey() == rich, "highest mana in the 24-block sphere wins, not the nearest or wild mob");
            PlayerMagic.of(rich).mana = 0;
            h.assertTrue(leech.findPrey() == poor, "empty pools should not be pursued");
            near(h, ManaLeech.availableMana(outside), 1000, "range must affect selection, not the mana query");
            near(h, ManaLeech.availableMana(creative), 0, "creative players are excluded");
            near(h, ManaLeech.availableMana(spectator), 0, "spectators are excluded");
            near(h, ManaLeech.availableMana(wild), 0, "wild creatures are not player familiars");
            PlayerMagic.of(poor).mana = 1000;
            PlayerMagic.of(poor).maxMana = 7;
            near(h, ManaLeech.availableMana(poor), 7, "player mana is bounded by its capacity");
        }
        h.succeed();
    }

    static void familiarPools(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::familiarPools)) return;
        try (Fixture f = new Fixture(h)) {
            ManaLeech leech = f.leech(0, 0);
            ServerPlayer owner = f.player(0, 8, 0);
            owner.setGameMode(GameType.CREATIVE);
            var ordinary = f.mob(EntityType.IRON_GOLEM, 4, 0);
            ordinary.setHealth(40);
            Familiars.bindForTest(ordinary, owner);
            ManaLifeform lifeform = f.mob(LogosMod.MANA_LIFEFORM.get(), 8, 0);
            Familiars.bindForTest(lifeform, owner);
            lifeform.setMana(60);
            AlchemicalColossus colossus = f.mob(LogosMod.ALCHEMICAL_COLOSSUS.get(), 12, 0);
            Familiars.bindForTest(colossus, owner);
            colossus.setMana(80);
            near(h, ManaLeech.availableMana(ordinary), 40, "ordinary familiar health is its mana store");
            near(h, ManaLeech.availableMana(lifeform), 60, "lifeform uses its own pool");
            near(h, ManaLeech.availableMana(colossus), 80, "colossus uses its own pool");
            h.assertTrue(leech.findPrey() == colossus, "compare player-created and ordinary familiar pools on the same scale");
            colossus.setMana(20);
            h.assertTrue(leech.findPrey() == lifeform, "changing a pool changes the richest prey");
        }
        h.succeed();
    }

    static void chargeInterrupted(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::chargeInterrupted)) return;
        try (Fixture f = new Fixture(h)) {
            ManaLeech leech = f.leech(0, 0);
            ServerPlayer prey = f.player(0, 2, 100);
            charge(f, leech, prey);
            steps(leech, 12);
            near(h, leech.chargeProgress(), .4, "charge progress follows elapsed ticks");
            leech.hurtServer(f.level, leech.damageSources().generic(), 1);
            h.assertTrue(leech.state() == ManaLeech.State.SEEK && leech.stateTicks() == 0,
                    "a successful hit cancels the charge immediately");
            leech.setDeltaMovement(Vec3.ZERO);
            leech.snapTo(f.origin.x, f.origin.y, f.origin.z, 0, 0);
            charge(f, leech, prey);
            steps(leech, 10);
            Vec3 far = f.at(0, 4);
            prey.snapTo(far.x, far.y, far.z, 0, 0);
            step(leech);
            h.assertTrue(leech.state() == ManaLeech.State.SEEK && leech.getTarget() == null,
                    "leaving three blocks cancels rather than completes the charge");
            near(h, PlayerMagic.of(prey).mana, 100, "interrupted charges must never drain");
        }
        h.succeed();
    }

    static void preciseFeeding(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::preciseFeeding)) return;
        Difficulty previous = h.getLevel().getDifficulty();
        try (Fixture f = new Fixture(h)) {
            f.level.getServer().setDifficulty(Difficulty.NORMAL, true);
            ManaLeech leech = f.leech(0, 0);
            ServerPlayer prey = f.player(0, 2, 100);
            attach(f, leech, prey);
            steps(leech, 19);
            near(h, PlayerMagic.of(prey).mana, 100, "no drain before one full second");
            step(leech);
            near(h, PlayerMagic.of(prey).mana, 90, "first drain occurs at tick 20");
            near(h, leech.mana(), 10, "first drain is conserved");
            near(h, leech.attackDamage(), 4, "first actual ten mana raises attack before damage");
            near(h, prey.getHealth(), 196, "first normal spell hit is four");
            steps(leech, 19);
            near(h, leech.mana(), 10, "no second drain at tick 39");
            step(leech);
            near(h, leech.mana(), 20, "second drain at tick 40");
            steps(leech, 19);
            h.assertTrue(leech.state() == ManaLeech.State.ATTACHED, "hold through tick 59");
            step(leech);
            near(h, PlayerMagic.of(prey).mana, 70, "exactly three ten-mana drains");
            near(h, leech.mana(), 30, "store thirty actual mana");
            near(h, leech.totalAbsorbedMana(), 30, "cumulative absorption counts actual transfers");
            near(h, leech.attackDamage(), 8, "thirty actual mana gives eight attack");
            near(h, prey.getHealth(), 182, "updated attacks deal four, six, then eight");
            h.assertTrue(leech.state() == ManaLeech.State.FALL && leech.attachedTarget() == null,
                    "drop off after the tick-60 drain");
        } finally { h.getLevel().getServer().setDifficulty(previous, true); }
        h.succeed();
    }

    static void difficultyDamage(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::difficultyDamage)) return;
        Difficulty previous = h.getLevel().getDifficulty();
        try {
            for (Difficulty difficulty : List.of(Difficulty.EASY, Difficulty.NORMAL, Difficulty.HARD)) {
                try (Fixture f = new Fixture(h)) {
                    f.level.getServer().setDifficulty(difficulty, true);
                    ManaLeech leech = f.leech(0, 0);
                    ServerPlayer prey = f.player(0, 2, 100);
                    attach(f, leech, prey);
                    steps(leech, 20);
                    double damage = difficulty == Difficulty.HARD ? 6 : 4;
                    near(h, 200 - prey.getHealth(), damage, difficulty + " applies the required scaling exactly once");
                    near(h, leech.spellDamage(difficulty), damage, "displayed spell attack agrees with real damage");
                }
            }
        } finally { h.getLevel().getServer().setDifficulty(previous, true); }
        h.succeed();
    }

    static void limitedBalance(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::limitedBalance)) return;
        Difficulty previous = h.getLevel().getDifficulty();
        try (Fixture f = new Fixture(h)) {
            f.level.getServer().setDifficulty(Difficulty.NORMAL, true);
            ManaLeech leech = f.leech(0, 0);
            ServerPlayer prey = f.player(0, 2, 5);
            attach(f, leech, prey);
            steps(leech, 20);
            near(h, PlayerMagic.of(prey).mana, 0, "five remaining mana never becomes negative");
            near(h, leech.mana(), 5, "credit only the available five");
            near(h, leech.attackDamage(), 3, "partial transfer gives the proportional attack");
            steps(leech, 39);
            h.assertTrue(leech.state() == ManaLeech.State.ATTACHED, "an empty player pool does not shorten the three-second latch");
            step(leech);
            near(h, leech.mana(), 5, "later empty-pool pulses create no mana");
            near(h, leech.totalAbsorbedMana(), 5, "later pulses do not fabricate attack growth");
            near(h, prey.getHealth(), 191, "all three one-second pulses still deal the current three attack");
            h.assertTrue(leech.state() == ManaLeech.State.FALL, "empty pool still drops after precisely sixty ticks");
        } finally { h.getLevel().getServer().setDifficulty(previous, true); }
        h.succeed();
    }

    static void ordinaryFamiliarDrain(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::ordinaryFamiliarDrain)) return;
        Difficulty previous = h.getLevel().getDifficulty();
        try (Fixture f = new Fixture(h)) {
            f.level.getServer().setDifficulty(Difficulty.HARD, true);
            ServerPlayer owner = f.player(0, 8, 0);
            owner.setGameMode(GameType.CREATIVE);
            var familiar = f.mob(EntityType.HUSK, 0, 2);
            familiar.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
            familiar.setHealth(100);
            Familiars.bindForTest(familiar, owner);
            ManaLeech leech = f.leech(0, 0);
            attach(f, leech, familiar);
            steps(leech, 60);
            near(h, familiar.getHealth(), 70, "ordinary familiars lose only the three actual ten-mana drains, without extra hard spell damage");
            near(h, leech.mana(), 30, "ordinary familiar actual health loss is stored mana");
            near(h, leech.attackDamage(), 8, "ordinary familiar absorption still grows attack");
        } finally { h.getLevel().getServer().setDifficulty(previous, true); }
        h.succeed();
    }

    static void madeFamiliarDrain(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::madeFamiliarDrain)) return;
        try (Fixture f = new Fixture(h)) {
            f.level.getServer().setDifficulty(Difficulty.NORMAL, true);
            ServerPlayer owner = f.player(0, 8, 0);
            owner.setGameMode(GameType.CREATIVE);
            ManaLifeform familiar = f.mob(LogosMod.MANA_LIFEFORM.get(), 0, 2);
            familiar.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
            familiar.setHealth(100);
            familiar.setMana(100);
            Familiars.bindForTest(familiar, owner);
            ManaLeech leech = f.leech(0, 0);
            attach(f, leech, familiar);
            steps(leech, 20);
            near(h, familiar.mana(), 90, "a made familiar loses ten from its existing mana pool");
            near(h, familiar.getHealth(), 96, "a made familiar separately takes the updated four spell attack");
            near(h, leech.mana(), 10, "spell health loss is not counted as extra absorbed mana");
        }
        h.succeed();
    }

    static void resistedFamiliarDrain(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::resistedFamiliarDrain)) return;
        try (Fixture f = new Fixture(h)) {
            ServerPlayer owner = f.player(0, 8, 0);
            owner.setGameMode(GameType.CREATIVE);
            var familiar = f.mob(EntityType.HUSK, 0, 2);
            familiar.setInvulnerable(true);
            Familiars.bindForTest(familiar, owner);
            ManaLeech leech = f.leech(0, 0);
            attach(f, leech, familiar);
            steps(leech, 60);
            near(h, familiar.getHealth(), 20, "an invulnerable ordinary familiar loses no health");
            near(h, leech.mana(), 0, "rejected damage never creates absorbed mana");
            near(h, leech.totalAbsorbedMana(), 0, "rejected damage never creates cumulative attack growth");
        }
        h.succeed();
    }

    static void splitTimingAndOffspring(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::splitTimingAndOffspring)) return;
        try (Fixture f = new Fixture(h)) {
            ManaLeech mother = f.natural(0, 0);
            mother.setStoredMana(60);
            mother.setNoAi(false);
            mother.tick();
            h.assertTrue(mother.state() == ManaLeech.State.SPLIT && mother.stateTicks() == 0, "natural original starts a fresh split at sixty mana");
            steps(mother, 39);
            h.assertTrue(!mother.isRemoved(), "mother survives until split tick forty");
            mother.tick();
            h.assertTrue(mother.isRemoved(), "mother is replaced rather than killed at tick forty");
            List<ManaLeech> children = f.level.getEntitiesOfClass(ManaLeech.class, f.bounds(), Entity::isAlive);
            h.assertTrue(children.size() == 2, "split produces exactly two offspring");
            for (ManaLeech child : children) {
                near(h, child.mana(), 0, "offspring current pool starts empty");
                near(h, child.totalAbsorbedMana(), 0, "offspring do not inherit earned attack");
                near(h, child.attackDamage(), 2, "offspring starts at attack two");
                h.assertTrue(child.isSplitOffspring() && !child.canSplit(), "offspring is permanently excluded from splitting");
                child.setStoredMana(80);
                child.tick();
                h.assertTrue(child.state() != ManaLeech.State.SPLIT, "an offspring may hold more than sixty but never split");
            }
            near(h, f.freeMana(), 0, "splitting is not death and releases no free mana");
            h.assertTrue(f.shards() == 0, "splitting creates no death material");
        }
        h.succeed();
    }

    static void splitPopulationLimit(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::splitPopulationLimit)) return;
        try (Fixture f = new Fixture(h)) {
            ManaLeech mother = f.natural(0, 0);
            mother.setStoredMana(60);
            mother.setNoAi(false);
            List<ManaLeech> neighbours = new ArrayList<>();
            for (int i = 0; i < 4; i++) neighbours.add(f.leech(3 + i * 2, 0));
            h.assertTrue(mother.nearbyLeechCount() == 5, "sixteen-block population includes the mother");
            mother.tick();
            h.assertTrue(mother.state() != ManaLeech.State.SPLIT, "five living leeches block a split");
            neighbours.removeLast().discard();
            h.assertTrue(mother.nearbyLeechCount() == 4, "four originals can become five");
            mother.tick();
            h.assertTrue(mother.state() == ManaLeech.State.SPLIT, "four allows the split to begin");
            ManaLeech newcomer = f.leech(8, 2);
            steps(mother, 40);
            h.assertTrue(!mother.isRemoved() && mother.state() != ManaLeech.State.SPLIT,
                    "population is checked again at completion, so another arrival cancels the split");
            newcomer.discard();
            mother.tick();
            steps(mother, 40);
            h.assertTrue(mother.isRemoved(), "after population falls again the original can finish a new split");
            h.assertTrue(f.level.getEntitiesOfClass(ManaLeech.class, f.bounds(), Entity::isAlive).size() == 5,
                    "replacing one of four with two finishes at five");
        }
        h.succeed();
    }

    static void fullAttachmentBeforeSplit(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::fullAttachmentBeforeSplit)) return;
        try (Fixture f = new Fixture(h)) {
            f.level.getServer().setDifficulty(Difficulty.NORMAL, true);
            ManaLeech mother = f.natural(0, 0);
            mother.setStoredMana(40);
            ServerPlayer prey = f.player(0, 2, 100);
            attach(f, mother, prey);
            steps(mother, 40);
            near(h, mother.mana(), 60, "second feeding pulse reaches the split threshold");
            h.assertTrue(mother.state() == ManaLeech.State.ATTACHED && mother.stateTicks() == 40,
                    "reaching sixty mana must not interrupt the fixed three-second latch");
            steps(mother, 19);
            h.assertTrue(mother.state() == ManaLeech.State.ATTACHED, "hold even a full original until tick fifty-nine");
            step(mother);
            near(h, PlayerMagic.of(prey).mana, 70, "the third pulse is completed before splitting");
            near(h, mother.mana(), 70, "threshold does not truncate actual absorption");
            h.assertTrue(mother.state() == ManaLeech.State.SPLIT && mother.stateTicks() == 0,
                    "only the end of tick sixty starts a fresh forty-tick split");
            steps(mother, 39);
            h.assertTrue(!mother.isRemoved(), "the split still needs all forty ticks");
            step(mother);
            h.assertTrue(mother.isRemoved(), "the full latch then finishes its split");
        }
        h.succeed();
    }

    static void unnaturalNeverSplits(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::unnaturalNeverSplits)) return;
        try (Fixture f = new Fixture(h)) {
            ManaLeech summoned = f.leech(0, 0);
            summoned.setStoredMana(120);
            summoned.setNoAi(false);
            steps(summoned, 45);
            h.assertTrue(!summoned.canSplit() && !summoned.isRemoved() && summoned.state() != ManaLeech.State.SPLIT,
                    "only naturally spawned originals may split");
            near(h, summoned.mana(), 120, "blocked splitting does not cap or consume the current pool");
        }
        h.succeed();
    }

    static void deathDrops(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::deathDrops)) return;
        try (Fixture f = new Fixture(h)) {
            ManaLeech original = f.leech(0, 0);
            original.setStoredMana(25);
            original.hurtServer(f.level, original.damageSources().generic(), 1000);
            near(h, f.freeMana(), 20, "death releases eighty percent of the current pool");
            h.assertTrue(f.shards() == 1, "original drops one existing tainted mana shard");
            original.die(original.damageSources().generic());
            near(h, f.freeMana(), 20, "a repeated death callback cannot duplicate mana");
            near(h, original.mana(), 0, "death clears the released pool");
        }
        h.succeed();
    }

    static void offspringDeathDrops(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::offspringDeathDrops)) return;
        try (Fixture f = new Fixture(h)) {
            ManaLeech mother = f.natural(0, 0);
            mother.setStoredMana(60);
            mother.setNoAi(false);
            steps(mother, 41);
            List<ManaLeech> children = f.level.getEntitiesOfClass(ManaLeech.class, f.bounds(), Entity::isAlive);
            h.assertTrue(children.size() == 2, "fixture must split into two real offspring");
            for (ManaLeech child : children) {
                child.setNoAi(true);
                child.setStoredMana(45);
                int before = f.shards();
                child.hurtServer(f.level, child.damageSources().generic(), 1000);
                int dropped = f.shards() - before;
                h.assertTrue(dropped == 2 || dropped == 3, "each offspring drops two or three tainted shards, got " + dropped);
            }
            near(h, f.freeMana(), 0, "offspring never drop free mana, even after accumulating a pool");
        }
        h.succeed();
    }

    static void reloadStopsDuplicateFeeding(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::reloadStopsDuplicateFeeding)) return;
        Difficulty previous = h.getLevel().getDifficulty();
        try (Fixture f = new Fixture(h)) {
            f.level.getServer().setDifficulty(Difficulty.NORMAL, true);
            ServerPlayer prey = f.player(0, 2, 100);
            ManaLeech old = f.leech(0, 0);
            attach(f, old, prey);
            steps(old, 20);
            CompoundTag saved = save(f, old);
            old.discard();
            ManaLeech restored = f.leech(0, 0);
            load(f, restored, saved);
            h.assertTrue(restored.state() == ManaLeech.State.SEEK && restored.attachedTarget() == null,
                    "saved attachment must reacquire and charge instead of replaying a drain");
            near(h, restored.mana(), 10, "saved current pool is preserved");
            near(h, restored.totalAbsorbedMana(), 10, "saved attack growth is preserved");
            steps(restored, 19);
            near(h, PlayerMagic.of(prey).mana, 90, "reloading cannot repeat the previous one-second drain");
        } finally { h.getLevel().getServer().setDifficulty(previous, true); }
        h.succeed();
    }

    static void baneDispersal(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::baneDispersal)) return;
        for (int enchantmentLevel : List.of(1, 2, 5)) {
            try (Fixture f = new Fixture(h)) {
                ServerPlayer attacker = f.player(0, 2, 0);
                ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                sword.enchant(f.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                        .getOrThrow(Enchantments.BANE_OF_ARTHROPODS), enchantmentLevel);
                attacker.setItemInHand(InteractionHand.MAIN_HAND, sword);
                ManaLeech leech = f.leech(0, 0);
                leech.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
                leech.setHealth(200);
                leech.setStoredMana(50);
                CompoundTag earned = save(f, leech);
                earned.putDouble("absorbed_mana", 30);
                load(f, leech, earned);
                for (int i = 0; i < 30; i++) {
                    attacker.tick();
                    attacker.doTick();
                }
                near(h, attacker.getAttackStrengthScale(.5f), 1, "Bane fixture uses a fully charged vanilla attack");
                double before = leech.getHealth();
                double expectedDamage = attacker.getAttributeValue(Attributes.ATTACK_DAMAGE) + 2.5 * enchantmentLevel;
                attacker.attack(leech);
                double expelled = 50 * (enchantmentLevel + 1) * .1;
                near(h, leech.mana(), 50 - expelled, "Bane " + enchantmentLevel + " expels its fraction after a real weapon attack");
                near(h, f.freeMana(), expelled, "dispersed mana becomes free mana");
                near(h, leech.attackDamage(), 8, "dispersal never erases cumulative absorption");
                near(h, before - leech.getHealth(), expectedDamage,
                        "the real hit retains exactly the vanilla Bane bonus, with no extra custom damage");
                leech.invulnerableTime = 0;
                leech.hurtServer(f.level, leech.damageSources().generic(), 1000);
                near(h, f.freeMana(), expelled + (50 - expelled) * .8,
                        "later death releases eighty percent of only the remaining pool");
            }
        }
        near(h, ManaLeechBane.fraction(20), 1, "command-created high levels cap dispersal at one hundred percent");
        h.succeed();
    }

    static void arthropodAndHealth(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::arthropodAndHealth)) return;
        try (Fixture f = new Fixture(h)) {
            ManaLeech leech = f.leech(0, 0);
            near(h, leech.getMaxHealth(), 20, "initial health is twenty");
            near(h, leech.attackDamage(), 2, "initial spell attack is two");
            h.assertTrue(leech.getType().builtInRegistryHolder().is(EntityTypeTags.ARTHROPOD), "leech is registered as an arthropod");
            h.assertTrue(leech.getType().builtInRegistryHolder().is(EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS),
                    "vanilla Bane damage and slowness use the separate sensitive-to-Bane tag");
        }
        h.succeed();
    }

    static void rejectedBaneHit(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::rejectedBaneHit)) return;
        try (Fixture f = new Fixture(h)) {
            ServerPlayer attacker = f.player(0, 2, 0);
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            sword.enchant(f.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.BANE_OF_ARTHROPODS), 5);
            attacker.setItemInHand(InteractionHand.MAIN_HAND, sword);
            ManaLeech leech = f.leech(0, 0);
            leech.setStoredMana(50);
            leech.setInvulnerable(true);
            leech.hurtServer(f.level, leech.damageSources().playerAttack(attacker), 2);
            near(h, leech.mana(), 50, "a rejected Bane hit does not expel mana");
            near(h, f.freeMana(), 0, "a rejected hit produces no free mana");
        }
        h.succeed();
    }

    private static void weaponHit(Fixture f, ServerPlayer attacker, ManaLeech leech,
            net.minecraft.world.item.Item item, int knockback) {
        ItemStack weapon = new ItemStack(item);
        if (knockback > 0) weapon.enchant(f.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.KNOCKBACK), knockback);
        attacker.setItemInHand(InteractionHand.MAIN_HAND, weapon);
        for (int i = 0; i < 40; i++) {
            attacker.tick();
            attacker.doTick();
        }
        attacker.attack(leech);
    }

    static void knockbackSwordDetaches(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::knockbackSwordDetaches)) return;
        try (Fixture f = new Fixture(h)) {
            ManaLeech leech = f.leech(0, 0);
            ServerPlayer prey = f.player(0, 2, 100), attacker = f.player(2, 2, 0);
            attach(f, leech, prey);
            Vec3 before = leech.position();
            weaponHit(f, attacker, leech, Items.DIAMOND_SWORD, 1);
            h.assertTrue(leech.getHealth() < leech.getMaxHealth(), "the real knockback sword hit must take effect");
            h.assertTrue(leech.state() == ManaLeech.State.FALL && leech.attachedTarget() == null,
                    "an effective knockback-enchanted sword hit drops a latched leech");
            h.assertTrue(leech.getDeltaMovement().length() > .1 && !leech.isNoGravity(),
                    "detachment supplies an actual impulse and restores gravity");
            leech.tick();
            h.assertTrue(leech.position().distanceTo(before) > .01, "the detached leech must leave its face position");
            near(h, leech.mana(), 0, "knocking off a leech creates no mana");
        }
        h.succeed();
    }

    static void ordinaryWeaponKeepsLatch(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::ordinaryWeaponKeepsLatch)) return;
        for (ItemStack weapon : List.of(new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.DIAMOND_AXE))) {
            try (Fixture f = new Fixture(h)) {
                ManaLeech leech = f.leech(0, 0);
                ServerPlayer prey = f.player(0, 2, 100), attacker = f.player(2, 2, 0);
                attach(f, leech, prey);
                weaponHit(f, attacker, leech, weapon.getItem(), weapon.is(Items.DIAMOND_AXE) ? 1 : 0);
                h.assertTrue(leech.getHealth() < leech.getMaxHealth(), "negative fixture still needs an effective weapon hit");
                h.assertTrue(leech.state() == ManaLeech.State.ATTACHED && leech.attachedTarget() == prey,
                        "a normal sword or command-enchanted axe must not detach the leech");
                near(h, leech.mana(), 0, "the negative weapon hit creates no mana");
            }
        }
        h.succeed();
    }

    static void invulnerableSwordKeepsLatch(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::invulnerableSwordKeepsLatch)) return;
        try (Fixture f = new Fixture(h)) {
            ManaLeech leech = f.leech(0, 0);
            ServerPlayer prey = f.player(0, 2, 100), attacker = f.player(2, 2, 0);
            attach(f, leech, prey);
            leech.setInvulnerable(true);
            weaponHit(f, attacker, leech, Items.DIAMOND_SWORD, 1);
            near(h, leech.getHealth(), 20, "the rejected sword attack loses no health");
            h.assertTrue(leech.state() == ManaLeech.State.ATTACHED && leech.attachedTarget() == prey,
                    "a rejected knockback sword hit cannot drop the leech");
            near(h, leech.mana(), 0, "a rejected weapon hit creates no mana");
        }
        h.succeed();
    }

    /** Real level ticks cover navigation, face contact, falling and starting a second full encounter. */
    static void automaticRepeatedEncounter(GameTestHelper h) {
        if (awaitWorld(h, ManaLeechGameTests::automaticRepeatedEncounter)) return;
        Fixture f = new Fixture(h);
        f.level.getServer().setDifficulty(Difficulty.NORMAL, true);
        ServerPlayer prey = f.player(0, 8, 100);
        ManaLeech leech = f.leech(0, 0);
        leech.setNoAi(false);
        boolean[] attached = {false}, fell = {false};
        h.succeedWhen(() -> {
            if (leech.state() == ManaLeech.State.ATTACHED) attached[0] = true;
            if (attached[0] && leech.state() == ManaLeech.State.FALL) fell[0] = true;
            h.assertTrue(leech.isAlive(), "the encounter should not kill the leech");
            h.assertTrue(attached[0] && fell[0], "real ticks must navigate, attach and fall before repeating; state=" + leech.state());
            h.assertTrue(leech.totalAbsorbedMana() >= 40, "the second encounter must absorb again, got " + leech.totalAbsorbedMana());
            near(h, PlayerMagic.of(prey).mana + leech.mana(), 100, "both real encounters conserve mana");
            f.close();
        });
    }
}
