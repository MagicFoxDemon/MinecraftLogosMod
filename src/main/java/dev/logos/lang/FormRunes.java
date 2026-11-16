package dev.logos.lang;

import static dev.logos.lang.Category.*;
// FORM is also a type (🜔, a body a form made): bare FORM is the family, Type.FORM the type.
import static dev.logos.lang.Category.FORM;
import static dev.logos.lang.Runes.*;
import static dev.logos.lang.Type.*;
import static dev.logos.runtime.Interp.*;

import java.util.ArrayList;
import java.util.List;

import dev.logos.runtime.BeamObj;
import dev.logos.runtime.ConstructEntity;
import dev.logos.runtime.ConstructObj;
import dev.logos.runtime.Ctx;
import dev.logos.runtime.FieldObj;
import dev.logos.runtime.Limits;
import dev.logos.runtime.Place;
import dev.logos.runtime.ProjectileObj;
import dev.logos.runtime.SpellInstance;
import dev.logos.runtime.SpellRuntime;
import dev.logos.runtime.SpellWorld;
import dev.logos.runtime.Targeting;
import dev.logos.runtime.VeinObj;
import dev.logos.runtime.Vfx;
import dev.logos.runtime.WrapObj;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 盐 Salt / Form: the bodies a spell takes in the world. */
final class FormRunes {
    private FormRunes() {}

