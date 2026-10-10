package dev.logos.runtime;

import java.util.ArrayList;
import java.util.List;

/** Server-side scheduler for living spell objects and delayed continuations. */
public final class SpellRuntime {
    /** Very deep recursion (a large maxRecursion) can exhaust the Java stack; the spell ends, the server does not. */
    static final String STACK_OVERFLOW = "法则失稳：递归过深，世界无法承载（Java 栈溢出）";

    private record Task(SpellInstance inst, long due, Runnable action) {}

    private static final List<SpellObject> OBJECTS = new ArrayList<>();
    private static final List<SpellObject> SPAWNED = new ArrayList<>();
    private static final List<Task> TASKS = new ArrayList<>();
    private static final List<Task> SCHEDULED = new ArrayList<>();
    private static long now;

    private SpellRuntime() {}

    public static long now() {
        return now;
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
            } catch (StackOverflowError e) {
                t.inst.terminate(STACK_OVERFLOW);
            } catch (RuntimeException e) {
                t.inst.terminate("运行错误: " + e);
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
            } catch (StackOverflowError e) {
                o.inst.terminate(STACK_OVERFLOW);
                o.dissolve();
            } catch (RuntimeException e) {
                o.inst.terminate("运行错误: " + e);
                o.dissolve();
            }
        }
        OBJECTS.removeIf(SpellObject::isEnded);
    }

    public static void clear() {
        OBJECTS.clear();
        SPAWNED.clear();
        TASKS.clear();
        SCHEDULED.clear();
    }
}
