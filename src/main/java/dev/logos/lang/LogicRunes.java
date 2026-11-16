package dev.logos.lang;

import static dev.logos.lang.Category.*;
import static dev.logos.lang.Runes.*;
import static dev.logos.lang.Type.*;
import static dev.logos.runtime.Interp.*;

import dev.logos.LogosConfig;
import dev.logos.runtime.Ctx;
import dev.logos.runtime.Place;
import dev.logos.runtime.SpellRuntime;
import dev.logos.runtime.Targeting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/** 律 Logic: sequence, branches, loops, delays and recursion. */
final class LogicRunes {
    private LogicRunes() {}

    static void logic() {
        def("do", LOGIC, ACTION).body(false).doc("依次执行多个行动")
                .exec((n, c) -> {
                    run(n.body, c);
                    return null;
                });
        def("if", LOGIC, ACTION).p("cond", BOOL).p("then", ACTION).opt("else", ACTION, "(do)").stab(1)
                .doc("条件。成立分支中，命题成为『证明』，可降低 execute 的代价。0.5 魔力")
                .exec((n, c) -> {
                    if (!c.inst().spend(0.5, "if")) return null;
                    if (bool(n.arg(0), c)) eval(n.arg(1), c);
                    else if (n.arg(2) != null) eval(n.arg(2), c);
                    return null;
                });
        def("switch", LOGIC, ACTION).p("index", NUM).rest("branch", ACTION)
                .on("on-other", false, "序号不对应任何分支时").stab(1)
                .doc("择路：按序号（四舍五入，从 0 数起）只走其中一条分支，如 (switch (random 2 :whole) (do …) (do …) (do …))；"
                        + "序号小于 0、超过最后一条或不是数时走 on-other。0.5 魔力")
                .exec((n, c) -> {
                    if (!c.inst().spend(0.5, "switch")) return null;
                    int k = branch(num(n.arg(0), c), n.args.size() - 1, false);
                    if (k < 0) fire(n, "on-other", c);
                    else eval(n.arg(k + 1), c);
                    return null;
                });
        def("each", LOGIC, ACTION).p("targets", ENTITIES).body(true).stab(2)
                .doc("对每个实体执行（生物、玩家、掉落物都可以），内部 target/here 指向它。每个 0.5 魔力")
                .exec((n, c) -> {
                    for (Entity e : anyEntities(n.arg(0), c)) {
                        if (!c.inst().spend(0.5, "each")) return null;
                        run(n.body, c.on(e, Targeting.center(e)));
                    }
                    return null;
                });
        def("each-block", LOGIC, ACTION).p("blocks", BLOCKS).body(false).stab(2)
                .doc("对每个方块执行，内部 here 是那个方块的中心。每个 0.5 魔力")
                .exec((n, c) -> {
                    for (Place p : places(n.arg(0), c)) {
                        if (!c.inst().spend(0.5, "each-block")) return null;
                        run(n.body, c.at(p));
                    }
                    return null;
                });
        def("repeat", LOGIC, ACTION).p("times", NUM).kw("every", NUM, "0").body(false).stab(2)
                .doc("重复 times 次（上限见配置 maxRepeat），可用 :every 秒 间隔；内部 i 为序号。每次 1 魔力。没有 while")
                .exec((n, c) -> {
                    long times = (long) clamp(Math.round(num(n.arg(0), c)), 0, LogosConfig.maxRepeat());
                    int every = (int) Math.round(clamp(num(kw(n, "every"), c), 0, 10) * 20);
                    if (every == 0) {
                        for (long k = 0; k < times && !c.inst().dead; k++) {
                            // Counts as a step even with an empty body, so a huge loop still hits maxOpsPerTick.
                            if (!c.inst().step() || !c.inst().spend(1, "repeat")) return null;
                            run(n.body, c.withIndex(k));
                        }
                    } else if (times > 0) {
                        repeatEvery(n, c, 0, times, every);
                    }
                    return null;
                });
        def("delay", LOGIC, ACTION).p("seconds", NUM).body(false).stab(1).doc("延迟后执行。0.5 魔力")
                .exec((n, c) -> {
                    if (!c.inst().spend(0.5, "delay")) return null;
                    SpellRuntime.schedule(c.inst(), (int) Math.round(clamp(num(n.arg(0), c), 0, 30) * 20), () -> run(n.body, c));
                    return null;
                });
        def("let", LOGIC, ACTION).p("name", TEXT).p("value", ANY).body(false).stab(0.5).doc("(let $名字 值 行动…) 记住一个值")
                .exec((n, c) -> {
                    run(n.body, c.withVar((String) n.arg(0).value, eval(n.arg(1), c)));
                    return null;
                });
        def("recurse", LOGIC, ACTION).stab(12)
                .doc("禁忌：在当前 here/aim/target 上重新执行整个术式。3 魔力，稳定度代价高，层数上限见配置 maxRecursion")
                .exec((n, c) -> {
                    if (c.depth() + 1 > LogosConfig.maxRecursion()) {
                        c.inst().terminate(Component.translatable("logos.spell.end.recursion", LogosConfig.maxRecursion()));
                        return null;
                    }
                    if (!c.inst().spend(3, "recurse")) return null;
                    run(c.inst().spell.body(), c.deeper());
                    return null;
                });
        def("stop", LOGIC, ACTION).doc("立即熄灭整个术式")
                .exec((n, c) -> {
                    c.inst().stop(Component.translatable("logos.spell.end.stopped"));
                    return null;
                });
    }

    /** One iteration of a timed repeat; the next is scheduled only after this one runs, so a huge count never queues up. */
    private static void repeatEvery(Node n, Ctx c, long k, long times, int every) {
        if (c.inst().dead || !c.inst().spend(1, "repeat")) return;
        run(n.body, c.withIndex(k));
        if (k + 1 < times && !c.inst().dead) SpellRuntime.schedule(c.inst(), every, () -> repeatEvery(n, c, k + 1, times, every));
    }
}