    static void forms() {
        def("projectile", FORM, ACTION).yields(Type.FORM)
                .kw("from", POS, "here").kw("dir", DIR, "aim").kw("speed", NUM, "1.2").kw("life", NUM, "3")
                .kw("motion", Type.MOTION, "forward").kw("pierce", NUM, "0").kw("size", NUM, "0.3")
                .kw("look", LOOK, null).kw("spin", NUM, "0").kw("trail", PARTICLE, null)
                .on("on-hit", true, "命中实体时").on("on-impact", false, "撞上方块时（here 是那个方块）")
                .on("on-tick", false, "每一刻").on("on-expire", false, "消散时（撞墙/寿命结束/穿透耗尽/飞到未加载的区块前）")
                .stab(4).doc("弹丸：在世界中飞行的魔法实体。飞到未加载的区块前会消散，配置 spellsLoadChunks 打开后才会一直飞下去。基础 4 魔力 + 运动符文的代价（蛇行、环扩、弧扫是游脉贴地的运动，接上会报错）。"
                        + ":look &物品 让它显现为那件物品的模型（如 &mace、&fire_charge；方块物品显示为方块），写生物名则显现为那种生物的模型（如 :look zombie），:spin 是每秒翻转的圈数；"
                        + ":trail %粒子 用那种粒子代替元素尾迹（如 %portal、%end_rod）。外观与拖尾不收魔力，也不改变任何行为")
                .exec((n, c) -> {
                    var m = motion(kw(n, "motion"), c);
                    // Written motions are checked when compiled; a vein's worked out while it runs flies straight.
                    if (!m.flies()) m = Motion.FORWARD;
                    if (!c.inst().spend(4 + m.mana, "projectile")) return null;
                    Place from = place(kw(n, "from"), c);
                    Node look = kw(n, "look");
                    Node trail = kw(n, "trail");
                    var obj = new ProjectileObj(n, c.at(from), from.pos(), vec(kw(n, "dir"), c),
                            clamp(num(kw(n, "speed"), c), 0.1, 4), (int) (clamp(num(kw(n, "life"), c), 0.1, 20) * 20), m,
                            (int) clamp(num(kw(n, "pierce"), c), 0, 99), clamp(num(kw(n, "size"), c), 0.1, 3),
                            new Vfx.Guise(look == null ? Vfx.NONE : look.type == CREATURE ? Vfx.creatureLook(text(look, c)) : Vfx.lookId(text(look, c)), clamp(num(kw(n, "spin"), c), -10, 10),
                                    trail == null ? Vfx.NONE : Vfx.particleId(text(trail, c))));
                    SpellRuntime.spawn(obj);
                    Vfx.sound(from.level(), from.pos(), Vfx.sound(n.element), 0.5f, 1.2f);
                    return c.inst().dead ? null : obj;
                });

        def("beam", FORM, ACTION).yields(Type.FORM)
                .kw("from", POS, null).kw("dir", DIR, null).kw("radius", NUM, "0.5").kw("range", NUM, "24")
                .kw("life", NUM, "2").kw("every", NUM, "0.25").kw("trail", PARTICLE, null)
                .on("on-enter", true, "实体进入光柱时（每个实体一次）").on("on-hit", true, "每次脉动，对光柱里的每个实体")
                .on("on-tick", false, "每次脉动（here 是光柱尽头）").on("on-impact", false, "每次脉动，光柱被方块挡住时（here 是那个方块）")
                .on("on-expire", false, "消散时（here 是最后的尽头）")
                .stab(5).doc("光柱：持续存在的一道粗光束，每刻从起点沿方向重新成形，直到被方块挡住。"
                        + "不写 :from/:dir 时起点是施法者的眼睛、方向是施法者此刻的视线，所以会随转身立刻跟着扫动（魔力生命体施放时则一直对准它施法的目标）；"
                        + "写了则每刻重新求值（如 :dir 指向最近的敌人即可追踪）。展开 6 魔力，每次脉动维持 0.3 + 0.05×半径×射程。"
                        + ":trail %粒子 让光柱上飘起那种粒子")
                .exec((n, c) -> {
                    if (!c.inst().spend(6, "beam")) return null;
                    Node trail = kw(n, "trail");
                    var obj = new BeamObj(n, c, clamp(num(kw(n, "radius"), c), 0.1, 4), clamp(num(kw(n, "range"), c), 1, 64),
                            (int) (clamp(num(kw(n, "life"), c), 0.05, 30) * 20), (int) Math.round(clamp(num(kw(n, "every"), c), 0.05, 10) * 20),
                            kw(n, "from"), kw(n, "dir"), trail == null ? Vfx.NONE : Vfx.particleId(text(trail, c)));
                    SpellRuntime.spawn(obj);
                    Vfx.sound(c.level(), c.inst().caster.getEyePosition(), net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE, 0.8f, 1.6f);
                    return c.inst().dead ? null : obj;
                });

        def("construct", FORM, ACTION).yields(Type.FORM)
                .kw("shape", SHAPE, "wall").kw("at", POS, "(ahead 3)").kw("facing", DIR, "aim")
                .kw("width", NUM, "5").kw("height", NUM, "3").kw("depth", NUM, "1").kw("radius", NUM, "3")
                .kw("life", NUM, "10").kw("every", NUM, "1").kw("solid", FILTER, "(kind all)").kw("look", LOOK, null)
                .kw("to", POS, null).kw("speed", NUM, "0.2").flag("snap").flag("one-way").flag("deflect").flag("follow").flag("sigil")
                .on("on-block", true, "挡下实体或弹射物时（target 是被挡下的东西，here 是接触点）")
                .on("on-stand", true, "有实体站上来时（每次站上来触发一次）")
                .on("on-pass", true, "有东西穿过时（没被它挡住的生物进入时、弹射物飞过时，各一次；aim 是穿过的方向）")
                .on("on-arrive", false, "移动到达目的地时（:to 或 move；here 是中心）")
                .on("on-tick", false, "每 :every 秒（here 是中心）").on("on-expire", false, "消散时")
                .stab(5).doc("炼成体：把魔力凝成有形之物——box 方块、wall 墙、plane 平台、disk 圆盾、dome 穹顶。"
                        + ":solid ⊙ 决定挡住谁（默认全部，含施法者），也挡下弹射物、其他法术的弹丸与光柱、以及身后的爆炸；"
                        + "可以站在上面，也能贴着它放方块（放下的方块留在世界里）。:one-way 只挡从正面来的（平台是从上方，穹顶是从外面），"
                        + ":deflect 把弹射物反弹回去，:snap 对齐方块网格，:look &方块 显现为那种方块，:sigil 让圆盾显现为元素的法阵（只对 disk 有效）。"
                        + ":follow 跟着施法者（墙和圆盾随视线转动，其余随身平移）；:to 位置 以 :speed 格/刻 移过去，站在上面的会被一起带走，到达时 on-arrive；"
                        + "事件里 self 就是这个炼成体，可以 (move self 位置) 再走、(halt self) 停下。"
                        + "展开 4 + 0.3×面积（方块按体积），每秒维持 0.2 + 0.02×面积，每挡下一次 0.5。"
                        + "生命值是展开魔力的 2 倍：挡下的弹射物、法术与爆炸按它们本会造成的伤害扣除，扣完即碎；生物推挤不扣。"
                        + "虚空法术的伤害超过剩余生命值时不被挡下，直接穿过")
                .exec((n, c) -> {
                    Place at = place(kw(n, "at"), c);
                    ConstructShape shape = eval(kw(n, "shape"), c) instanceof ConstructShape s ? s : ConstructShape.WALL;
                    Vec3 facing = vec(kw(n, "facing"), c);
                    double w = num(kw(n, "width"), c), h = num(kw(n, "height"), c), d = num(kw(n, "depth"), c), r = num(kw(n, "radius"), c);
                    boolean snap = n.flags.contains("snap");
                    var g = dev.logos.runtime.ConstructGeometry.of(shape, at.pos(), facing, w, h, d, r, snap);
                    if (!c.inst().spend(ConstructObj.cost(g), "construct")) return null;
                    ConstructEntity e = dev.logos.LogosMod.CONSTRUCT.get().create(at.level(), net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
                    if (e == null) return null;
                    Node look = kw(n, "look");
                    BlockState lookBlock = null;
                    if (look != null) {
                        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(text(look, c)));
                        Block b = item.map(Block::byItem).orElse(Blocks.AIR);
                        if (b != Blocks.AIR) lookBlock = b.defaultBlockState();
                    }
                    e.setup(shape, at.pos(), facing, w, h, d, r, snap, n.flags.contains("one-way"), n.element, lookBlock, c.inst().caster.getId(),
                            n.flags.contains("follow") ? c.inst().caster : null);
                    e.sigil(n.flags.contains("sigil") && shape == ConstructShape.DISK);
                    ConstructObj obj = new ConstructObj(n, c.at(at), e, filter(kw(n, "solid"), c), n.flags.contains("deflect"),
                            (int) (clamp(num(kw(n, "life"), c), 0.5, 120) * 20), (int) Math.round(clamp(num(kw(n, "every"), c), 0.05, 10) * 20));
                    SpellRuntime.spawn(obj);
                    if (c.inst().dead) return null; // refused: too many bodies
                    Node to = kw(n, "to");
                    if (to != null) obj.moveTo(place(to, c).pos(), clamp(num(kw(n, "speed"), c), 0.01, 2));
                    at.level().addFreshEntity(e);
                    Vfx.sound(at.level(), g.center, SoundEvents.AMETHYST_BLOCK_RESONATE, 1f, 1.2f);
                    return obj;
                });

        def("field", FORM, ACTION).yields(Type.FORM)
                .kw("at", POS, "here").kw("radius", NUM, "3").kw("life", NUM, "4").kw("every", NUM, "0.5").flag("follow")
                .on("on-tick", false, "每次脉动").on("on-enter", true, "生物进入时（包括施法者自己）").on("on-expire", false, "消散时")
                .stab(5).doc("领域：持续存在的区域。展开 6 魔力，每次脉动维持 0.5。:follow 让它跟随施法者")
                .exec((n, c) -> {
                    if (!c.inst().spend(6, "field")) return null;
                    Place at = place(kw(n, "at"), c);
                    var obj = new FieldObj(n, c.at(at), at.pos(), clamp(num(kw(n, "radius"), c), 0.5, 16),
                            (int) (clamp(num(kw(n, "life"), c), 0.05, 30) * 20), (int) Math.round(clamp(num(kw(n, "every"), c), 0.05, 10) * 20),
                            n.flags.contains("follow"));
                    SpellRuntime.spawn(obj);
                    return c.inst().dead ? null : obj;
                });

        def("imbue", FORM, ACTION).yields(Type.FORM).opt("target", ENTITY, "caster").kw("life", NUM, "10").kw("every", NUM, "0.5")
                .on("on-hit", true, "这只手命中时：蓄力满九成的近战一击（生物每一击都算），以及这只手射出的箭（弓要拉满）、掷出的三叉戟命中时（here 是命中点）")
                .on("on-swing", false, "这只手挥动时：玩家蓄力满九成的挥击，打中打空都算；生物在出手命中时（here 是眼睛，aim 是视线）")
                .on("on-kill", true, "命中的这一击（连同 on-hit 引发的效果）击杀了目标时")
                .on("on-tick", false, "每次脉动（here 是持有者）").on("on-expire", false, "消散时（到期、被新的缠绕取代、持有者死去或离开这个世界）")
                .stab(4).doc("魔力缠绕：把一段术式缠在目标的主手上（不连目标时缠在施法者自己手上）。缠在手上而不是物品上：拿着什么就缠在什么上，换了武器也跟着走，空手时缠在拳头上；"
                        + "这只手的攻击触发它的事件，事件里 self 是被缠的那个生物，伤害和效果都算施法者的。"
                        + "每只手同时只有一段缠绕，新的取代旧的。展开 6 魔力，每次脉动维持 0.5（:every 默认 0.5 秒，缠 30 秒约 36），"
                        + "事件里的符文触发时照常从这道术式的魔力里扣，魔力耗尽缠绕即散")
                .exec((n, c) -> {
                    List<LivingEntity> ts = living(n.arg(0), c);
                    if (ts.isEmpty()) return null;
                    LivingEntity t = ts.get(0);
                    if (!c.inst().spend(WrapObj.DEPLOY, "imbue")) return null;
                    var obj = new WrapObj(n, c, t, (int) (clamp(num(kw(n, "life"), c), 0.05, 3600) * 20),
                            (int) Math.round(clamp(num(kw(n, "every"), c), 0.05, 10) * 20));
                    SpellRuntime.spawn(obj);
                    if (c.inst().dead) return null;
                    obj.wear();
                    c.inst().affected.add(t.getUUID());
                    Vfx.burst(lv(t), Targeting.center(t), n.element, 12);
                    return obj;
                });

        def("vein", FORM, ACTION).yields(Type.FORM)
                .kw("from", POS, "here").kw("dir", DIR, "aim").kw("motion", Type.MOTION, "forward").kw("spread", NUM, "360").kw("radius", NUM, "3")
                .kw("speed", NUM, "0.6").kw("range", NUM, "16").kw("width", NUM, "1").kw("height", NUM, "1").kw("pierce", NUM, "0").kw("linger", NUM, "0").flag("ccw").flag("climb")
                .on("on-hit", true, "碰到实体时（留下形体时，碰到留下的路径也算）").on("on-step", false, "每走上一块新的方块，起步那块也算；爬墙时墙面、天花板上的方块也算（here 是那块方块）")
                .on("on-impact", false, "撞上翻不过去的墙时（here 是那个方块）；爬墙时不会撞墙")
                .on("on-expire", false, "消散时（走完射程、寿命结束、撞墙、穿透耗尽、地面断开或到了未加载的区块前；留下形体时，等留下的路径全部散去）")
                .stab(4).doc("游脉：贴着地面往前爬的一道脉络——藤蔓生长、地火蔓延、剑气贴地、冰刺破土。"
                        + "从 :from 往下最多 6 格找到地面起步（找不到则不成形，也不花魔力），沿 :dir 的水平方向前进，能迈上 1 格高的台阶、顺着地面往下最多 4 格，"
                        + "在液体表面也能走；撞上更高的墙时 on-impact，地面断开超过 4 格时消散。"
                        + ":climb 爬墙：贴着任何方块表面爬，撞上墙就爬上墙，墙顶接着翻过去，能沿着天花板走，走到屋檐、悬崖边就绕过边沿往下爬，不会掉下去也不会撞墙；"
                        + "离开地面时笔直前进，回到地面后才按运动拐弯、摆动、追踪或沿弧线走。"
                        + ":linger 留下形体：爬过的路径保留这么多秒（0 不保留，最多 30 秒），期间碰到它的实体就像被头部碰到一样触发 on-hit（每个实体仍只碰一次，不消耗 :pierce）；"
                        + "路径从最早爬过的地方开始散去，全部散去后游脉才消散。"
                        + ":motion 运动：与魔弹共用 forward 直行、homing 追踪（拐向最近的敌人）、wander 游走（随机拐弯，偏离原方向不超过 60°）；"
                        + "游脉独有 snake 蛇行（左右摆动着前进）、ring 环扩（从起点向四周同时扩散，:spread 度以内，默认 360 是整圈，以 :dir 为中线）、"
                        + "sweep 弧扫（以 :from 为圆心、:radius 为半径，沿 :spread 度的弧扫过去，以 :dir 为中线，从上往下看默认顺时针，即从施法者左边扫到右边，:ccw 逆时针；扫完整段弧为止，不看 :range）。"
                        + "抛物、环绕、折返离不开空中，接上会报错。"
                        + ":width 宽、:height 高是碰到实体的范围；每个实体只碰一次，:pierce 是还能穿过几个。"
                        + "展开 4 + 运动的代价（直行 0、游走 1、蛇行 1、弧扫 1、环扩 2、追踪 3）+ 0.05×宽×高×爬过的长度："
                        + "一般是射程；环扩是射程×π×角度/360（不低于射程）；弧扫是弧长，半径×角度（弧度）；"
                        + "留下形体另加 0.02×宽×高×同一长度×保留秒数")
                .exec((n, c) -> {
                    Place from = place(kw(n, "from"), c);
                    // Written motions are checked when compiled; one worked out while it runs and unfit for the ground goes straight on.
                    Motion motion = motion(kw(n, "motion"), c);
                    if (!motion.crawls()) motion = Motion.FORWARD;
                    Vec3 dir = vec(kw(n, "dir"), c);
                    // Aimed straight up or down, it sets off the way the caster faces.
                    if (dir.x * dir.x + dir.z * dir.z < 1e-6) dir = Vec3.directionFromRotation(0, c.inst().caster.getYRot());
                    var arc = new VeinObj.Arc(from.pos(), clamp(num(kw(n, "spread"), c), 1, 360), clamp(num(kw(n, "radius"), c), 0.5, 32),
                            n.flags.contains("ccw"));
                    // A sweep starts on its arc, the rest where they are cast.
                    Vec3 start = motion == Motion.SWEEP ? VeinObj.sweepStart(dir, arc) : from.pos();
                    Vec3 ground = VeinObj.groundBelow(from.level(), start, c.inst().caster);
                    if (ground == null) return null;
                    double speed = clamp(num(kw(n, "speed"), c), 0.1, 2), range = clamp(num(kw(n, "range"), c), 1, 64);
                    double width = clamp(num(kw(n, "width"), c), 0.25, 4), height = clamp(num(kw(n, "height"), c), 0.25, 4);
                    double linger = clamp(num(kw(n, "linger"), c), 0, VeinObj.LINGER_MAX);
                    if (!c.inst().spend(VeinObj.cost(motion, range, width, height, arc.spread(), arc.radius(), linger), "vein")) return null;
                    var obj = new VeinObj(n, c.at(from.moved(ground)), ground, dir, motion, speed, range, width, height,
                            (int) clamp(num(kw(n, "pierce"), c), 0, 99), arc);
                    if (n.flags.contains("climb")) obj.climb();
                    obj.linger((int) Math.round(linger * 20));
                    SpellRuntime.spawn(obj);
                    Vfx.sound(from.level(), ground, Vfx.sound(n.element), 0.5f, 0.8f);
                    return c.inst().dead ? null : obj;
                });

        def("ray", FORM, ACTION)
                .kw("from", POS, "here").kw("dir", DIR, "aim").kw("to", ENTITY, null).kw("range", NUM, "16").kw("pierce", NUM, "0")
                .on("on-hit", true, "射线命中实体时").on("on-impact", false, "射线被方块挡住时（here 是那个方块）")
                .on("on-expire", false, "射线终点")
                .stab(3).doc("射线：瞬间成立的一条线。:to 指定实体时直接连向它（若为空则不成立）。3 魔力")
                .exec(FormRunes::ray);

        def("split", FORM, ACTION).p("count", NUM).kw("spread", NUM, "30").body(false)
                .stab(3).doc("分裂：把内部的形体以扇形复制 count 份（旋转 aim）。每份 2 魔力。可作用于任何形体")
                .exec((n, c) -> {
                    int count = (int) clamp(Math.round(num(n.arg(0), c)), 1, Limits.MAX_SPLIT);
                    double spread = Math.toRadians(clamp(num(kw(n, "spread"), c), 0, 360));
                    for (int k = 0; k < count; k++) {
                        if (!c.inst().spend(2, "split")) return null;
                        double off = count == 1 ? 0 : -spread / 2 + spread * k / (count - 1);
                        if (spread >= Math.PI * 2 - 1e-3) off = Math.PI * 2 * k / count;
                        run(n.body, c.aiming(c.aim().yRot((float) off)));
                    }
                    return null;
                });
    }

