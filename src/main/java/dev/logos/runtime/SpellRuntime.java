package dev.logos.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.logos.LogosMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Server-side scheduler for living spell objects and delayed continuations. */
public final class SpellRuntime {
    /** Very deep recursion (a large maxRecursion) can exhaust the Java stack; the spell ends, the server does not. */
    static final String STACK_OVERFLOW = "法则失稳：递归过深，世界无法承载（Java 栈溢出）";
    /** Ticks before the same failure may log its stack again; and how many failures are remembered for that. */
    private static final long REPORT_EVERY = 1200;
    private static final int REPORTS_KEPT = 256;

    private record Task(SpellInstance inst, long due, Runnable action) {}

    private static final List<SpellObject> OBJECTS = new ArrayList<>();
    private static final List<SpellObject> SPAWNED = new ArrayList<>();
    private static final List<Task> TASKS = new ArrayList<>();
    private static final List<Task> SCHEDULED = new ArrayList<>();
    /** When each failure last logged its stack; see {@link #report}. */
    private static final Map<String, Long> REPORTED = new HashMap<>();
    private static long now;

    private SpellRuntime() {}

    public static long now() {
        return now;
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
        inst.terminate(e instanceof StackOverflowError ? STACK_OVERFLOW : "运行错误: " + e);
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
        inst.hold();
        SCHEDULED.add(new Task(inst, now + Math.max(0, ticks), action));
    }

    public static void tick() {
        now++;
        OBJECTS.addAll(SPAWNED);
        SPAWNED.clear();
        TASKS.addAll(SCHEDULED);
        SCHEDULED.clear();

        for (int i = 0; i < TASKS.size(); i++) {
            Task t = TASKS.get(i);
            if (t.due > now && !t.inst.dead) continue;
            TASKS.remove(i--);
            try {
                if (!t.inst.dead) t.action.run();
            } catch (StackOverflowError | RuntimeException e) {
                crashed(t.inst, e);
            }
            t.inst.release();
        }

        for (SpellObject o : OBJECTS) {
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
        OBJECTS.removeIf(SpellObject::isEnded);
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

    public static void clear() {
        OBJECTS.clear();
        SPAWNED.clear();
        TASKS.clear();
        SCHEDULED.clear();
        REPORTED.clear();
    }
}
