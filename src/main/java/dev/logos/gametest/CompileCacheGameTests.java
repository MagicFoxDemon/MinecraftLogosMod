package dev.logos.gametest;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import dev.logos.LogosMod;
import dev.logos.block.SpellEmitterBlock;
import dev.logos.block.SpellEmitterBlockEntity;
import dev.logos.graph.SpecialNodes;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.lang.Compiler;
import dev.logos.lang.SpellCache;
import dev.logos.runtime.Casting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;

/** How the spell emitter reads its page: compiled once and kept, whether or not it compiled. */
final class CompileCacheGameTests {
    private CompileCacheGameTests() {}

    private static final String GOOD = "(spell :name \"渡\" :mana 30 (infuse-block (ahead 1) 10))";
    /**
     * Names a creature nothing else asks after, so counting the compiler's questions about it counts how
     * often this one page is compiled, whatever the other tests in the batch compile meanwhile.
     */
    private static final String CREATURE = "logos_test:compile_probe";
    private static final String BAD = "(spell :name \"探\" :mana 30 (summon " + CREATURE + "))";

    private static SpellEmitterBlockEntity place(GameTestHelper h, BlockPos at) {
        BlockState state = LogosMod.SPELL_EMITTER.get().defaultBlockState().setValue(SpellEmitterBlock.FACING, Direction.EAST);
        h.setBlock(at, state);
        return (SpellEmitterBlockEntity) h.getBlockEntity(at, SpellEmitterBlockEntity.class);
    }

    private static ItemStack page(String source) {
        return SpellPages.inscribed(PageTier.CRYSTAL, source, null);
    }

    /**
     * A page that does not compile is compiled once, however long it sits there, even after the cache
     * has forgotten it; a reload makes the emitter look again. Beside it, a good page casts as usual,
     * which also shows the emitters were ticking all along.
     */
    static void badPageCompilesOnce(GameTestHelper h) {
        Predicate<String> real = Compiler.creatureExists;
        AtomicInteger compiles = new AtomicInteger();
        AtomicBoolean exists = new AtomicBoolean(false);
        Compiler.creatureExists = id -> {
            if (!id.equals(CREATURE)) return real.test(id);
            compiles.incrementAndGet();
            return exists.get();
        };

        SpellEmitterBlockEntity bad = place(h, new BlockPos(1, 2, 1));
        bad.setPage(page(BAD), null);
        bad.receiveMana(200);

        BlockPos goodPos = new BlockPos(1, 2, 3);
        SpellEmitterBlockEntity good = place(h, goodPos);
        SpellEmitterBlockEntity front = place(h, goodPos.east());
        good.setPage(page(GOOD), null);
        good.edit(good.page(), SpecialNodes.EmitterMode.FULL, 1, h.makeMockPlayer(GameType.CREATIVE));
        good.receiveMana(200);

        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(front.mana() > 0, "the good page never cast"))
                .thenExecute(() -> {
                    h.assertTrue(bad.spell() == null, "a page that does not compile has no spell");
                    h.assertTrue(compiles.get() == 1, "the bad page compiled " + compiles.get() + " times");
                    // Push the failure out of the cache: from here on only the emitter's own memory can spare the compiler.
                    for (int i = 0; i <= SpellCache.MAX_ENTRIES; i++) {
                        Casting.compile("(spell :name \"flood" + i + "\" :mana 5 (heal caster 1))");
                    }
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    h.assertTrue(compiles.get() == 1, "with the cache flooded, the bad page compiled " + compiles.get() + " times");
                    exists.set(true);
                    h.assertTrue(bad.spell() == null, "nothing has told the emitter to look again");
                    Casting.invalidate();
                    h.assertTrue(bad.spell() != null, "after a reload the page compiles now");
                    h.assertTrue(compiles.get() == 2, "the reload should have compiled it once more, not " + (compiles.get() - 1) + " times");
                    Compiler.creatureExists = real;
                })
                .thenSucceed();
    }

    /** What it keeps follows the page: a new page, no page, and a page loaded from a save are each read afresh. */
    static void keptSpellFollowsThePage(GameTestHelper h) {
        SpellEmitterBlockEntity e = place(h, new BlockPos(1, 2, 1));
        e.setPage(page("(spell :name \"坏\" (no-such-rune))"), null);
        h.assertTrue(e.spell() == null, "the bad page has no spell");
        e.setPage(page(GOOD), null);
        h.assertTrue(e.spell() != null && e.spell().name().equals("渡"), "a good page put in afterwards should be read, got " + e.spell());
        h.assertTrue(e.target() == 30, "and charged for: " + e.target());

        var registries = h.getLevel().registryAccess();
        var saved = e.saveCustomOnly(registries);
        e.setPage(ItemStack.EMPTY, null);
        h.assertTrue(e.spell() == null && e.target() == 0, "with the page out there is nothing to cast");
        e.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, registries, saved));
        h.assertTrue(e.spell() != null && e.spell().name().equals("渡"), "loading should bring the page's spell back, got " + e.spell());

        e.setPage(page("(spell :name \"被动\" :trigger hurt :mana 5 (heal caster 1))"), null);
        h.assertTrue(e.spell() == null, "an emitter does not cast a passive spell");
        h.succeed();
    }
}
