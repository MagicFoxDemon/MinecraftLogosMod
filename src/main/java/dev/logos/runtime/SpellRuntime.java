package dev.logos.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Server-side scheduler for living spell objects and delayed continuations. */
public final class SpellRuntime {
    /**
     * Very deep recursion (a large maxRecursion) can exhaust the Java stack; the spell ends, the server does not. This
     * and {@link #CRASHED} are what its caster reads; the stack goes to the log.
     */
    public static final String STACK_OVERFLOW = "法则失稳：递归过深，世界承载不住";
    /** Why a spell went out when its own code crashed some other way. */
    public static final String CRASHED = "法则失稳：术式运转时出了意外";
    /** Ticks before the same failure may log its stack again; and how many failures are remembered for that. */
    private static final long REPORT_EVERY = 1200;
    private static final int REPORTS_KEPT = 256;

    /** A delayed continuation. Soonest due goes first; of those due the same tick, the first scheduled. */
    private record Task(SpellInstance inst, long due, long order, Runnable action) implements Comparable<Task> {
        @Override
        public int compareTo(Task o) {
            return due != o.due ? Long.compare(due, o.due) : Long.compare(order, o.order);
        }
    }

    private static final List<SpellObject> OBJECTS = new ArrayList<>();
    private static final List<SpellObject> SPAWNED = new ArrayList<>();
    /** A dead spell's tasks wait in here until they come up. */
    private static final PriorityQueue<Task> TASKS = new PriorityQueue<>();
    private static final List<Task> SCHEDULED = new ArrayList<>();
    /** When each failure last logged its stack; see {@link #report}. */
    private static final Map<String, Long> REPORTED = new HashMap<>();
    private static long now;
    private static long nextOrder;
    /** Interpreter steps taken since the last {@link #tick} ended, events included; and how many that was for the tick before. */
    private static long steps, stepsLastTick;
    /** Whether forms go before tasks this tick: they did not get to run last tick, and tasks did. */
    private static boolean formsFirst;

    private SpellRuntime() {}

    public static long now() {
        return now;
    }

    /** One interpreter step of any spell, for the budget all of them share per tick ({@code maxOpsPerTickGlobal}). */
    static void countStep() {
        steps++;
    }

    /** Delayed tasks waiting, including any a dead spell left behind that have not come up yet. */
    public static int pendingTasks() {
        return TASKS.size() + SCHEDULED.size();
    }

    public static int liveForms() {
        return OBJECTS.size() + SPAWNED.size();
    }

    /** The interpreter steps of the last tick that ended; a command runs before this tick's are in. */
    public static long stepsLastTick() {
        return stepsLastTick;
    }

    /**
     * Runs spell code that may be called from anywhere (an event, a deferred task, another spell): a crash in it
     * extinguishes {@code inst} and is logged, instead of reaching the caller, which may be the server's tick.
     */
    public static void guard(SpellInstance inst, Runnable body) {
        try {
            body.run();
        } catch (StackOverflowError | RuntimeException e) {
            crashed(inst, e);
        }
    }

    private static void crashed(SpellInstance inst, Throwable e) {
        report("Spell " + inst.spell.name(), e);
        inst.terminate(e instanceof StackOverflowError ? STACK_OVERFLOW : CRASHED);
    }

    /** Logs {@code e}'s stack, but only once in a while for {@code what}, so what keeps failing does not fill the log. */
    public static void report(String what, Throwable e) {
        String key = what + " " + e.getClass().getName();
        Long last = REPORTED.get(key);
        if (last != null && now - last < REPORT_EVERY) return;
        if (REPORTED.size() >= REPORTS_KEPT) REPORTED.clear();
        REPORTED.put(key, now);
        LogosMod.LOGGER.error("{} crashed", what, e);
    }

    public static void spawn(SpellObject obj) {
        SpellInstance inst = obj.inst;
        if (inst.objects >= Limits.MAX_OBJECTS_PER_SPELL) {
            inst.terminate("法则失稳：同时存在的形体超过 " + Limits.MAX_OBJECTS_PER_SPELL + " 个");
            return;
        }
        if (OBJECTS.size() + SPAWNED.size() >= Limits.MAX_GLOBAL_OBJECTS) {
            inst.terminate("世界的法则已饱和（全局形体上限）");
            return;
        }
        inst.objects++;
        inst.hold();
        SPAWNED.add(obj);
    }