    private static Object ray(Node n, Ctx c) {
        SpellInstance inst = c.inst();
        Node toNode = kw(n, "to");
        Entity to = toNode != null ? entity(toNode, c) : null;
        if (toNode != null && to == null) return null; // nothing to connect to: the ray does not form
        if (!inst.spend(3, "ray")) return null;
        Place origin = place(kw(n, "from"), c);
        ServerLevel level = origin.level();
        Vec3 from = origin.pos();
        Vec3 dir;
        Vec3 end;
        BlockPos blocked = null;
        List<Entity> hits = new ArrayList<>();
        if (to != null) {
            end = Targeting.center(to);
            dir = end.subtract(from).normalize();
            hits.add(to);
        } else {
            dir = vec(kw(n, "dir"), c).normalize();
            end = from.add(dir.scale(clamp(num(kw(n, "range"), c), 1, 64)));
            // A chunk nothing has loaded cuts it short, without an impact.
            end = SpellWorld.reach(level, from, end);
            BlockHitResult bh = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, inst.caster));
            if (bh.getType() != HitResult.Type.MISS) {
                end = bh.getLocation();
                blocked = bh.getBlockPos();
            }
            Vec3 a = from, b = end;
            int pierce = (int) clamp(num(kw(n, "pierce"), c), 0, 99);
            List<Entity> along = level.getEntities(inst.caster, new AABB(a, b).inflate(1), e -> Targeting.strikes(inst, e)
                    && e.getBoundingBox().inflate(0.3).clip(a, b).isPresent());
            along.sort((x, y) -> Double.compare(x.distanceToSqr(a), y.distanceToSqr(a)));
            for (Entity e : along) {
                if (hits.size() > pierce) break;
                hits.add(e);
            }
            if (!hits.isEmpty() && hits.size() > pierce) {
                end = Targeting.center(hits.getLast());
                blocked = null; // stopped by a creature before reaching the block
            }
        }
        // Left from the caster's eyes, it is drawn from their cast point (a wand's head); only the picture moves.
        boolean fromEyes = inst.caster.level() == level && from.distanceToSqr(inst.caster.getEyePosition()) < 1e-4;
        // A ray that ends on a creature (its :to, or the first it could not pierce) ends at that creature's centre.
        Vfx.RayEnd how = blocked != null ? Vfx.RayEnd.BLOCK
                : !hits.isEmpty() && end.equals(Targeting.center(hits.getLast())) ? Vfx.RayEnd.CREATURE : Vfx.RayEnd.OPEN;
        Vfx.ray(level, from, end, n.element, fromEyes ? inst.caster.getId() : Vfx.NONE, how);
        Vfx.sound(level, end, Vfx.sound(n.element), 0.4f, 1.6f);
        Ctx base = c.at(origin);
        for (Entity e : hits) {
            if (inst.dead) return null;
            fire(n, "on-hit", base.on(e, Targeting.center(e)).aiming(dir));
        }
        if (blocked != null) fire(n, "on-impact", base.at(Place.center(level, blocked)).aiming(dir));
        fire(n, "on-expire", base.at(end).aiming(dir));
        return null;
    }
}
