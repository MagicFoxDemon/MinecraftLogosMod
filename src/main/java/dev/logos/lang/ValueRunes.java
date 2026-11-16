package dev.logos.lang;

import static dev.logos.lang.Category.*;
import static dev.logos.lang.Runes.*;
import static dev.logos.lang.Type.*;
import static dev.logos.runtime.Interp.*;

import java.util.function.DoubleBinaryOperator;

import dev.logos.runtime.Place;
import dev.logos.runtime.SpellObject;
import dev.logos.runtime.SpellWorld;
import dev.logos.runtime.StatusData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** 数 Values: the numbers and propositions a spell works out. */
final class ValueRunes {
    private ValueRunes() {}

    /** The part of {@code t} past its whole cycles, 0~1 (also for negative t). */
    private static double frac(double t) {
        return t - Math.floor(t);
    }

    private static void fold(String name, DoubleBinaryOperator op, String doc) {
        def(name, VALUE, NUM).rest("x", NUM).doc(doc).exec((n, c) -> {
            double acc = num(n.args.getFirst(), c);
            for (int k = 1; k < n.args.size(); k++) acc = op.applyAsDouble(acc, num(n.args.get(k), c));
            return acc;
        });
    }

    private static void compare(String name, java.util.function.BiPredicate<Double, Double> op) {
        def(name, VALUE, BOOL).p("a", NUM).p("b", NUM).doc("比较")
                .exec((n, c) -> op.test(num(n.arg(0), c), num(n.arg(1), c)));
    }

    private static void entityNum(String name, java.util.function.ToDoubleFunction<LivingEntity> f, String doc) {
        def(name, VALUE, NUM).p("entity", ENTITY).doc(doc)
                .exec((n, c) -> entity(n.arg(0), c) instanceof LivingEntity le ? f.applyAsDouble(le) : 0.0);
    }

