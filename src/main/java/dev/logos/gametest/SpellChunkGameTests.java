package dev.logos.gametest;

import java.util.List;
import java.util.Map;

import dev.logos.LogosConfig;
import dev.logos.lang.Motion;
import dev.logos.lang.Status;
import dev.logos.runtime.BeamObj;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Ctx;
import dev.logos.runtime.Interp;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.ProjectileObj;
import dev.logos.runtime.SpellInstance;
import dev.logos.runtime.SpellObject;
import dev.logos.runtime.StatusData;
import dev.logos.runtime.Vfx;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * In-world tests for {@code spellsLoadChunks}: with it off, spells leave unloaded chunks unloaded; with it on, they fly and
 * reach as far as ever. Each test runs start to finish in one call, far from anything else, so it controls which chunks are
 * loaded: {@code getChunk} loads just the launch chunk, and the next one along stays out until something asks for it.
 */
final class SpellChunkGameTests {
    /** How far out, in chunks, the launch chunks lie: no other test comes near. */
    private static final int FAR = 20000;

    private SpellChunkGameTests() {}

    private static ServerPlayer caster(GameTestHelper h) {
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 pos = h.absoluteVec(new Vec3(0.5, 2, 0.5));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0);
        PlayerMagic m = PlayerMagic.of(p);
        m.baseMaxMana = m.mana = 1e9;
        m.trace = false;
        return p;
    }

    /** A live spell's context with here at {@code at}, aiming east, for bodies made by hand. */
    private static Ctx ctx(GameTestHelper h, ServerPlayer p, String src, Vec3 at) {
        var inst = new SpellInstance(Casting.compile(src), p, h.getLevel(), 1000, 1);
        inst.objects = 1;
        inst.hold();
        return new Ctx(inst, h.getLevel(), at, new Vec3(1, 0, 0), null, Map.of(), 0, 0);
    }

    private static boolean loaded(GameTestHelper h, int chunkX, int chunkZ) {
        return h.getLevel().getChunkSource().getChunkNow(chunkX, chunkZ) != null;
    }

    /** Loads the chunk {@code slot} rows out, unique to each test, checks the next one east is not, and returns its middle at {@code y}. */
    private static Vec3 launch(GameTestHelper h, int slot, double y) {
        ServerLevel level = h.getLevel();
        int chunkX = FAR, chunkZ = FAR + slot * 8;
        level.getChunk(chunkX, chunkZ);
        h.assertTrue(loaded(h, chunkX, chunkZ), "the launch chunk should be loaded");
        h.assertTrue(!loaded(h, chunkX + 1, chunkZ), "the next chunk east should not be loaded yet");
        return new Vec3(SectionPos.sectionToBlockCoord(chunkX, 8) + 0.5, y, SectionPos.sectionToBlockCoord(chunkZ, 8) + 0.5);
    }

    /** Whether the {@code on-expire} that marks the caster with {@code tag} has run. */
    private static boolean ended(ServerPlayer p, String tag) {
        return StatusData.stacks(p, new Status(tag)) > 0;
    }

    private static int east(Vec3 launch) {
        return SectionPos.blockToSectionCoord((int) Math.floor(launch.x)) + 1;
    }

    private static int zOf(Vec3 launch) {
        return SectionPos.blockToSectionCoord((int) Math.floor(launch.z));
    }

    /** Runes aimed at a far unloaded chunk find nothing, charge only what they charge up front, and leave it unloaded. */
    static void blockRunes(GameTestHelper h) {
        ServerPlayer p = caster(h);
        BlockPos far = BlockPos.containing(p.position().add(0, 0, 320000));
        int chunkX = SectionPos.blockToSectionCoord(far.getX()), chunkZ = SectionPos.blockToSectionCoord(far.getZ());
        h.assertTrue(!loaded(h, chunkX, chunkZ), "the far chunk should start unloaded");
        record Case(String body, double spends) {}
        for (Case c : List.of(
                new Case("(each-block (blocks :at (ahead 320000) :radius 8) (mark caster #reached))", 2),
                new Case("(break-block (ahead 320000))", 0),
                new Case("(toggle-state (ahead 320000))", 0),
                new Case("(use-block (ahead 320000))", 0),
                new Case("(grow (ahead 320000) 6)", 0),
                new Case("(decay (ahead 320000) 6)", 0),
                new Case("(lightning (ahead 320000))", 0),
                new Case("(set-fire (ahead 320000))", 0),
                new Case("(infuse-block (ahead 320000) 10)", 0),
                new Case("(if (is-air (ahead 320000)) (mark caster #reached))", 0.5),
                new Case("(if (block-is (ahead 320000) \"#minecraft:logs\") (mark caster #reached))", 0.5),
                new Case("(if (is-air (spot :at (ahead 320000) :radius 0 :ground)) (mark caster #reached))", 0.5),
                new Case("(vein :from (ahead 320000))", 0),
                new Case("(ray :from (ahead 320000))", 3))) {
            SpellInstance inst = Casting.cast(p, Casting.compile("(spell :mana 50 " + c.body + ")"), null, null);
            h.assertTrue(!loaded(h, chunkX, chunkZ), c.body + " should leave the far chunk unloaded");
            h.assertTrue(Math.abs(inst.mana - (50 - c.spends)) < 1e-6, c.body + " should spend " + c.spends + ", left " + inst.mana);
                h.assertTrue(!ended(p, "#reached"), c.body + " should find nothing there");
        }
        h.succeed();
    }

    /** A projectile set flying at an unloaded chunk flies up to its border and goes out there, running on-expire, without loading it. */
    static void projectile(GameTestHelper h) {
        ServerPlayer p = caster(h);
        Vec3 from = launch(h, 0, h.getLevel().getMaxY() - 8);
        String src = "(projectile (on-expire (mark caster #gone)))";
        Ctx c = ctx(h, p, src, from);
        var shot = new ProjectileObj(Casting.compile(src).body().getFirst(), c, from, new Vec3(1, 0, 0), 4, 400, Motion.FORWARD, 0, 0.1, Vfx.Guise.PLAIN);
        for (int k = 0; k < 8 && !ended(p, "#gone"); k++) shot.tick();
        double border = SectionPos.sectionToBlockCoord(east(from));
        h.assertTrue(ended(p, "#gone"), "it should end at the border, running on-expire; it is at " + shot.position());
        h.assertTrue(shot.position().x < border && shot.position().x > border - 0.01, "it should end just short of x=" + border + ", at " + shot.position());
        h.assertTrue(!loaded(h, east(from), zOf(from)), "the chunk ahead should stay unloaded");
        h.succeed();
    }

    /** With spellsLoadChunks on, the same flight carries on over the border and loads the chunk, as spells always did. */
    static void optionKeepsFlying(GameTestHelper h) {
        ServerPlayer p = caster(h);
        Vec3 from = launch(h, 1, h.getLevel().getMaxY() - 8);
        String src = "(projectile (on-expire (mark caster #gone)))";
        Ctx c = ctx(h, p, src, from);
        var shot = new ProjectileObj(Casting.compile(src).body().getFirst(), c, from, new Vec3(1, 0, 0), 4, 400, Motion.FORWARD, 0, 0.1, Vfx.Guise.PLAIN);
        LogosConfig.SPELLS_LOAD_CHUNKS.set(true);
        try {
            for (int k = 0; k < 8; k++) shot.tick();
        } finally {
            LogosConfig.SPELLS_LOAD_CHUNKS.set(false);
        }
        double border = SectionPos.sectionToBlockCoord(east(from));
        h.assertTrue(!ended(p, "#gone") && shot.position().x > border, "it should fly on past x=" + border + ", at " + shot.position());
        h.assertTrue(loaded(h, east(from), zOf(from)), "the chunk it entered should be loaded");
        h.succeed();
    }

    /** A beam, a ray and a look stop at the border as if they met nothing; a beam coming out of an unloaded chunk goes out. */
    static void beamRayLook(GameTestHelper h) {
        ServerPlayer p = caster(h);
        Vec3 home = p.position();
        Vec3 from = launch(h, 2, h.getLevel().getMaxY() - 8);
        int east = east(from), z = zOf(from);
        double border = SectionPos.sectionToBlockCoord(east);

        String beamSrc = "(beam (on-expire (mark caster #gone)))";
        Ctx c = ctx(h, p, beamSrc, from);
        var beam = new BeamObj(Casting.compile(beamSrc).body().getFirst(), c, 0.5, 64, 200, 5, null, null, Vfx.NONE);
        beam.moveTo(from, 0);
        beam.turn(new Vec3(1, 0, 0));
        beam.tick();
        h.assertTrue(!loaded(h, east, z), "a beam should stop at the border");
        h.assertTrue(!ended(p, "#gone"), "a beam shining up to the border should go on");

        Interp.run(Casting.compile("(ray :range 64)").body(), ctx(h, p, beamSrc, from));
        h.assertTrue(!loaded(h, east, z), "a ray should stop at the border");

        // The look comes from the caster's eyes, so the caster stands there for this one call.
        p.snapTo(from.x, from.y, from.z, -90, 0);
        try {
            // is-air is false where a spell may not look, so this also says the look ended on this side.
            Interp.run(Casting.compile("(if (is-air (look-point 64)) (mark caster #seen))").body(), ctx(h, p, beamSrc, from));
        } finally {
            p.snapTo(home.x, home.y, home.z, 0, 0);
        }
        h.assertTrue(!loaded(h, east, z), "a look should stop at the border");
        h.assertTrue(ended(p, "#seen"), "a look should end short of x=" + border);

        var farBeam = new BeamObj(Casting.compile(beamSrc).body().getFirst(), c, 0.5, 64, 200, 5, null, null, Vfx.NONE);
        farBeam.moveTo(from.add(32000, 0, 0), 0);
        farBeam.tick();
        h.assertTrue(ended(p, "#gone"), "a beam coming out of an unloaded chunk should go out");
        h.succeed();
    }

    /** A vein crawling towards an unloaded chunk ends short of it, as at any other edge, running on-expire. */
    static void vein(GameTestHelper h) {
        ServerPlayer p = caster(h);
        ServerLevel level = h.getLevel();
        Vec3 from = launch(h, 3, level.getMaxY() - 8);
        int chunkX = east(from) - 1, chunkZ = zOf(from), y = (int) Math.floor(from.y) - 1;
        // A floor right up to the border, laid without the neighbour updates that would read the next chunk and load it.
        for (int x = 0; x < 16; x++) {
            level.setBlock(new BlockPos(SectionPos.sectionToBlockCoord(chunkX, x), y, SectionPos.sectionToBlockCoord(chunkZ, 8)), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        String src = "(vein :speed 2 :range 64 (on-expire (mark caster #gone)))";
        Object made = Interp.eval(Casting.compile(src).body().getFirst(), ctx(h, p, src, from));
        h.assertTrue(made instanceof SpellObject, "the vein should form on the stone, got " + made);
        SpellObject vein = (SpellObject) made;
        for (int k = 0; k < 16 && !ended(p, "#gone"); k++) vein.tick();
        h.assertTrue(ended(p, "#gone"), "it should end at the border, running on-expire; it is at " + vein.position());
        h.assertTrue(vein.position().x < SectionPos.sectionToBlockCoord(east(from)), "it should end short of the border, at " + vein.position());
        h.assertTrue(!loaded(h, east(from), zOf(from)), "the chunk ahead should stay unloaded");
        h.succeed();
    }

    /** Blink still goes wherever it is paid to: whoever lands there needs the chunk anyway. */
    static void blinkLands(GameTestHelper h) {
        ServerPlayer p = caster(h);
        Vec3 home = p.position();
        Casting.cast(p, Casting.compile("(spell :mana 2000 (blink (ahead 2000)))"), null, null);
        h.assertTrue(Math.abs(p.getZ() - (home.z + 2000)) < 1e-6, "it should land 2000 blocks on, at " + p.position());
        p.snapTo(home.x, home.y, home.z, 0, 0);
        h.succeed();
    }
}
