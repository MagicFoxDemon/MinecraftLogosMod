package dev.logos.gametest;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.logos.LogosConfig;
import dev.logos.item.SpellBag;
import dev.logos.lang.Compiler;
import dev.logos.lang.Node;
import dev.logos.lang.Spell;
import dev.logos.lang.Status;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Ctx;
import dev.logos.runtime.Limits;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellInstance;
import dev.logos.runtime.SpellObject;
import dev.logos.runtime.SpellRuntime;
import dev.logos.runtime.StatusData;
import dev.logos.runtime.Targeting;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.commands.CommandSource;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The scheduler under load: many delayed tasks at once, the cap on them, the budget of steps all spells share per tick,
 * and what no longer has to be worked out each time a form spawns.
 */
final class SpellBudgetGameTests {
    /**
     * Each runs in a batch of its own: they turn the shared budget down or time the scheduler, which would slow the tests
     * running beside them, or be thrown off by them.
     */
    static final Set<String> ALONE = Set.of("spell_tasks_many_due_at_once", "spell_tasks_cap", "spell_budget_defers_forms", "spell_budget_defers_tasks",
            "spell_budget_forms_and_tasks_take_turns", "spell_budget_events_count");

    /** The budget those tests set: three {@link #HEAVY} runs use it up, whatever else is still about. */
    private static final int BUDGET = 1000;
    private static final int HEAVY = 400;

    private SpellBudgetGameTests() {}

    /** Turns the budget down until the test ends, however it ends. */
    private static void lowerBudget(GameTestHelper h) {
        int old = LogosConfig.MAX_OPS_PER_TICK_GLOBAL.getAsInt();
        LogosConfig.MAX_OPS_PER_TICK_GLOBAL.set(BUDGET);
        h.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {}

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                LogosConfig.MAX_OPS_PER_TICK_GLOBAL.set(old);
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                LogosConfig.MAX_OPS_PER_TICK_GLOBAL.set(old);
            }

