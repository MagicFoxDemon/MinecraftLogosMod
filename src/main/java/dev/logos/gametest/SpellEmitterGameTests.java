package dev.logos.gametest;

import dev.logos.LogosMod;
import dev.logos.block.SpellEmitterBlock;
import dev.logos.block.SpellEmitterBlockEntity;
import dev.logos.graph.EmitterGraphs;
import dev.logos.graph.GraphBuilder;
import dev.logos.graph.GraphCompiler;
import dev.logos.graph.SpecialNodes;
import dev.logos.graph.SpellGraph;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.lang.Compiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** The spell emitter: charging from its own mana, its triggers, casting from inside itself, and editing its page. */
final class SpellEmitterGameTests {
    private SpellEmitterGameTests() {}

    /** Gives 10 mana to the block right in front of the emitter, for 30 injected. */
    private static final String INFUSE_AHEAD = "(spell :name \"渡\" :mana 30 (infuse-block (ahead 1) 10))";

    /** An emitter facing east at {@code at} with the spell on a crystal page and {@code mana} in store; a bare emitter in front to receive. */
    private static SpellEmitterBlockEntity emitter(GameTestHelper h, BlockPos at, SpecialNodes.EmitterMode mode, double mana) {
        BlockState state = LogosMod.SPELL_EMITTER.get().defaultBlockState().setValue(SpellEmitterBlock.FACING, Direction.EAST);
        h.setBlock(at, state);
        h.setBlock(at.east(), state);
        SpellEmitterBlockEntity e = (SpellEmitterBlockEntity) h.getBlockEntity(at, SpellEmitterBlockEntity.class);
        e.setPage(SpellPages.inscribed(PageTier.CRYSTAL, INFUSE_AHEAD, null), null);
        e.edit(e.page(), mode, 1, h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE));
        e.receiveMana(mana);
        return e;
    }

    private static SpellEmitterBlockEntity at(GameTestHelper h, BlockPos pos) {
        return (SpellEmitterBlockEntity) h.getBlockEntity(pos, SpellEmitterBlockEntity.class);
    }

    /**
     * In pulse mode it charges and then waits; a redstone pulse lets the charge go, aimed where it faces,
     * at no more than the spell's injection. The comparator reads its store.
     */
    static void pulseCastsOnce(GameTestHelper h) {
        BlockPos pos = new BlockPos(1, 2, 1);
        SpellEmitterBlockEntity e = emitter(h, pos, SpecialNodes.EmitterMode.PULSE, 200);
        h.assertTrue(h.getLevel().getBlockState(h.absolutePos(pos)).getAnalogOutputSignal(h.getLevel(), h.absolutePos(pos), Direction.WEST) == 3,
                "200 of 1000 mana should read 3 on a comparator");
        h.startSequence()
                .thenIdle(40)
                .thenExecute(() -> {
                    h.assertTrue(Math.abs(e.injected() - 30) < 1e-6, "the charge holds " + e.injected() + ", not 30");
                    h.assertTrue(at(h, pos.east()).mana() == 0, "it cast without a pulse");
                    h.setBlock(pos.north(), Blocks.REDSTONE_BLOCK);
                })
                .thenWaitUntil(() -> h.assertTrue(Math.abs(at(h, pos.east()).mana() - 10) < 1e-6, "the emitter in front got " + at(h, pos.east()).mana()))
                .thenIdle(40)
                .thenExecute(() -> {
                    h.assertTrue(Math.abs(at(h, pos.east()).mana() - 10) < 1e-6, "a held signal cast again in pulse mode: " + at(h, pos.east()).mana());
                    h.assertTrue(Math.abs(e.mana() - 140) < 1e-6, "two charges should leave 140 of 200, not " + e.mana());
                })
                .thenSucceed();
    }

    /** As soon as full, it casts by itself, paying 2 more each time; with 64 mana that is exactly twice. */
    static void fullCastsAndPaysExtra(GameTestHelper h) {
        BlockPos pos = new BlockPos(1, 2, 1);
        SpellEmitterBlockEntity e = emitter(h, pos, SpecialNodes.EmitterMode.FULL, 64);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(Math.abs(at(h, pos.east()).mana() - 20) < 1e-6, "the emitter in front got " + at(h, pos.east()).mana()))
                .thenIdle(20)
                .thenExecute(() -> {
                    h.assertTrue(Math.abs(at(h, pos.east()).mana() - 20) < 1e-6, "it cast more than twice: " + at(h, pos.east()).mana());
                    h.assertTrue(e.mana() < 1e-6 && e.injected() < 1e-6, "it should have spent all 64, holds " + e.mana() + " + " + e.injected());
                })
                .thenSucceed();
    }

    /** Its own spells see through it, out of its centre; everything else still meets a solid block. */
    static void spellsLeaveThroughItself(GameTestHelper h) {
        BlockPos pos = new BlockPos(1, 2, 1);
        h.setBlock(pos, LogosMod.SPELL_EMITTER.get().defaultBlockState().setValue(SpellEmitterBlock.FACING, Direction.EAST));
        h.setBlock(pos.east(3), Blocks.STONE);
        SpellEmitterBlockEntity e = at(h, pos);
        var caster = e.caster(h.getLevel());
        Vec3 centre = h.absolutePos(pos).getCenter();
        h.assertTrue(caster.getEyePosition().distanceTo(centre) < 1e-6, "the caster's eyes are at " + caster.getEyePosition() + ", not the centre");
        h.assertTrue(caster.getLookAngle().distanceTo(new Vec3(1, 0, 0)) < 1e-6, "the caster looks " + caster.getLookAngle() + ", not east");
        Vec3 far = centre.add(4, 0, 0);
        BlockHitResult own = h.getLevel().clip(new ClipContext(centre, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
        h.assertTrue(own.getType() == HitResult.Type.BLOCK && own.getBlockPos().equals(h.absolutePos(pos.east(3))),
                "the emitter's own ray stopped at " + own.getBlockPos() + " instead of the stone");
        BlockHitResult other = h.getLevel().clip(new ClipContext(centre.add(-2, 0, 0), far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()));
        h.assertTrue(other.getBlockPos().equals(h.absolutePos(pos)), "anything else should hit the emitter, hit " + other.getBlockPos());
        h.succeed();
    }

    /**
     * Opened in an emitter, the page's invocation becomes the emitter's node, which compiles as cast by
     * hand; saved, the page gets its invocation back with its intent and immolation as they were.
     */
    static void editSwapsTheInvocation(GameTestHelper h) {
        String src = "(spell :name \"愈\" :mana 30 :immolate false :intent heal (infuse-block (ahead 1) 10))";
        SpellGraph g = GraphBuilder.build(Compiler.compile(src));
        String before = GraphCompiler.compile(g).source();
        EmitterGraphs.Kept kept = EmitterGraphs.toEmitter(g, SpecialNodes.EmitterMode.TIMED, 7);
        SpecialNodes.EmitterRoot node = EmitterGraphs.root(g, SpecialNodes.EmitterRoot.class);
        h.assertTrue(node != null && EmitterGraphs.root(g, SpecialNodes.SpellRoot.class) == null, "the invocation was not replaced");
        h.assertTrue(node.mode() == SpecialNodes.EmitterMode.TIMED && node.interval() == 7, "the node shows " + node.mode() + " every " + node.interval());
        h.assertTrue(!kept.immolate() && kept.intent() == SpecialNodes.IntentChoice.HEAL, "kept aside " + kept);
        GraphCompiler.Result inEmitter = GraphCompiler.compile(g);
        h.assertTrue(inEmitter.ok() && inEmitter.spell().name().equals("愈") && inEmitter.spell().mana() == 30 && !inEmitter.spell().isPassive(),
                "in the emitter it compiles to " + inEmitter.source() + " / " + inEmitter.error());
        EmitterGraphs.toPage(g, kept);
        String after = GraphCompiler.compile(g).source();
        h.assertTrue(before.equals(after), "the saved page reads\n" + after + "\nnot\n" + before);
        h.succeed();
    }
}
