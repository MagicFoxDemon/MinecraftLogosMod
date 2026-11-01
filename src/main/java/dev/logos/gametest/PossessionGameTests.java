package dev.logos.gametest;

import dev.logos.LogosMod;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Familiars;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.Possession;
import dev.logos.runtime.SpellInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 附身: riding inside a creature, the body left behind, and every way it ends. */
final class PossessionGameTests {
    private PossessionGameTests() {}

    /** A survival player on a stone floor at the structure's corner, with mana to spare. */
    private static ServerPlayer caster(GameTestHelper h) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) h.setBlock(x, 1, z, Blocks.STONE);
        FamiliarGameTests.forceAround(h, 1);
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        p.connection.markClientLoaded();
        Vec3 pos = h.absoluteVec(new Vec3(0.5, 2, 0.5));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0);
        PlayerMagic m = PlayerMagic.of(p);
        m.maxMana = m.mana = 1e9;
        m.trace = false;
        return p;
    }

    private static Mannequin bodyNear(GameTestHelper h, Vec3 at) {
        var bodies = h.getLevel().getEntitiesOfClass(Mannequin.class, new AABB(at, at).inflate(1));
        h.assertTrue(bodies.size() == 1, "one body should stand where the player stood, found " + bodies.size());
        return bodies.getFirst();
    }

    /**
     * The rune puts the player inside the pig for current health / 5 × √seconds; the hidden player
     * takes no damage, the body's goes to them, and what went for them goes for the body. Sneaking
     * puts them back in their body, which is gone.
     */
    static void body(GameTestHelper h) {
        ServerPlayer p = caster(h);
        ServerLevel level = h.getLevel();
        Vec3 start = p.position();
        BlockPos at = new BlockPos(6, 2, 6);
        var pig = h.spawn(EntityType.PIG, at);
        pig.setHealth(5);
        SpellInstance inst = Casting.cast(p, Casting.compile("(possess (nearest :radius 2) 4)"), null, Vec3.atCenterOf(h.absolutePos(at)));
        h.assertTrue(inst != null && !inst.dead, "the cast should go through");
        // 5 / 5 × √4 = 2, and 1 for nearest.
        h.assertTrue(Math.abs(inst.injected - inst.mana - 3) < 1e-6, "possessing should cost 3 with the selector, cost " + (inst.injected - inst.mana));
        h.assertTrue(p.getVehicle() == pig && Possession.possessing(p) && Possession.possessor(pig) == p, "the player should be inside the pig");
        Mannequin body = bodyNear(h, start);

        float health = p.getHealth();
        p.hurtServer(level, level.damageSources().generic(), 4);
        h.assertTrue(p.getHealth() == health, "the hidden player should take no damage of their own");
        body.hurtServer(level, level.damageSources().generic(), 4);
        h.assertTrue(Math.abs(p.getHealth() - (health - 4)) < 1e-4, "the body's damage should go to the player, health " + p.getHealth());
        h.assertTrue(body.isAlive(), "the body itself never dies");

        // A mock player always counts as creative, which mobs never target; so ask the event directly.
        var wolf = h.spawn(EntityType.WOLF, new BlockPos(2, 2, 6));
        var redirected = net.neoforged.neoforge.common.CommonHooks.onLivingChangeTarget(wolf, p,
                net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent.LivingTargetType.MOB_TARGET).getNewAboutToBeSetTarget();
        h.assertTrue(redirected == body, "a creature going for the player should go for their body, got " + redirected);
        wolf.setTarget(body);
        h.assertTrue(wolf.getTarget() == body, "the body should be a target creatures can keep");

        p.setShiftKeyDown(true);
        h.succeedWhen(() -> {
            h.assertTrue(!Possession.possessing(p) && p.getVehicle() == null, "sneaking should end the possession");
            h.assertTrue(body.isRemoved(), "the body should be gone once the player is back in it");
            h.assertTrue(p.position().distanceTo(start) < 0.01, "the player should be back where their body stood, at " + p.position());
            h.assertTrue(pig.isAlive() && Possession.possessor(pig) == null, "the pig should be free and unharmed");
        });
    }

    /** The possessed creature's death throws the player back into their body, a quarter of their max health the poorer. */
    static void backlash(GameTestHelper h) {
        ServerPlayer p = caster(h);
        ServerLevel level = h.getLevel();
        Vec3 start = p.position();
        var pig = h.spawn(EntityType.PIG, new BlockPos(6, 2, 6));
        h.assertTrue(Possession.start(p, pig, 30), "the possession should start");
        Mannequin body = bodyNear(h, start);
        pig.kill(level);
        h.assertTrue(!Possession.possessing(p) && body.isRemoved(), "the pig's death should end the possession");
        h.assertTrue(p.position().distanceTo(start) < 0.01, "the player should be back in their body, at " + p.position());
        float expected = p.getMaxHealth() * (1 - Possession.BACKLASH);
        h.assertTrue(Math.abs(p.getHealth() - expected) < 1e-4, "the player should lose a quarter of their max health, health " + p.getHealth());
        h.succeed();
    }

    /** It wears off by itself; only one at a time, only on mobs within range, never on a player or a boss; free on one's own familiar. */
    static void limits(GameTestHelper h) {
        ServerPlayer p = caster(h);
        ServerLevel level = h.getLevel();
        Vec3 start = p.position();
        var pig = h.spawn(EntityType.PIG, new BlockPos(6, 2, 6));
        h.assertTrue(Possession.cost(pig, p, 9) == pig.getHealth() / 5 * 3, "the price should be health / 5 × √seconds");
        pig.setData(LogosMod.FAMILIAR, new Familiars.Bond(p.getUUID(), Long.MAX_VALUE));
        h.assertTrue(Possession.cost(pig, p, 9) == 0, "one's own familiar should be free");

        var far = EntityType.PIG.create(level, EntitySpawnReason.TRIGGERED);
        far.setPos(start.add(Possession.RANGE + 2, 0, 0));
        h.assertTrue(!Possession.canStart(p, far), "a mob beyond 128 blocks should be out of reach");
        var wither = EntityType.WITHER.create(level, EntitySpawnReason.TRIGGERED);
        wither.setPos(start.add(4, 0, 4));
        h.assertTrue(!Possession.canStart(p, wither), "a boss should not be possessable");
        @SuppressWarnings("removal")
        ServerPlayer other = h.makeMockServerPlayerInLevel();
        h.assertTrue(!Possession.canStart(p, other), "a player should not be possessable");

        h.assertTrue(Possession.start(p, pig, 0.5), "the possession should start");
        var cow = h.spawn(EntityType.COW, new BlockPos(2, 2, 6));
        h.assertTrue(!Possession.canStart(p, cow), "one possession at a time");
        h.succeedWhen(() -> {
            h.assertTrue(!Possession.possessing(p), "half a second should wear off by itself");
            h.assertTrue(p.position().distanceTo(start) < 0.01, "the player should be back in their body, at " + p.position());
        });
    }

    /** Left click is the creature's own blow, once a second; the use button its shot, if it has one. */
    static void attacks(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var wolf = h.spawn(EntityType.WOLF, new Vec3(4.5, 2, 4.5));
        var cow = h.spawn(EntityType.COW, new Vec3(4.5, 2, 5.6));
        h.assertTrue(Possession.start(p, wolf, 30), "the possession should start");
        float full = cow.getHealth();
        Possession.strike(p, cow);
        float hit = cow.getHealth();
        h.assertTrue(hit < full, "the wolf's bite should hurt the cow");
        Possession.strike(p, cow);
        h.assertTrue(cow.getHealth() == hit, "a second blow within a second should not land");
        Possession.end(p, false);

        var golem = h.spawn(EntityType.SNOW_GOLEM, new Vec3(6.5, 2, 2.5));
        h.assertTrue(Possession.start(p, golem, 30), "the snow golem possession should start");
        Possession.shoot(p, null);
        h.assertTrue(!h.getLevel().getEntitiesOfClass(Snowball.class, golem.getBoundingBox().inflate(3)).isEmpty(),
                "the snow golem should throw a snowball where the player looks");
        Possession.end(p, false);
        h.succeed();
    }
}