    static void values() {
        fold("+", Double::sum, "加");
        fold("*", (a, b) -> a * b, "乘");
        fold("min", Math::min, "最小值");
        fold("max", Math::max, "最大值");
        def("-", VALUE, NUM).p("a", NUM).p("b", NUM).doc("减").exec((n, c) -> num(n.arg(0), c) - num(n.arg(1), c));
        def("/", VALUE, NUM).p("a", NUM).p("b", NUM).doc("除（除以 0 得 0）").exec((n, c) -> {
            double b = num(n.arg(1), c);
            return b == 0 ? 0.0 : num(n.arg(0), c) / b;
        });
        entityNum("hp", LivingEntity::getHealth, "当前生命");
        entityNum("max-hp", LivingEntity::getMaxHealth, "最大生命");
        entityNum("hp-ratio", e -> e.getHealth() / e.getMaxHealth(), "生命比例 0~1");
        entityNum("missing-hp", e -> e.getMaxHealth() - e.getHealth(), "已损失生命");
        def("stacks", VALUE, NUM).p("entity", ENTITY).p("status", STATUS).doc("印记层数 / 燃烧或冻结剩余秒数")
                .exec((n, c) -> StatusData.stacks(entity(n.arg(0), c), status(n.arg(1), c)));
        def("distance", VALUE, NUM).p("a", POS).p("b", POS).doc("两点距离")
                .exec((n, c) -> vec(n.arg(0), c).distanceTo(vec(n.arg(1), c)));
        def("count", VALUE, NUM).p("targets", ENTITIES).doc("实体数量").exec((n, c) -> (double) entities(n.arg(0), c).size());
        def("mana-left", VALUE, NUM).doc("本次法术剩余的魔力").exec((n, c) -> c.inst().mana);
        def("depth", VALUE, NUM).doc("当前递归层数").exec((n, c) -> (double) c.depth());
        def("life-of", VALUE, NUM).p("form", Type.FORM)
                .doc("形之寿：一个形体（魔弹、光柱、炼成体、领域、魔力缠绕、游脉）走过了自己寿命的几分，0 是刚展开，1 是寿终；已经消散的也是 1。"
                        + "形体事件里接 form（此形），形体之后接它的「此形」输出。配合调和、波可让效果随寿命变化。免费")
                .exec((n, c) -> eval(n.arg(0), c) instanceof SpellObject o ? o.progress() : 1.0);
        def("mix", VALUE, NUM).p("a", NUM).p("b", NUM).p("alpha", NUM)
                .doc("调和：按比例 alpha 在 a、b 之间取值，0 是 a，1 是 b，0.5 是正中；小于 0 或大于 1 时沿同一条直线越过两端")
                .exec((n, c) -> {
                    double a = num(n.arg(0), c);
                    return a + (num(n.arg(1), c) - a) * num(n.arg(2), c);
                });
        def("round", VALUE, NUM).p("x", NUM).choice("mode", "nearest", "up", "down")
                .doc("取整：:mode nearest 四舍五入（.5 远离 0），up 向上取整，down 向下取整")
                .exec((n, c) -> {
                    double x = num(n.arg(0), c);
                    return switch (choice(n, "mode")) {
                        case "up" -> Math.ceil(x);
                        case "down" -> Math.floor(x);
                        default -> nearest(x);
                    };
                });
        def("choose", VALUE, ANY).p("index", NUM).rest("value", ANY).flag("wrap")
                .doc("择值：按序号（四舍五入，从 0 数起）取其中一个值，各个值须是同一种（数、位置、实体……），结果也是这一种；"
                        + "只求值被选中的那个。序号超出时取最近的一端，:wrap 则绕回开头循环，如配合 i 轮流取值")
                .exec((n, c) -> {
                    int count = n.args.size() - 1;
                    double x = num(n.arg(0), c);
                    int k = n.flags.contains("wrap") ? branch(x, count, true)
                            : (int) Math.clamp((long) nearest(Double.isNaN(x) ? 0 : x), 0, count - 1);
                    return eval(n.arg(k + 1), c);
                });
        def("clamp", VALUE, NUM).p("x", NUM).kw("min", NUM, "0").kw("max", NUM, "1")
                .doc("约束：把数限制在 min~max 之间（默认 0~1），低于下限取下限，高于上限取上限；两者写反时互换")
                .exec((n, c) -> {
                    double lo = num(kw(n, "min"), c), hi = num(kw(n, "max"), c);
                    return Math.clamp(num(n.arg(0), c), Math.min(lo, hi), Math.max(lo, hi));
                });
        def("wave", VALUE, NUM).p("x", NUM).choice("shape", "sine", "triangle", "saw", "square")
                .kw("period", NUM, "1").kw("amp", NUM, "1").kw("phase", NUM, "0")
                .doc("波：随 x 往复的数，在 -amp~amp 之间。:shape sine 正弦、triangle 三角、saw 锯齿（匀速升起后跌回）、square 方波；"
                        + ":period 每多少 x 重复一次（默认 1），:phase 把波形提前几分之几个周期。x 接循环序号或时间即可做出律动")
                .exec((n, c) -> {
                    double period = num(kw(n, "period"), c);
                    if (period == 0) return 0.0;
                    double t = num(n.arg(0), c) / period + num(kw(n, "phase"), c);
                    // Every shape starts at 0 rising (square at +1) and peaks a quarter cycle in, like sine.
                    double y = switch (choice(n, "shape")) {
                        case "triangle" -> 4 * Math.abs(frac(t - 0.25) - 0.5) - 1;
                        case "saw" -> 2 * frac(t + 0.5) - 1;
                        case "square" -> frac(t) < 0.5 ? 1 : -1;
                        default -> Math.sin(2 * Math.PI * t);
                    };
                    return num(kw(n, "amp"), c) * y;
                });
        def("random", VALUE, NUM).opt("max", NUM, "1").kw("min", NUM, "0").flag("whole")
                .doc("min~max 之间的随机数（默认 0~1，不含 max）。:whole 取整数，两端都可能取到，如 (random 6 :min 1 :whole) 掷骰")
                .exec((n, c) -> {
                    double lo = num(kw(n, "min"), c), hi = num(n.arg(0), c);
                    var rng = c.inst().level.getRandom();
                    if (!n.flags.contains("whole")) return lo + rng.nextDouble() * (hi - lo);
                    long a = (long) Math.ceil(Math.min(lo, hi)), b = (long) Math.floor(Math.max(lo, hi));
                    // No whole number between the bounds: the nearest one to them.
                    if (b < a) return (double) Math.round((lo + hi) / 2);
                    return (double) (a + (long) (rng.nextDouble() * (b - a + 1)));
                });
        compare("<", (a, b) -> a < b);
        compare(">", (a, b) -> a > b);
        compare("<=", (a, b) -> a <= b);
        compare(">=", (a, b) -> a >= b);
        compare("=", (a, b) -> Math.abs(a - b) < 1e-9);
        def("and", VALUE, BOOL).rest("p", BOOL).doc("并且（其中每个条件都成为证明）")
                .exec((n, c) -> n.args.stream().allMatch(a -> bool(a, c)));
        def("or", VALUE, BOOL).rest("p", BOOL).doc("或者（不构成证明）")
                .exec((n, c) -> n.args.stream().anyMatch(a -> bool(a, c)));
        def("not", VALUE, BOOL).p("p", BOOL).doc("非").exec((n, c) -> !bool(n.arg(0), c));
        def("has", VALUE, BOOL).p("entity", ENTITY).p("status", STATUS).doc("是否处于状态 burning / frozen / #印记")
                .exec((n, c) -> StatusData.has(entity(n.arg(0), c), status(n.arg(1), c)));
        def("is", VALUE, BOOL).p("entity", ENTITY).p("filter", FILTER)
                .doc("这个实体是否符合筛选，如 (is target (kind projectiles))、(is target \"#minecraft:undead\")")
                .exec((n, c) -> {
                    Entity e = entity(n.arg(0), c);
                    return e != null && filter(n.arg(1), c).test(c.inst(), e);
                });
        def("block-is", VALUE, BOOL).p("pos", POS).p("filter", FILTER).doc("该位置的方块是否符合筛选：方块 id（minecraft:wheat）或 #标签（#minecraft:logs）")
                .exec((n, c) -> {
                    Place p = place(n.arg(0), c);
                    return SpellWorld.loaded(p.level(), p.pos()) && filter(n.arg(1), c).testBlock(p.level().getBlockState(p.block()));
                });
        def("is-air", VALUE, BOOL).p("pos", POS).doc("该位置是否为空气")
                .exec((n, c) -> {
                    Place p = place(n.arg(0), c);
                    return SpellWorld.loaded(p.level(), p.pos()) && p.level().getBlockState(p.block()).isAir();
                });
        def("block-count", VALUE, NUM).p("blocks", BLOCKS).doc("方块数量").exec((n, c) -> (double) places(n.arg(0), c).size());
        def("alive", VALUE, BOOL).p("entity", ENTITY).doc("实体存在且存活")
                .exec((n, c) -> entity(n.arg(0), c) instanceof LivingEntity le && le.isAlive());
    }
}
