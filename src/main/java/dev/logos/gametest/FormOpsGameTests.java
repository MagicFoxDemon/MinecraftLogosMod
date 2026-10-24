package dev.logos.gametest;

import java.util.Map;

import dev.logos.lang.Motion;
import dev.logos.lang.Status;
import dev.logos.runtime.BeamObj;
import dev.logos.runtime.Casting;
import dev.logos.runtime.ConstructEntity;
import dev.logos.runtime.Ctx;
import dev.logos.runtime.FieldObj;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.ProjectileObj;
import dev.logos.runtime.SpellInstance;
import dev.logos.runtime.StatusData;
import dev.logos.runtime.Vfx;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** What a spell can do to its own bodies: read where they are and face, move, halt, turn and disperse them. */
final class FormOpsGameTests {
    private FormOpsGameTests() {}

    private static ServerPlayer caster(GameTestHelper h) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) h.setBlock(x, 1, z, Blocks.STONE);
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 pos = h.absoluteVec(new Vec3(0.5, 2, 0.5));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0);
        PlayerMagic m = PlayerMagic.of(p);
        m.maxMana = m.mana = 1e9;
        m.trace = false;
        return p;
    }

    /** A live spell's context with here at relative {@code at}, for bodies made by hand. */
    private static Ctx ctx(GameTestHelper h, ServerPlayer p, String src, Vec3 at) {
        var inst = new SpellInstance(Casting.compile(src), p, h.getLevel(), 1000, 1);
        inst.objects = 1;
        inst.hold();
        return new Ctx(inst, h.getLevel(), h.absoluteVec(at), new Vec3(0, 0, 1), null, Map.of(), 0, 0);
    }

    /** A projectile turns and re-aims at its own speed, hangs still when halted, and reads its flight as its facing. */
    static void projectile(GameTestHelper h) {
        ServerPlayer p = caster(h);
        String src = "(projectile)";
        Ctx c = ctx(h, p, src, new Vec3(4.5, 5, 4.5));
        var shot = new ProjectileObj(Casting.compile(src).body().getFirst(), c, c.here(), new Vec3(0, 0, 1), 0.2, 200, Motion.ARC, 0, 0.1, Vfx.Guise.PLAIN);
        shot.turn(new Vec3(1, 0, 0));
        h.assertTrue(shot.facing().distanceTo(new Vec3(1, 0, 0)) < 1e-9, "turn should face east, faces " + shot.facing());
        Vec3 before = shot.position();
        shot.tick();
        h.assertTrue(shot.position().subtract(before).distanceTo(new Vec3(0.2, 0, 0)) < 1e-9, "turned, it flies east at its own speed and no longer arcs");
        shot.halt();
        Vec3 held = shot.position();
        for (int k = 0; k < 5; k++) shot.tick();
        h.assertTrue(shot.position().distanceTo(held) < 1e-9, "halted, it hangs still");
        Vec3 aim = held.add(0, 0, -3);
        shot.moveTo(aim, 0);
        shot.tick();
        h.assertTrue(shot.position().subtract(held).distanceTo(new Vec3(0, 0, -0.2)) < 1e-9, "move re-aims it at its own speed");
        h.succeed();
    }

    /** A beam moved, halted or turned keeps that start and direction instead of following the caster; pos-of reads its start. */
    static void beam(GameTestHelper h) {
        ServerPlayer p = caster(h);
        String src = "(beam :range 4)";
        Ctx c = ctx(h, p, src, new Vec3(0.5, 3.6, 0.5));
        var beam = new BeamObj(Casting.compile(src).body().getFirst(), c, 0.5, 4, 200, 5, null, null, Vfx.NONE);
        beam.tick();
        h.assertTrue(beam.position().distanceTo(p.getEyePosition()) < 1e-6, "an unwritten :from starts at the caster's eyes");
        Vec3 from = h.absoluteVec(new Vec3(6.5, 4, 6.5));
        beam.moveTo(from, 0);
        beam.turn(new Vec3(0, 0, -1));
        p.setYRot(90);
        beam.tick();
        h.assertTrue(beam.position().distanceTo(from) < 1e-9, "moved, it comes out from there, got " + beam.position());
        h.assertTrue(beam.facing().distanceTo(new Vec3(0, 0, -1)) < 1e-9, "turned, it keeps pointing north however the caster looks");
        h.succeed();
    }

    /** A field glides where it is sent and stops when halted. */
    static void field(GameTestHelper h) {
        ServerPlayer p = caster(h);
        String src = "(field)";
        Ctx c = ctx(h, p, src, new Vec3(1.5, 3, 1.5));
        var field = new FieldObj(Casting.compile(src).body().getFirst(), c, c.here(), 2, 200, 100, false);
        Vec3 dest = h.absoluteVec(new Vec3(5.5, 3, 1.5));
        field.moveTo(dest, 0.5);
        for (int k = 0; k < 4; k++) field.tick();
        h.assertTrue(field.position().distanceTo(h.absoluteVec(new Vec3(3.5, 3, 1.5))) < 1e-9, "four ticks at 0.5 a tick: halfway, got " + field.position());
        field.halt();
        Vec3 held = field.position();
        for (int k = 0; k < 4; k++) field.tick();
        h.assertTrue(field.position().distanceTo(held) < 1e-9, "halted, it stays");
        h.succeed();
    }

    /** Level direction from a construct's centre to its caster. */
    private static Vec3 toCaster(ConstructEntity wall, ServerPlayer p) {
        Vec3 d = p.position().subtract(wall.geometry().center);
        return new Vec3(d.x, 0, d.z).normalize();
    }

    /**
     * Through spells: a wall turned by its own event faces the new way; pos-of reads a kept field; disperse ends a body
     * early and runs its on-expire.
     */
    static void throughSpells(GameTestHelper h) {
        ServerPlayer p = caster(h);
        Casting.cast(p, Casting.compile("(spell :mana 200 (construct :shape wall :width 2 :height 2 :life 30 :every 0.25 :at (offset 4 0 0)"
                + " (on-tick (turn self (toward here (pos-of caster))))))"), null, null);
        Casting.cast(p, Casting.compile("(spell :mana 200 (field :life 5 :every 0.25 (on-tick (disperse form)) (on-expire (mark caster #gone))))"), null, null);
        // A field made where the caster stands; the caster steps away, and pos-of the kept field brings them back.
        Vec3 start = p.position();
        Casting.cast(p, Casting.compile("(spell :mana 200 (let $f (field :life 5) (delay 0.5 (blink (pos-of $f)))))"), null, null);
        p.snapTo(start.x + 4, start.y, start.z + 4, 0, 0);
        FamiliarGameTests.forceAround(h, 1);
        h.succeedWhen(() -> {
            var wall = ConstructEntity.near(h.getLevel(), new AABB(h.absolutePos(BlockPos.ZERO)).inflate(16)).stream().findFirst().orElse(null);
            h.assertTrue(wall != null && wall.geometry().normal().dot(toCaster(wall, p)) > 0.95,
                    "the wall should have turned to face its caster; wall " + (wall == null ? "missing" : wall.geometry().normal() + " owner " + wall.owner()));
            h.assertTrue(StatusData.stacks(p, new Status("#gone")) == 1, "disperse should end the field early and run its on-expire");
            h.assertTrue(p.position().distanceTo(start) < 1.5, "pos-of the kept field should bring the caster back to " + start + ", caster at " + p.position());
        });
    }
}
