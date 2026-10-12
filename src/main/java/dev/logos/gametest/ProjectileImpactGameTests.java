package dev.logos.gametest;

import java.util.Map;
import dev.logos.lang.Motion;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Ctx;
import dev.logos.runtime.ProjectileObj;
import dev.logos.runtime.SpellInstance;
import dev.logos.runtime.Vfx;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Collision termination flags must not confuse expiry with a hit or consume piercing early. */
final class ProjectileImpactGameTests {
    private record Flight(ProjectileObj projectile, SpellInstance spell) {}

    @SuppressWarnings("removal")
    private static Flight flight(GameTestHelper h, double x, double speed, int life, int pierce) {
        var player = h.makeMockServerPlayerInLevel();
        var spell = Casting.compile("(projectile (on-hit (damage target 1 fire)))");
        var inst = new SpellInstance(spell, player, h.getLevel(), 1000, 1);
        inst.objects = 1;
        inst.hold();
        Vec3 origin = h.absoluteVec(new Vec3(x, 4.5, 2.5));
        var ctx = new Ctx(inst, h.getLevel(), origin, new Vec3(0, 0, 1), null, Map.of(), 0, 0);
        return new Flight(new ProjectileObj(spell.body().getFirst(), ctx, origin, ctx.aim(), speed,
                life, Motion.FORWARD, pierce, .1, Vfx.Guise.PLAIN), inst);
    }

    private static boolean collisionEnd(ProjectileObj projectile) {
        try {
            var field = ProjectileObj.class.getDeclaredField("impactEnd");
            field.setAccessible(true);
            return field.getBoolean(projectile);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    static void expiry(GameTestHelper h) {
        Flight f = flight(h, 4.5, .1, 1, 0);
        f.projectile.tick();
        h.assertTrue(f.spell.objects == 1, "flight ended before its lifetime");
        f.projectile.tick();
        h.assertTrue(f.spell.finished() && !collisionEnd(f.projectile), "expiry was classified as a collision");
        h.succeed();
    }

    static void block(GameTestHelper h) {
        h.setBlock(4, 4, 5, Blocks.STONE);
        Flight f = flight(h, 4.5, 5, 20, 0);
        f.projectile.tick();
        h.assertTrue(f.spell.finished() && collisionEnd(f.projectile), "wall contact was classified as expiry");
        h.succeed();
    }

    static void piercing(GameTestHelper h) {
        var first = h.spawn(EntityType.COW, new Vec3(4.5, 4, 5.5));
        var second = h.spawn(EntityType.COW, new Vec3(4.5, 4, 8.5));
        first.setNoAi(true);
        second.setNoAi(true);
        Flight f = flight(h, 4.5, 8, 20, 1);
        f.projectile.tick();
        h.assertTrue(first.getHealth() < first.getMaxHealth() && second.getHealth() < second.getMaxHealth(),
                "piercing did not hit both targets");
        h.assertTrue(f.spell.finished() && collisionEnd(f.projectile), "second hit did not end as a collision");

        var single = h.spawn(EntityType.COW, new Vec3(7.5, 4, 5.5));
        single.setNoAi(true);
        Flight expires = flight(h, 7.5, 4, 1, 1);
        expires.projectile.tick();
        h.assertTrue(single.getHealth() < single.getMaxHealth() && expires.spell.objects == 1,
                "a piercing hit ended flight early");
        expires.projectile.tick();
        h.assertTrue(expires.spell.finished() && !collisionEnd(expires.projectile),
                "expiry after an earlier piercing hit was classified as another impact");
        h.succeed();
    }
}