    public static void schedule(SpellInstance inst, int ticks, Runnable action) {
        if (pendingTasks() >= Limits.MAX_GLOBAL_TASKS) {
            TASKS.removeIf(SpellRuntime::dropIfDead);
            SCHEDULED.removeIf(SpellRuntime::dropIfDead);
        }
        if (pendingTasks() >= Limits.MAX_GLOBAL_TASKS) {
            inst.terminate("世界的法则已饱和（全局任务上限）");
            return;
        }
        inst.hold();
        SCHEDULED.add(new Task(inst, now + Math.max(0, ticks), nextOrder++, action));
    }

    private static boolean dropIfDead(Task t) {
        if (!t.inst.dead) return false;
        t.inst.release();
        return true;
    }

    public static void tick() {
        now++;
        OBJECTS.addAll(SPAWNED);
        SPAWNED.clear();
        TASKS.addAll(SCHEDULED);
        SCHEDULED.clear();

        // Events have taken their steps already and cannot wait; however many, the queues still get to move a little.
        long limit = Math.max(LogosConfig.maxOpsPerTickGlobal(), steps + 1);
        boolean tasksDone, formsDone;
        if (formsFirst) {
            formsDone = tickForms(limit);
            tasksDone = runTasks(limit);
        } else {
            tasksDone = runTasks(limit);
            formsDone = tickForms(limit);
        }
        // What had to wait goes first, or in turn when both did, so that neither queue can starve the other.
        formsFirst = tasksDone ? !formsDone : !formsDone && !formsFirst;
        stepsLastTick = steps;
        steps = 0;
    }

    /** Runs what is due, oldest first, until the tick's steps run out. Returns whether it ran all of it. */
    private static boolean runTasks(long limit) {
        while (!TASKS.isEmpty() && TASKS.peek().due <= now) {
            Task t = TASKS.peek();
            if (!t.inst.dead && steps >= limit) return false;
            TASKS.poll();
            try {
                if (!t.inst.dead) t.action.run();
            } catch (StackOverflowError | RuntimeException e) {
                crashed(t.inst, e);
            }
            t.inst.release();
        }
        return true;
    }

    /** Ticks the forms in turn until the tick's steps run out; the ones not reached start the next tick. Returns whether it reached them all. */
    private static boolean tickForms(long limit) {
        int reached = 0;
        for (SpellObject o : OBJECTS) {
            if (!o.inst.dead && steps >= limit) break;
            reached++;
            try {
                if (o.inst.dead) o.dissolve();
                else {
                    o.inst.touch(o.level, o.pos);
                    o.tick();
                }
            } catch (StackOverflowError | RuntimeException e) {
                crashed(o.inst, e);
                o.dissolve();
            }
        }
        boolean all = reached == OBJECTS.size();
        if (!all) Collections.rotate(OBJECTS, -reached);
        OBJECTS.removeIf(SpellObject::isEnded);
        return all;
    }

    /**
     * The running spells with a body in {@code level} within {@code radius} of {@code at}, each once, nearest body first:
     * what 万咒皆终 (end-all) may reach.
     */
    public static List<SpellInstance> spellsNear(ServerLevel level, Vec3 at, double radius) {
        List<SpellObject> bodies = new ArrayList<>(OBJECTS);
        bodies.addAll(SPAWNED);
        bodies.removeIf(o -> o.isEnded() || o.inst.dead || o.level != level || o.position().distanceTo(at) > radius);
        bodies.sort(java.util.Comparator.comparingDouble(o -> o.position().distanceToSqr(at)));
        java.util.LinkedHashSet<SpellInstance> spells = new java.util.LinkedHashSet<>();
        for (SpellObject o : bodies) spells.add(o.inst);
        return List.copyOf(spells);
    }

    /**
     * The server is stopping: every spell ends where it stands, silently, and nothing it held stays behind. It may run
     * with the worlds already closed (after a crash), so its spells settle nothing there; see {@link SpellInstance#abandon}.
     */
    public static void clear() {
        List<SpellObject> bodies = new ArrayList<>(OBJECTS);
        bodies.addAll(SPAWNED);
        OBJECTS.clear();
        SPAWNED.clear();
        for (SpellObject o : bodies) {
            o.inst.abandon();
            o.dissolve();
        }
        TASKS.clear();
        SCHEDULED.clear();
        REPORTED.clear();
        WrapObj.clear();
        ManaWrap.clear();
        SpellCasters.clear();
    }
}