            @Override
            public void testAddedForRerun(GameTestInfo original, GameTestInfo copy, GameTestRunner runner) {}
        });
    }

    private static SpellInstance instance(GameTestHelper h, ServerPlayer p, String name) {
        return new SpellInstance(Casting.compile("(spell :name \"" + name + "\" :mana 1 (field))"), p, h.getLevel(), 0, 1);
    }

    /** A form of a spell of its own that takes {@code steps} interpreter steps each tick for {@code life} ticks, as a busy on-tick would. */
    private static final class Worker extends SpellObject {
        private final int steps;
        final SpellInstance spell;
        int ticks;

        Worker(GameTestHelper h, SpellInstance spell, int steps, int life) {
            super(spell.spell.body().getFirst(), new Ctx(spell, h.getLevel(), h.absoluteVec(new Vec3(1.5, 3, 1.5)), new Vec3(0, 0, 1), null, Map.of(), 0, 0),
                    h.absoluteVec(new Vec3(1.5, 3, 1.5)), life);
            this.spell = spell;
            this.steps = steps;
        }

        int life() {
            return life;
        }

        @Override
        public void tick() {
            ticks++;
            for (int k = 0; k < steps; k++) inst.step();
            if (ticks >= life) end();
        }
    }

    private static List<Worker> workers(GameTestHelper h, ServerPlayer p, int count, int steps, int life) {
        List<Worker> out = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            Worker w = new Worker(h, instance(h, p, "budget-worker"), steps, life);
            SpellRuntime.spawn(w);
            out.add(w);
        }
        return out;
    }

    private static int turns(List<Worker> workers) {
        return workers.stream().mapToInt(w -> w.ticks).sum();
    }

    /** Checks after each tick of the scheduler that the workers took between {@code least} and {@code most} turns, and that none got ahead of the rest. */
    private static void watchTurns(GameTestHelper h, List<Worker> workers, int least, int most) {
        long[] tick = {SpellRuntime.now()};
        int[] seen = {0};
        h.onEachTick(() -> {
            if (SpellRuntime.now() == tick[0]) return;
            tick[0] = SpellRuntime.now();
            int all = turns(workers);
            int moved = all - seen[0];
            seen[0] = all;
            int left = workers.stream().mapToInt(w -> w.life() - w.ticks).sum();
            if (left > 0) h.assertTrue(moved >= least && moved <= most, "forms should take " + least + " to " + most + " turns a tick, took " + moved);
            int max = workers.stream().mapToInt(w -> w.ticks).max().orElse(0);
            int min = workers.stream().mapToInt(w -> w.ticks).min().orElse(0);
            h.assertTrue(max - min <= 1, "no form should get a turn ahead of the rest: " + min + " to " + max);
        });
    }

    private static void assertLivedOut(GameTestHelper h, List<Worker> workers) {
        for (Worker w : workers) {
            h.assertTrue(w.ticks == w.life(), "a form should live out its life, got " + w.ticks + " of " + w.life());
            h.assertTrue(!w.spell.dead, "waiting should not extinguish it: " + w.spell.endReason());
        }
    }

    // ---- the queue ---------------------------------------------------------------------------

    /**
     * Rounds of many tasks all due in the same tick, small and big in turn, timing how long each takes to run through.
     * The best of many rounds is compared, so that neither a pause in one nor code not yet warmed up decides it.
     */
    private static final class Burst {
        static final int SMALL = 1000, BIG = 16000, ROUNDS = 24;
        final SpellInstance inst;
        final long[] best = {Long.MAX_VALUE, Long.MAX_VALUE};
        int round, ran, expected;
        boolean inOrder = true;

        Burst(SpellInstance inst) {
            this.inst = inst;
        }

        void next() {
            if (round == ROUNDS) return;
            int r = round++;
            int size = r % 2 == 0 ? SMALL : BIG;
            expected += size;
            int[] nextIndex = {0};
            long[] start = {0};
            for (int i = 0; i < size; i++) {
                int index = i;
                SpellRuntime.schedule(inst, 1, () -> {
                    if (index == 0) start[0] = System.nanoTime();
                    inOrder &= nextIndex[0]++ == index;
                    ran++;
                    if (index < size - 1) return;
                    best[r % 2] = Math.min(best[r % 2], System.nanoTime() - start[0]);
                    next();
                });
            }
        }
    }

    /**
     * Many tasks due at once all run, in order, at a cost that follows their number rather than its square: sixteen times
     * the tasks take about twenty times as long, where removing each from the front of a list took about a hundred times.
     */
    static void manyDueAtOnce(GameTestHelper h) {
        Burst burst = new Burst(instance(h, ConstructGameTests.caster(h), "budget-burst"));
        burst.next();
        h.succeedWhen(() -> {
            h.assertTrue(burst.round == Burst.ROUNDS && burst.ran == burst.expected, "ran " + burst.ran + " of " + burst.expected + " tasks, round " + burst.round);
            h.assertTrue(burst.inOrder, "tasks due in one tick should run in the order they were scheduled");
            h.assertTrue(!burst.inst.dead, "it should fit under the cap: " + burst.inst.endReason());
            h.assertTrue(burst.best[1] < burst.best[0] * 50, Burst.BIG / Burst.SMALL + " times the tasks took " + (double) burst.best[1] / burst.best[0] + " times as long");
        });
    }

    /** Soonest due first, first scheduled first among those due together, on the same ticks as ever; a dead spell's task never runs and a spell stays open for its last. */
    static void tasksInOrder(GameTestHelper h) {
        ServerPlayer p = ConstructGameTests.caster(h);
        SpellInstance inst = instance(h, p, "budget-order");
        SpellInstance dead = instance(h, p, "budget-order-dead");
        long start = SpellRuntime.now();
        List<String> ran = new ArrayList<>();
        SpellRuntime.schedule(inst, 4, () -> ran.add("a@" + (SpellRuntime.now() - start)));
        SpellRuntime.schedule(inst, 2, () -> {
            ran.add("b@" + (SpellRuntime.now() - start));
            SpellRuntime.schedule(inst, 2, () -> ran.add("e@" + (SpellRuntime.now() - start)));
            SpellRuntime.schedule(inst, 0, () -> ran.add("f@" + (SpellRuntime.now() - start)));
        });
        SpellRuntime.schedule(inst, 4, () -> ran.add("c@" + (SpellRuntime.now() - start)));
        SpellRuntime.schedule(inst, 0, () -> ran.add("d@" + (SpellRuntime.now() - start)));
        SpellRuntime.schedule(dead, 1, () -> ran.add("dead"));
        dead.terminate(Component.literal("test over"));
        h.onEachTick(() -> h.assertTrue(inst.finished() == (ran.size() == 6), "the spell should stay open until its last task has run, ran " + ran));
        h.succeedWhen(() -> {
            h.assertTrue(inst.finished() && !inst.dead, "the spell should end on its own, got " + inst.endReason());
            h.assertTrue(ran.equals(List.of("d@1", "b@2", "f@3", "a@4", "c@4", "e@4")), "ran " + ran);
        });
    }

    /** Past the cap a spell goes out as it does over too many forms, and tasks of spells that have gone out make way at once. */
    static void taskCap(GameTestHelper h) {
        ServerPlayer p = ConstructGameTests.caster(h);
        SpellInstance filler = instance(h, p, "budget-cap-filler");
        while (SpellRuntime.pendingTasks() < Limits.MAX_GLOBAL_TASKS) SpellRuntime.schedule(filler, 600, () -> {});
        h.assertTrue(!filler.dead, "up to the cap is allowed: " + filler.endReason());
        SpellInstance over = instance(h, p, "budget-cap-over");
        boolean[] ran = {false, false};
        SpellRuntime.schedule(over, 0, () -> ran[0] = true);
        h.assertTrue(over.dead && "logos.spell.end.world_full_tasks".equals(over.endKey()), "past the cap the spell should go out, got " + over.endReason());
        h.assertTrue(SpellRuntime.pendingTasks() == Limits.MAX_GLOBAL_TASKS, "the refused task should not be queued");

        filler.terminate(Component.literal("test over"));
        SpellInstance after = instance(h, p, "budget-cap-after");
        SpellRuntime.schedule(after, 0, () -> ran[1] = true);
        h.assertTrue(!after.dead, "a dead spell's tasks should make way, got " + after.endReason());
        h.assertTrue(SpellRuntime.pendingTasks() < Limits.MAX_GLOBAL_TASKS / 2, "a dead spell's tasks should be gone, " + SpellRuntime.pendingTasks() + " left");
        h.succeedWhen(() -> {
            h.assertTrue(ran[1], "the task that fits should run");
            h.assertTrue(!ran[0], "the refused task should not run");
        });
    }

    // ---- the budget --------------------------------------------------------------------------

    /** Past the budget forms wait for the next tick in turn, and each still lives out its whole life. */
    static void defersForms(GameTestHelper h) {
        lowerBudget(h);
        List<Worker> workers = workers(h, ConstructGameTests.caster(h), 7, HEAVY, 4);
        boolean[] waited = {false};
        watchTurns(h, workers, 1, 3);
        h.onEachTick(() -> waited[0] |= workers.stream().anyMatch(w -> w.ticks == 0) && turns(workers) > 0);
        h.succeedWhen(() -> {
            assertLivedOut(h, workers);
            h.assertTrue(waited[0], "some forms should have waited while others ran");
        });
    }

    /** Past the budget due tasks wait for the next tick, oldest first; none goes out, and each holds its spell open until it has run. */
    static void defersTasks(GameTestHelper h) {
        lowerBudget(h);
        ServerPlayer p = ConstructGameTests.caster(h);
        List<SpellInstance> spells = new ArrayList<>();
        List<Integer> ran = new ArrayList<>();
        List<Integer> expected = new ArrayList<>();
        for (int k = 0; k < 10; k++) {
            int index = k;
            SpellInstance inst = instance(h, p, "budget-task");
            spells.add(inst);
            expected.add(k);
            SpellRuntime.schedule(inst, 0, () -> {
                ran.add(index);
                for (int s = 0; s < HEAVY; s++) inst.step();
            });
        }
        long[] tick = {SpellRuntime.now()};
        int[] seen = {0};
        h.onEachTick(() -> {
            if (SpellRuntime.now() == tick[0]) return;
            tick[0] = SpellRuntime.now();
            int moved = ran.size() - seen[0];
            seen[0] = ran.size();
            if (moved == 0 && ran.size() == spells.size()) return;
            h.assertTrue(moved >= 1 && moved <= 3, "tasks should resume each tick, up to the budget: ran " + moved);
            for (int k = 0; k < spells.size(); k++) h.assertTrue(spells.get(k).finished() == ran.contains(k), "a waiting task should hold its spell open");
        });
        h.succeedWhen(() -> {
            h.assertTrue(ran.equals(expected), "tasks should run oldest first, ran " + ran);
            for (SpellInstance inst : spells) h.assertTrue(inst.finished() && !inst.dead, "waiting should not extinguish it: " + inst.endReason());
        });
    }

    /** Neither queue starves the other: forms live through a flood of tasks, then tasks fall due and run among a flood of forms. */
    static void takeTurns(GameTestHelper h) {
        lowerBudget(h);
        ServerPlayer p = ConstructGameTests.caster(h);
        int[] flood = {0};
        for (int k = 0; k < 30; k++) {
            SpellInstance inst = instance(h, p, "budget-flood");
            SpellRuntime.schedule(inst, 0, () -> {
                flood[0]++;
                for (int s = 0; s < HEAVY; s++) inst.step();
            });
        }
        List<Worker> few = workers(h, p, 3, HEAVY, 4);
        int[] floodWhenLivedOut = {-1};
        int[] late = {0};
        List<Worker> many = new ArrayList<>();
        h.onEachTick(() -> {
            if (floodWhenLivedOut[0] >= 0 || few.stream().anyMatch(w -> w.ticks < w.life())) return;
            floodWhenLivedOut[0] = flood[0];
            // Now the other way round: forms fill every tick, then tasks fall due among them.
            many.addAll(workers(h, p, 9, HEAVY, 60));
            for (int k = 0; k < 6; k++) {
                SpellInstance inst = instance(h, p, "budget-late");
                SpellRuntime.schedule(inst, 4, () -> {
                    late[0]++;
                    for (int s = 0; s < HEAVY; s++) inst.step();
                });
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(floodWhenLivedOut[0] > 0 && floodWhenLivedOut[0] < 30, "forms should live out their lives in the middle of the flood, " + floodWhenLivedOut[0] + " of 30 tasks had run by then");
            h.assertTrue(flood[0] == 30, "the flood should still finish, ran " + flood[0]);
            h.assertTrue(late[0] == 6, "tasks should get their turn among busy forms, ran " + late[0]);
            for (Worker w : many) {
                h.assertTrue(w.ticks < w.life(), "the tasks should not have had to wait for the forms to end");
                h.assertTrue(!w.spell.dead, "waiting should not extinguish it: " + w.spell.endReason());
            }
            many.forEach(w -> w.spell.terminate(Component.literal("test over")));
        });
    }

    /** Steps taken outside the scheduler run at once and count, and even when they take the whole budget the forms keep moving, in turn. */
    static void eventsCount(GameTestHelper h) {
        lowerBudget(h);
        ServerPlayer p = ConstructGameTests.caster(h);
        SpellInstance[] outside = {instance(h, p, "budget-event"), instance(h, p, "budget-event")};
        List<Worker> workers = workers(h, p, 4, 100, 3);
        watchTurns(h, workers, 0, 1);
        boolean[] counted = {false};
        h.onEachTick(() -> {
            counted[0] |= SpellRuntime.stepsLastTick() >= 1200;
            for (SpellInstance inst : outside) {
                for (int s = 0; s < 600; s++) inst.step();
                h.assertTrue(!inst.dead, "spell code outside the scheduler should run on: " + inst.endReason());
            }
        });
        h.succeedWhen(() -> {
            assertLivedOut(h, workers);
            h.assertTrue(counted[0], "steps outside the scheduler should count, last tick took " + SpellRuntime.stepsLastTick());
        });
    }

    /** At the default budget nothing waits: an ordinary spell's delays, pulses and end land on the ticks they always did, and busy forms all tick every tick. */
    static void ordinarySpells(GameTestHelper h) {
        ServerPlayer p = ConstructGameTests.caster(h);
        long start = SpellRuntime.now();
        SpellInstance inst = Casting.cast(p, Casting.compile("(spell :name \"budget-ordinary\" :mana 200 (repeat 8 (delay 0.25 (mark caster #budget_late)))"
                + " (field :radius 1 :life 1 :every 0.1 (on-tick (mark caster #budget_pulse))))"), null, null);
        h.assertTrue(inst != null && !inst.dead, "the spell should start");
        Status late = new Status("#budget_late"), pulse = new Status("#budget_pulse");
        List<Worker> workers = workers(h, p, 6, HEAVY, 5);
        watchTurns(h, workers, workers.size(), workers.size());
        long[] ended = {-1};
        h.onEachTick(() -> {
            long tick = SpellRuntime.now() - start;
            if (ended[0] < 0 && inst.finished()) ended[0] = tick;
            if (tick >= 1 && tick < 5) h.assertTrue(StatusData.stacks(p, late) == 0, "the delays should not run early, tick " + tick);
            if (tick >= 5 && tick <= 21) h.assertTrue(StatusData.stacks(p, late) == 8, "all the delays should run at once on time, tick " + tick + ": " + StatusData.stacks(p, late));
        });
        h.succeedWhen(() -> {
            h.assertTrue(ended[0] == 21, "the spell should end on tick 21, got " + ended[0]);
            h.assertTrue(inst.endedNaturally(), "it should end on its own, got " + inst.endReason());
            h.assertTrue(StatusData.stacks(p, pulse) == 10, "the field should pulse 10 times, got " + StatusData.stacks(p, pulse));
            assertLivedOut(h, workers);
        });
    }

    // ---- what is worked out once -------------------------------------------------------------

    /** Handlers that count every read of them, as any walk over the tree would. */
    private static final class Counted extends AbstractList<Node> {
        private final List<Node> handlers;
        int reads;

        Counted(List<Node> handlers) {
            this.handlers = handlers;
        }

        @Override
        public Node get(int i) {
            reads++;
            return handlers.get(i);
        }

        @Override
        public int size() {
            reads++;
            return handlers.size();
        }
    }

    /** Casting a spell and spawning its forms walks nothing: what a form's hits do and how a cast looks were settled when it was compiled. */
    static void formsKeepCompiledResults(GameTestHelper h) {
        ServerPlayer p = ConstructGameTests.caster(h);
        Map<String, String> forms = Map.of("projectile", "on-hit", "beam", "on-hit", "field", "on-enter", "vein", "on-hit", "ray :range 4", "on-hit");
        for (var form : forms.entrySet()) {
            // Compiled here rather than through the cache, since its handlers are swapped for ones that count.
            Spell spell = Compiler.compile("(spell :name \"budget-walk\" :mana 200 (" + form.getKey() + " (" + form.getValue() + " (heal target 1))))");
            Node node = spell.body().getFirst();
            Counted handlers = new Counted(node.events.get(form.getValue()));
            node.events.put(form.getValue(), handlers);
            SpellInstance inst = Casting.cast(p, spell, null, null);
            h.assertTrue(inst != null && !inst.dead, form.getKey() + " should start");
            h.assertTrue(form.getKey().startsWith("ray") || inst.objects == 1, form.getKey() + " should have spawned, has " + inst.objects + " bodies");
            h.assertTrue(new SpellBag.Entry(ItemStack.EMPTY, spell, false, ItemStack.EMPTY, 0).element() == spell.element(), "a page should look as its spell does");
            h.assertTrue(handlers.reads == 0, "spawning " + form.getKey() + " should not walk its handlers, read them " + handlers.reads + " times");
            inst.terminate(Component.literal("test over"));
        }
        h.succeed();
    }

    // ---- the rest ----------------------------------------------------------------------------

    /** The numbers the stats command reports are the scheduler's own, and it needs no player to ask it. */
    static void statsCommand(GameTestHelper h) {
        ServerPlayer p = ConstructGameTests.caster(h);
        SpellInstance inst = instance(h, p, "budget-stats");
        SpellRuntime.schedule(inst, 20, () -> {});
        SpellRuntime.spawn(new Worker(h, inst, 0, 20));
        List<String> said = new ArrayList<>();
        CommandSource listener = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                said.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        var server = h.getLevel().getServer();
        try {
            h.assertTrue(server.getCommands().getDispatcher().execute("logos stats", server.createCommandSourceStack().withSource(listener)) == 1, "the command should succeed");
        } catch (CommandSyntaxException e) {
            h.fail("/logos stats should exist: " + e.getMessage());
        }
        String line = String.join("\n", said);
        h.assertTrue(SpellRuntime.pendingTasks() >= 1 && SpellRuntime.liveForms() >= 1, "the task and the form should be counted");
        h.assertTrue(line.contains(SpellRuntime.pendingTasks() + " / " + Limits.MAX_GLOBAL_TASKS), "pending tasks: " + line);
        h.assertTrue(line.contains(SpellRuntime.liveForms() + " / " + Limits.MAX_GLOBAL_OBJECTS), "live forms: " + line);
        h.assertTrue(line.contains(SpellRuntime.stepsLastTick() + " / " + LogosConfig.maxOpsPerTickGlobal()), "steps: " + line);
        inst.terminate(Component.literal("test over"));
        h.succeed();
    }

    /** The filter is asked only about what is in range; what comes back is what always did, nearest first. */
    static void nearAsksDistanceFirst(GameTestHelper h) {
        ServerPlayer p = ConstructGameTests.caster(h);
        SpellInstance inst = instance(h, p, "budget-near");
        Mob far = pig(h, 7.5, 8.5);
        Mob close = pig(h, 4.5, 10);
        // In the box the search starts from, but past its reach.
        pig(h, 8.1, 12.1);
        Vec3 at = h.absoluteVec(new Vec3(4.5, 2.45, 8.5));
        int[] asked = {0};
        List<Entity> found = Targeting.near(inst, h.getLevel(), at, 4, false, e -> {
            asked[0]++;
            return true;
        });
        h.assertTrue(found.equals(List.of(close, far)), "nearest first, got " + found);
        h.assertTrue(asked[0] == 2, "only those in range should be asked about, asked " + asked[0]);
        h.assertTrue(Targeting.near(inst, h.getLevel(), at, 4, false, e -> e != close).equals(List.of(far)), "the filter should still decide");
        inst.affected.add(close.getUUID());
        h.assertTrue(Targeting.near(inst, h.getLevel(), at, 4, true, e -> true).equals(List.of(far)), ":fresh should skip what it has affected");
        h.succeed();
    }

    private static Mob pig(GameTestHelper h, double x, double z) {
        Mob e = h.spawn(EntityType.PIG, new Vec3(x, 2, z));
        e.setNoAi(true);
        return e;
    }

    /** Payments are only written down for a player who will be shown them. */
    static void traceOnlyForReaders(GameTestHelper h) {
        ServerPlayer p = ConstructGameTests.caster(h);
        Spell spell = Casting.compile("(spell :name \"budget-trace\" :mana 1 (field))");
        SpellInstance off = new SpellInstance(spell, p, h.getLevel(), 10, 1);
        off.spend(1, "test");
        h.assertTrue(off.trace.isEmpty(), "a player with the report off should keep no trace, has " + off.trace);
        PlayerMagic.of(p).trace = true;
        SpellInstance on = new SpellInstance(spell, p, h.getLevel(), 10, 1);
        on.spend(1, "test");
        h.assertTrue(on.trace.equals(List.of(10.0, 9.0)), "a player with the report on should keep it, has " + on.trace);
        SpellInstance creature = new SpellInstance(spell, pig(h, 4.5, 6.5), h.getLevel(), 10, 1);
        creature.spend(1, "test");
        h.assertTrue(creature.trace.isEmpty(), "a creature should keep no trace, has " + creature.trace);
        h.succeed();
    }

    /**
     * Without the report, which starts off, a spell that ends as spells do says nothing; one the world broke off says why
     * once on the action bar, never in chat.
     */
    static void reportOffByDefault(GameTestHelper h) {
        h.assertFalse(new PlayerMagic().trace, "a new player should start with the end-of-spell report off");
        ServerPlayer p = ConstructGameTests.caster(h);
        Spell spell = Casting.compile("(spell :name \"budget-quiet\" :mana 1 (field))");
        sent(p);
        new SpellInstance(spell, p, h.getLevel(), 10, 1).terminate(Component.literal("ran dry"), false);
        SpellInstance stopped = new SpellInstance(spell, p, h.getLevel(), 10, 1);
        stopped.stop(Component.literal("stop"));
        List<ClientboundSystemChatPacket> quiet = sent(p);
        h.assertTrue(quiet.isEmpty(), "spells that ran dry or stopped themselves should say nothing, said " + quiet);
        new SpellInstance(spell, p, h.getLevel(), 10, 1).terminate(Component.literal("broke"));
        List<ClientboundSystemChatPacket> told = sent(p);
        h.assertTrue(told.size() == 1 && told.getFirst().overlay() && told.getFirst().content().getString().contains("broke"),
                "a spell the world broke off should say why once on the action bar, said " + told);
        h.succeed();
    }

    /** The messages sent down {@code p}'s mock connection since the last call. */
    private static List<ClientboundSystemChatPacket> sent(ServerPlayer p) {
        EmbeddedChannel channel = (EmbeddedChannel) p.connection.getConnection().channel();
        channel.flushOutbound();
        List<ClientboundSystemChatPacket> out = new ArrayList<>();
        for (Object o : channel.outboundMessages()) if (o instanceof ClientboundSystemChatPacket c) out.add(c);
        channel.outboundMessages().clear();
        return out;
    }
}
