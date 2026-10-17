package dev.logos.lang;

import static dev.logos.lang.Category.*;
import static dev.logos.lang.Type.*;
import static dev.logos.runtime.Interp.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleBinaryOperator;

import dev.logos.LogosConfig;
import dev.logos.runtime.AreaWeatherObj;
import dev.logos.runtime.BeamObj;
import dev.logos.runtime.ConstructEntity;
import dev.logos.runtime.ConstructObj;
import dev.logos.runtime.Ctx;
import dev.logos.runtime.Effects;
import dev.logos.runtime.FieldObj;
import dev.logos.runtime.Group;
import dev.logos.runtime.Filter;
import dev.logos.runtime.Lightning;
import dev.logos.runtime.Limits;
import dev.logos.runtime.Place;
import dev.logos.runtime.ProjectileObj;
import dev.logos.runtime.SpellInstance;
import dev.logos.runtime.SpellRuntime;
import dev.logos.runtime.StatusData;
import dev.logos.runtime.Targeting;
import dev.logos.runtime.TimeSkipObj;
import dev.logos.runtime.TimeStop;
import dev.logos.runtime.Vfx;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The rune lexicon. Deliberately orthogonal: there is no "fireball" or "chain lightning" here,
 * only forms, motions, selectors, effects, logic and values that players compose.
 */
public final class Runes {
    private static final Map<String, Rune> RUNES = new LinkedHashMap<>();

    private Runes() {}

    public static Rune get(String name) {
        return RUNES.get(name);
    }

    public static Collection<Rune> all() {
        return RUNES.values();
    }

    private static Rune def(String name, Category c, Type ret) {
        Rune r = new Rune(name, c, ret);
        RUNES.put(name, r);
        return r;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Living targets the spell may touch (other players only if the spell allows players). */
    private static List<LivingEntity> living(Node n, Ctx c) {
        return living(entities(n, c), c);
    }

    private static List<LivingEntity> living(List<Entity> es, Ctx c) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : es) {
            if (e instanceof LivingEntity le && le.isAlive() && Targeting.mayAffect(c.inst(), e)) out.add(le);
        }
        return out;
    }

    /** Any targets the spell may touch, including non-living ones such as dropped items. */
    private static List<Entity> anyEntities(Node n, Ctx c) {
        return anyEntities(entities(n, c), c);
    }

    private static List<Entity> anyEntities(List<Entity> es, Ctx c) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : es) if (e.isAlive() && Targeting.mayAffect(c.inst(), e)) out.add(e);
        return out;
    }

    /** What disarm takes first: main hand, off hand, then head, chest, legs, feet; armour bound by a curse stays. */
    private static EquipmentSlot disarmSlot(LivingEntity t) {
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
                EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack s = t.getItemBySlot(slot);
            if (s.isEmpty()) continue;
            if (slot.isArmor() && net.minecraft.world.item.enchantment.EnchantmentHelper.has(s,
                    net.minecraft.world.item.enchantment.EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) continue;
            return slot;
        }
        return null;
    }

    /**
     * Group effects pay once, however many targets there are: a multiple of the single-target price
     * that grows with the radius {@code group} was gathered in (see {@link Group#costMultiplier}).
     */
    private static boolean spendGroup(Ctx c, List<Entity> group, int targets, double singleCost, String what) {
        return targets > 0 && c.inst().spend(singleCost * Group.costMultiplier(Group.radiusOf(group)), what);
    }

    /** A group selector's {@code :radius}, held to {@code maxGroupRadius}. */
    private static double groupRadius(Node n, Ctx c) {
        return Group.clampRadius(num(kw(n, "radius"), c));
    }

    private static ServerLevel lv(Entity e) {
        return (ServerLevel) e.level();
    }

    static {
        forms();
        bindings();
        effects();
        logic();
        values();
    }

    // ---- 盐 Salt / Form ----------------------------------------------------------------------

    private static void forms() {
        def("projectile", FORM, ACTION)
                .kw("from", POS, "here").kw("dir", DIR, "aim").kw("speed", NUM, "1.2").kw("life", NUM, "3")
                .kw("motion", Type.MOTION, "forward").kw("pierce", NUM, "0").kw("size", NUM, "0.3")
                .kw("look", LOOK, null).kw("spin", NUM, "0").kw("trail", PARTICLE, null)
                .on("on-hit", true, "命中实体时").on("on-impact", false, "撞上方块时（here 是那个方块）")
                .on("on-tick", false, "每一刻").on("on-expire", false, "消散时（撞墙/寿命结束/穿透耗尽）")
                .stab(4).doc("弹丸：在世界中飞行的魔法实体。基础 4 魔力 + 运动符文的代价。"
                        + ":look &物品 让它显现为那件物品的模型（如 &mace、&fire_charge），:spin 是每秒翻转的圈数；"
                        + ":trail %粒子 用那种粒子代替元素尾迹（如 %portal、%end_rod）。外观与拖尾不收魔力，也不改变任何行为")
                .exec((n, c) -> {
                    var m = motion(kw(n, "motion"), c);
                    if (!c.inst().spend(4 + m.mana, "projectile")) return null;
                    Place from = place(kw(n, "from"), c);
                    Node look = kw(n, "look");
                    Node trail = kw(n, "trail");
                    SpellRuntime.spawn(new ProjectileObj(n, c.at(from), from.pos(), vec(kw(n, "dir"), c),
                            clamp(num(kw(n, "speed"), c), 0.1, 4), (int) (clamp(num(kw(n, "life"), c), 0.1, 20) * 20), m,
                            (int) clamp(num(kw(n, "pierce"), c), 0, 99), clamp(num(kw(n, "size"), c), 0.1, 3),
                            new Vfx.Guise(look == null ? Vfx.NONE : Vfx.lookId(text(look, c)), clamp(num(kw(n, "spin"), c), -10, 10),
                                    trail == null ? Vfx.NONE : Vfx.particleId(text(trail, c)))));
                    Vfx.sound(from.level(), from.pos(), Vfx.sound(n.element), 0.5f, 1.2f);
                    return null;
                });

        def("beam", FORM, ACTION)
                .kw("from", POS, null).kw("dir", DIR, null).kw("radius", NUM, "0.5").kw("range", NUM, "24")
                .kw("life", NUM, "2").kw("every", NUM, "0.25").kw("trail", PARTICLE, null)
                .on("on-enter", true, "实体进入光柱时（每个实体一次）").on("on-hit", true, "每次脉动，对光柱里的每个实体")
                .on("on-tick", false, "每次脉动（here 是光柱尽头）").on("on-impact", false, "每次脉动，光柱被方块挡住时（here 是那个方块）")
                .on("on-expire", false, "消散时（here 是最后的尽头）")
                .stab(5).doc("光柱：持续存在的一道粗光束，每刻从起点沿方向重新成形，直到被方块挡住。"
                        + "不写 :from/:dir 时起点是施法者的眼睛、方向是施法者此刻的视线，所以会随转身立刻跟着扫动；"
                        + "写了则每刻重新求值（如 :dir 指向最近的敌人即可追踪）。展开 6 魔力，每次脉动维持 0.3 + 0.05×半径×射程。"
                        + ":trail %粒子 让光柱上飘起那种粒子")
                .exec((n, c) -> {
                    if (!c.inst().spend(6, "beam")) return null;
                    Node trail = kw(n, "trail");
                    SpellRuntime.spawn(new BeamObj(n, c, clamp(num(kw(n, "radius"), c), 0.1, 4), clamp(num(kw(n, "range"), c), 1, 64),
                            (int) (clamp(num(kw(n, "life"), c), 0.05, 30) * 20), (int) Math.round(clamp(num(kw(n, "every"), c), 0.05, 10) * 20),
                            kw(n, "from"), kw(n, "dir"), trail == null ? Vfx.NONE : Vfx.particleId(text(trail, c))));
                    Vfx.sound(c.level(), c.inst().caster.getEyePosition(), net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE, 0.8f, 1.6f);
                    return null;
                });

        def("construct", FORM, ACTION)
                .kw("shape", SHAPE, "wall").kw("at", POS, "(ahead 3)").kw("facing", DIR, "aim")
                .kw("width", NUM, "5").kw("height", NUM, "3").kw("depth", NUM, "1").kw("radius", NUM, "3")
                .kw("life", NUM, "10").kw("every", NUM, "1").kw("solid", FILTER, "(kind all)").kw("look", LOOK, null)
                .kw("to", POS, null).kw("speed", NUM, "0.2").flag("snap").flag("one-way").flag("deflect").flag("follow")
                .on("on-block", true, "挡下实体或弹射物时（target 是被挡下的东西，here 是接触点）")
                .on("on-stand", true, "有实体站上来时（每次站上来触发一次）")
                .on("on-pass", true, "有东西穿过时（没被它挡住的生物进入时、弹射物飞过时，各一次；aim 是穿过的方向）")
                .on("on-arrive", false, "移动到达目的地时（:to 或 move；here 是中心）")
                .on("on-tick", false, "每 :every 秒（here 是中心）").on("on-expire", false, "消散时")
                .stab(5).doc("炼成体：把魔力凝成有形之物——box 方块、wall 墙、plane 平台、disk 圆盾、dome 穹顶。"
                        + ":solid ⊙ 决定挡住谁（默认全部，含施法者），也挡下弹射物、其他法术的弹丸与光柱、以及身后的爆炸；"
                        + "可以站在上面，也能贴着它放方块（放下的方块留在世界里）。:one-way 只挡从正面来的（平台是从上方，穹顶是从外面），"
                        + ":deflect 把弹射物反弹回去，:snap 对齐方块网格，:look &方块 显现为那种方块。"
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
                    ConstructObj obj = new ConstructObj(n, c.at(at), e, filter(kw(n, "solid"), c), n.flags.contains("deflect"),
                            (int) (clamp(num(kw(n, "life"), c), 0.5, 120) * 20), (int) Math.round(clamp(num(kw(n, "every"), c), 0.05, 10) * 20));
                    SpellRuntime.spawn(obj);
                    if (c.inst().dead) return null; // refused: too many bodies
                    Node to = kw(n, "to");
                    if (to != null) obj.moveTo(place(to, c).pos(), clamp(num(kw(n, "speed"), c), 0.01, 2));
                    at.level().addFreshEntity(e);
                    Vfx.sound(at.level(), g.center, SoundEvents.AMETHYST_BLOCK_RESONATE, 1f, 1.2f);
                    return null;
                });

        def("field", FORM, ACTION)
                .kw("at", POS, "here").kw("radius", NUM, "3").kw("life", NUM, "4").kw("every", NUM, "0.5").flag("follow")
                .on("on-tick", false, "每次脉动").on("on-enter", true, "敌人进入时（只治疗或护盾时己方进入也算，开启 :players 时含施法者自己）").on("on-expire", false, "消散时")
                .stab(5).doc("领域：持续存在的区域。展开 6 魔力，每次脉动维持 0.5。:follow 让它跟随施法者")
                .exec((n, c) -> {
                    if (!c.inst().spend(6, "field")) return null;
                    Place at = place(kw(n, "at"), c);
                    SpellRuntime.spawn(new FieldObj(n, c.at(at), at.pos(), clamp(num(kw(n, "radius"), c), 0.5, 16),
                            (int) (clamp(num(kw(n, "life"), c), 0.05, 30) * 20), (int) Math.round(clamp(num(kw(n, "every"), c), 0.05, 10) * 20),
                            n.flags.contains("follow")));
                    return null;
                });

        def("ray", FORM, ACTION)
                .kw("from", POS, "here").kw("dir", DIR, "aim").kw("to", ENTITY, null).kw("range", NUM, "16").kw("pierce", NUM, "0")
                .on("on-hit", true, "射线命中实体时").on("on-impact", false, "射线被方块挡住时（here 是那个方块）")
                .on("on-expire", false, "射线终点")
                .stab(3).doc("射线：瞬间成立的一条线。:to 指定实体时直接连向它（若为空则不成立）。3 魔力")
                .exec(Runes::ray);

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
            BlockHitResult bh = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, inst.caster));
            if (bh.getType() != HitResult.Type.MISS) {
                end = bh.getLocation();
                blocked = bh.getBlockPos();
            }
            Vec3 a = from, b = end;
            int pierce = (int) clamp(num(kw(n, "pierce"), c), 0, 99);
            boolean helps = Targeting.helpsOnHit(n);
            List<Entity> along = level.getEntities(inst.caster, new AABB(a, b).inflate(1), e -> Targeting.mayHit(inst, e, helps)
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

    // ---- 汞 Mercury / Binding ----------------------------------------------------------------

    private static void bindings() {
        def("nearest", BINDING, ENTITY).kw("at", POS, "here").kw("radius", NUM, "10").kw("filter", FILTER, null).flag("fresh")
                .stab(1).doc("最近的敌人（可能为空）。:fresh 跳过本法术已经影响过的对象；:filter 按实体 id 或 #标签 筛选。1 魔力")
                .exec((n, c) -> {
                    if (!c.inst().spend(1, "nearest")) return null;
                    Place at = place(kw(n, "at"), c);
                    Filter f = filter(kw(n, "filter"), c);
                    var l = Targeting.enemiesNear(c.inst(), at.level(), at.pos(), num(kw(n, "radius"), c), n.flags.contains("fresh"))
                            .stream().filter(e -> f.test(c.inst(), e)).toList();
                    return l.isEmpty() ? null : l.getFirst();
                });
        def("enemies", BINDING, ENTITIES).kw("at", POS, "here").kw("radius", NUM, "5").kw("filter", FILTER, null).flag("fresh")
                .stab(1).doc("范围内所有敌人（生物；开启 :players 的术式也包括其他玩家）。:filter 按实体 id 或 #标签 筛选，如 #minecraft:undead。2 魔力")
                .exec((n, c) -> {
                    if (!c.inst().spend(2, "enemies")) return List.of();
                    Place at = place(kw(n, "at"), c);
                    Filter f = filter(kw(n, "filter"), c);
                    double r = groupRadius(n, c);
                    return new Group(r, Targeting.enemiesNear(c.inst(), at.level(), at.pos(), r, n.flags.contains("fresh"))
                            .stream().filter(e -> f.test(c.inst(), e)).toList());
                });
        def("players", BINDING, ENTITIES).kw("at", POS, "here").kw("radius", NUM, "8")
                .stab(1).doc("范围内的其他玩家（不含施法者；在领域里时也包括施法者自己）。只有在启式中开启 :players 的术式才能使用。1 魔力")
                .exec((n, c) -> {
                    if (!c.inst().spend(1, "players")) return List.of();
                    Place at = place(kw(n, "at"), c);
                    double r = groupRadius(n, c);
                    return new Group(r, Targeting.withCaster(c, Targeting.near(c.inst(), at.level(), at.pos(), r, false,
                            e -> e instanceof Player && e != c.inst().caster && e.isAlive() && !e.isSpectator()), at, r, e -> e instanceof Player));
                });
        def("master", BINDING, ENTITY)
                .doc("施法者的主人：魔力生命体心核里的术式用它指向召唤它的玩家，例如 (heal (master) 4)。玩家自己施法时就是施法者本人。免费")
                .exec((n, c) -> {
                    java.util.UUID m = dev.logos.runtime.Familiars.masterOf(c.inst().caster);
                    return m == null ? c.inst().caster : c.inst().caster.level().getPlayerByUUID(m);
                });
        def("foe", BINDING, ENTITY)
                .doc("施法者正在战斗的对象：魔力生命体普攻时就是它对准的那个，例如普攻页写 (damage (foe) 6)。没有时为空（玩家施法时总是空）。免费")
                .exec((n, c) -> c.inst().caster instanceof net.minecraft.world.entity.Mob m ? dev.logos.runtime.Familiars.orderedTarget(m) : null);
        def("familiar", BINDING, ENTITY).opt("name", TEXT, "\"\"")
                .doc("你的一只使魔：叫这个名字的（召唤时用 :name 起名），不写名字就是最后召唤的那只；没有则为空。免费")
                .exec((n, c) -> c.inst().caster instanceof net.minecraft.world.entity.player.Player p
                        ? dev.logos.runtime.Familiars.latest(p, text(n.arg(0), c)) : null);
        def("entities", BINDING, ENTITIES).kw("at", POS, "here").kw("radius", NUM, "6").kw("filter", FILTER, "(kind all)")
                .stab(1).doc("范围内符合筛选的任意实体（不含施法者），如 :filter (kind projectiles) 选出飞行中的箭矢。"
                        + "其他玩家仍然需要 :players true；开启 :players 的术式在领域里时，筛选包括施法者的话也选上施法者自己。2 魔力")
                .exec((n, c) -> {
                    if (!c.inst().spend(2, "entities")) return List.of();
                    Place at = place(kw(n, "at"), c);
                    Filter f = filter(kw(n, "filter"), c);
                    double r = groupRadius(n, c);
                    return new Group(r, Targeting.withCaster(c, Targeting.near(c.inst(), at.level(), at.pos(), r, false,
                            e -> e.isAlive() && !e.isSpectator() && Targeting.mayAffect(c.inst(), e) && f.test(c.inst(), e)),
                            at, r, e -> f.testSelf(c.inst(), e)));
                });
        def("kind", BINDING, FILTER).p("kind", KIND)
                .doc("目标种类：" + TargetKind.words() + "。接到任何 :filter 上；只有 all 能筛选方块。免费")
                .exec((n, c) -> Filter.of((TargetKind) n.arg(0).value));
        def("any-of", BINDING, FILTER).rest("filter", FILTER).doc("符合其中任意一个筛选即可。免费")
                .exec((n, c) -> Filter.anyOf(n.args.stream().map(a -> filter(a, c)).toList()));
        def("all-of", BINDING, FILTER).rest("filter", FILTER).doc("必须同时符合所有筛选。免费")
                .exec((n, c) -> Filter.allOf(n.args.stream().map(a -> filter(a, c)).toList()));
        def("except", BINDING, FILTER).p("filter", FILTER).p("without", FILTER)
                .doc("符合前一个筛选、但不符合后一个，如 (except (kind enemies) \"#minecraft:undead\")。免费")
                .exec((n, c) -> Filter.except(filter(n.arg(0), c), filter(n.arg(1), c)));
        def("items", BINDING, ENTITIES).kw("at", POS, "here").kw("radius", NUM, "6").kw("filter", FILTER, null)
                .stab(1).doc("范围内的掉落物。:filter 按物品 id 或 #标签 筛选，如 #minecraft:logs。可以被 push/pull/launch 等作用。1 魔力")
                .exec((n, c) -> {
                    if (!c.inst().spend(1, "items")) return List.of();
                    Place at = place(kw(n, "at"), c);
                    Filter f = filter(kw(n, "filter"), c);
                    double r = groupRadius(n, c);
                    return new Group(r, Targeting.near(c.inst(), at.level(), at.pos(), r, false,
                            e -> e instanceof ItemEntity ie && ie.isAlive() && f.test(c.inst(), ie)));
                });
        def("blocks", BINDING, BLOCKS).kw("at", POS, "here").kw("radius", NUM, "2").kw("filter", FILTER, null)
                .stab(2).doc("范围内的方块（不含空气，半径最多 " + Targeting.MAX_BLOCK_RADIUS
                        + "）。:filter 按方块 id 或 #标签 筛选，如 minecraft:wheat、#minecraft:logs、#minecraft:crops。2 魔力")
                .exec((n, c) -> {
                    if (!c.inst().spend(2, "blocks")) return List.of();
                    Place at = place(kw(n, "at"), c);
                    return new ArrayList<>(Targeting.blocksNear(at.level(), at.pos(), num(kw(n, "radius"), c), filter(kw(n, "filter"), c)));
                });
        def("hit-list", BINDING, ENTITIES).stab(1).doc("本次法术至今影响过的所有存活实体（法术的记忆）。1 魔力")
                .exec((n, c) -> {
                    if (!c.inst().spend(1, "hit-list")) return List.of();
                    List<Entity> out = new ArrayList<>();
                    for (var id : c.inst().affected) {
                        for (ServerLevel l : c.level().getServer().getAllLevels()) {
                            Entity e = l.getEntity(id);
                            if (e != null && e.isAlive()) {
                                out.add(e);
                                break;
                            }
                        }
                    }
                    return out;
                });
        def("marked", BINDING, ENTITIES).p("mark", STATUS).kw("at", POS, "here").kw("radius", NUM, "24")
                .stab(1).doc("范围内带有某状态/印记的敌人。1 魔力")
                .exec((n, c) -> {
                    if (!c.inst().spend(1, "marked")) return List.of();
                    Status s = status(n.arg(0), c);
                    Place at = place(kw(n, "at"), c);
                    double r = groupRadius(n, c);
                    return new Group(r, Targeting.enemiesNear(c.inst(), at.level(), at.pos(), r, false)
                            .stream().filter(e -> StatusData.has(e, s)).toList());
                });
        def("pos-of", BINDING, POS).p("entity", ENTITY).doc("实体的中心位置")
                .exec((n, c) -> {
                    Entity e = entity(n.arg(0), c);
                    return e == null ? c.place() : new Place(lv(e), Targeting.center(e));
                });
        def("above", BINDING, POS).p("pos", POS).opt("height", NUM, "8").doc("某位置上方")
                .exec((n, c) -> {
                    Place p = place(n.arg(0), c);
                    return p.moved(p.pos().add(0, num(n.arg(1), c), 0));
                });
        def("ahead", BINDING, POS).p("distance", NUM).kw("from", POS, "here").kw("dir", DIR, "aim").doc("沿方向前进一段距离后的位置")
                .exec((n, c) -> {
                    Place p = place(kw(n, "from"), c);
                    return p.moved(p.pos().add(vec(kw(n, "dir"), c).normalize().scale(num(n.arg(0), c))));
                });
        def("look-point", BINDING, POS).opt("range", NUM, "32").stab(1).doc("施法者视线落点（方块表面前方的空气）。1 魔力")
                .exec((n, c) -> {
                    if (!c.inst().spend(1, "look-point")) return c.place();
                    var caster = c.inst().caster;
                    Vec3 look = caster.getLookAngle();
                    BlockHitResult bh = sight(caster, num(n.arg(0), c));
                    Vec3 p = bh.getType() == HitResult.Type.MISS ? bh.getLocation() : bh.getLocation().subtract(look.scale(0.3));
                    return new Place(lv(caster), p);
                });
        def("look-block", BINDING, POS).opt("range", NUM, "32").stab(1).doc("施法者正看着的那个方块（方块中心）；没看着方块时为视线尽头。1 魔力")
                .exec((n, c) -> {
                    if (!c.inst().spend(1, "look-block")) return c.place();
                    var caster = c.inst().caster;
                    BlockHitResult bh = sight(caster, num(n.arg(0), c));
                    return bh.getType() == HitResult.Type.MISS ? new Place(lv(caster), bh.getLocation()) : Place.center(lv(caster), bh.getBlockPos());
                });
        def("in-dimension", BINDING, POS).p("dimension", DIMENSION).opt("pos", POS, "here").stab(2)
                .doc("另一个维度中的对应位置：水平坐标按两个维度的坐标比例换算（主世界 8 格 = 下界 1 格），高度限制在目标维度范围内。"
                        + "只能指向施法者亲身到过的维度（使魔看主人）。2 魔力")
                .exec((n, c) -> {
                    String id = (String) eval(n.arg(0), c);
                    Place from = place(n.arg(1), c);
                    ServerLevel target = dimension(c, id);
                    if (target == null) {
                        c.inst().terminate("不存在的维度 @" + id);
                        return from;
                    }
                    if (!dev.logos.runtime.Attunement.reaches(c.inst().caster, target)) {
                        c.inst().terminate("还没有到过维度 @" + id + "，无法与之共鸣");
                        return from;
                    }
                    if (!c.inst().spend(2, "in-dimension")) return c.place();
                    double scale = from.level().dimensionType().coordinateScale() / target.dimensionType().coordinateScale();
                    Vec3 p = from.pos();
                    double y = Math.max(target.getMinY(), Math.min(target.getMaxY(), p.y));
                    return new Place(target, new Vec3(p.x * scale, y, p.z * scale));
                });
        def("toward", BINDING, DIR).p("from", POS).p("to", POS).doc("从一点指向另一点的方向")
                .exec((n, c) -> vec(n.arg(1), c).subtract(vec(n.arg(0), c)).normalize());
        def("random-dir", BINDING, DIR).doc("随机方向")
                .exec((n, c) -> {
                    var r = c.level().getRandom();
                    return new Vec3(r.nextGaussian(), r.nextGaussian() * 0.5, r.nextGaussian()).normalize();
                });
    }

    private static BlockHitResult sight(LivingEntity caster, double range) {
        Vec3 eye = caster.getEyePosition();
        Vec3 end = eye.add(caster.getLookAngle().scale(clamp(range, 1, 64)));
        return caster.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, caster));
    }

    private static ServerLevel dimension(Ctx c, String id) {
        Identifier key = Identifier.tryParse(id);
        return key == null ? null : c.level().getServer().getLevel(ResourceKey.create(Registries.DIMENSION, key));
    }

    // ---- 硫 Sulfur / Effect ------------------------------------------------------------------

    private static void effects() {
        def("damage", EFFECT, ACTION).each("target").p("amount", NUM).opt("element", ELEMENT, "physical")
                .on("on-kill", true, "目标因此死亡时").stab(2)
                .doc("造成伤害。每点伤害消耗：物理0.8 火/冰1.0 雷1.1 虚空1.5")
                .exec((n, c) -> {
                    double amt = Math.max(0, num(n.arg(1), c));
                    Element el = element(n.arg(2), c);
                    for (LivingEntity t : living(n.arg(0), c)) {
                        if (!c.inst().spend(amt * el.costFactor, "damage")) return null;
                        if (Effects.damage(c.inst(), t, amt, el)) fire(n, "on-kill", c.on(t, Targeting.center(t)));
                    }
                    return null;
                });
        def("drain", EFFECT, ACTION).each("target").p("amount", NUM).on("on-kill", true, "目标因此死亡时").stab(3)
                .doc("汲取：造成虚空伤害，并治疗施法者一半。每点 1.5 魔力")
                .exec((n, c) -> {
                    double amt = Math.max(0, num(n.arg(1), c));
                    for (LivingEntity t : living(n.arg(0), c)) {
                        if (!c.inst().spend(amt * 1.5, "drain")) return null;
                        boolean killed = Effects.damage(c.inst(), t, amt, Element.VOID);
                        c.inst().caster.heal((float) amt / 2);
                        if (t.level() == c.inst().caster.level()) Vfx.line(lv(t), Targeting.center(t), Targeting.center(c.inst().caster), Element.VOID);
                        if (killed) fire(n, "on-kill", c.on(t, Targeting.center(t)));
                    }
                    return null;
                });
        def("heal", EFFECT, ACTION).each("target").p("amount", NUM).stab(2).doc("治疗。每点 1.5 魔力")
                .exec((n, c) -> {
                    double amt = Math.max(0, num(n.arg(1), c));
                    for (LivingEntity t : living(n.arg(0), c)) {
                        if (!c.inst().spend(amt * 1.5, "heal")) return null;
                        t.heal((float) amt);
                        Vfx.burst(lv(t), t, null, Element.LIFE, Vfx.Burst.HEAL);
                    }
                    return null;
                });
        def("shield", EFFECT, ACTION).each("target").p("amount", NUM).stab(2).doc("护盾（伤害吸收），可叠加，不设上限。每点 1.5 魔力")
                .exec((n, c) -> {
                    double amt = Math.max(0, num(n.arg(1), c));
                    for (LivingEntity t : living(n.arg(0), c)) {
                        if (!c.inst().spend(amt * 1.5, "shield")) return null;
                        Effects.shield(t, amt);
                        Vfx.burst(lv(t), t, null, Element.LIFE, Vfx.Burst.WARD);
                    }
                    return null;
                });
        def("ignite", EFFECT, ACTION).each("target").p("seconds", NUM).stab(1).doc("点燃（状态 burning）。飞行中的箭矢也能点燃：射中时会点燃目标。每秒 0.6 魔力")
                .exec((n, c) -> {
                    double s = clamp(num(n.arg(1), c), 0, 60);
                    for (Entity t : anyEntities(n.arg(0), c)) {
                        if (!c.inst().spend(s * 0.6, "ignite")) return null;
                        t.igniteForSeconds((float) s);
                        c.inst().affected.add(t.getUUID());
                    }
                    return null;
                });
        def("freeze", EFFECT, ACTION).each("target").p("seconds", NUM).stab(1).doc("冻结（状态 frozen，几乎无法移动）。每秒 1.2 魔力")
                .exec((n, c) -> {
                    double s = clamp(num(n.arg(1), c), 0, 30);
                    for (LivingEntity t : living(n.arg(0), c)) {
                        if (!c.inst().spend(s * 1.2, "freeze")) return null;
                        StatusData.freeze(t, s);
                        c.inst().affected.add(t.getUUID());
                        Vfx.burst(lv(t), Targeting.center(t), Element.FROST, 12);
                    }
                    return null;
                });
        def("time-stop", EFFECT, ACTION).each("target").p("seconds", NUM).stab(2)
                .doc("时间停止：目标的时间停止（最多 10 秒）。生物完全静止；玩家无法移动、攻击或使用物品。"
                        + "期间受到的伤害先存下，时间恢复时一次性生效。每秒 4 魔力")
                .exec((n, c) -> {
                    double s = clamp(num(n.arg(1), c), 0, 10);
                    for (LivingEntity t : living(n.arg(0), c)) {
                        if (!c.inst().spend(s * 4, "time-stop")) return null;
                        TimeStop.stop(t, s);
                        c.inst().affected.add(t.getUUID());
                        Vfx.burst(lv(t), Targeting.center(t), Element.ARCANE, 12);
                    }
                    return null;
                });
        def("damage-all", EFFECT, ACTION).p("targets", ENTITIES).p("amount", NUM).opt("element", ELEMENT, "physical")
                .on("on-kill", true, "每个因此死亡的目标").stab(3)
                .doc("群体伤害：一次付费，同时伤害群中每个目标，不会因魔力耗尽只打到一部分。"
                        + "消耗与目标数无关：半径 5 以内为单体的 3 倍（每点物理2.4 火/冰3.0 雷3.3 虚空4.5），"
                        + "选出这个群的范围越大越贵，再乘 (半径 ÷ 5)^1.5（倍率与指数可配置）；群为空时不消耗")
                .exec((n, c) -> {
                    double amt = Math.max(0, num(n.arg(1), c));
                    Element el = element(n.arg(2), c);
                    List<Entity> group = entities(n.arg(0), c);
                    List<LivingEntity> ts = living(group, c);
                    if (!spendGroup(c, group, ts.size(), amt * el.costFactor, "damage-all")) return null;
                    for (LivingEntity t : ts) {
                        if (Effects.damage(c.inst(), t, amt, el)) fire(n, "on-kill", c.on(t, Targeting.center(t)));
                    }
                    return null;
                });
        def("ignite-all", EFFECT, ACTION).p("targets", ENTITIES).p("seconds", NUM).stab(2)
                .doc("群体点燃：一次付费点燃群中每个目标（含飞行中的箭矢）。每秒 1.8 魔力（单体 3 倍），与目标数无关；"
                        + "群的半径超过 5 时再乘 (半径 ÷ 5)^1.5（均可配置）")
                .exec((n, c) -> {
                    double s = clamp(num(n.arg(1), c), 0, 60);
                    List<Entity> group = entities(n.arg(0), c);
                    List<Entity> ts = anyEntities(group, c);
                    if (!spendGroup(c, group, ts.size(), s * 0.6, "ignite-all")) return null;
                    for (Entity t : ts) {
                        t.igniteForSeconds((float) s);
                        c.inst().affected.add(t.getUUID());
                    }
                    return null;
                });
        def("freeze-all", EFFECT, ACTION).p("targets", ENTITIES).p("seconds", NUM).stab(2)
                .doc("群体冻结：一次付费冻结群中每个目标。每秒 3.6 魔力（单体 3 倍），与目标数无关；"
                        + "群的半径超过 5 时再乘 (半径 ÷ 5)^1.5（均可配置）")
                .exec((n, c) -> {
                    double s = clamp(num(n.arg(1), c), 0, 30);
                    List<Entity> group = entities(n.arg(0), c);
                    List<LivingEntity> ts = living(group, c);
                    if (!spendGroup(c, group, ts.size(), s * 1.2, "freeze-all")) return null;
                    for (LivingEntity t : ts) {
                        StatusData.freeze(t, s);
                        c.inst().affected.add(t.getUUID());
                        Vfx.burst(lv(t), Targeting.center(t), Element.FROST, 12);
                    }
                    return null;
                });
        def("disarm", EFFECT, ACTION).each("target").stab(2)
                .doc("缴械：目标掉落一件装备。先主手，主手为空则副手，再依次是头、胸、腿、脚。带绑定诅咒的盔甲不会脱落。"
                        + "掉落的物品 2 秒内捡不起来。每个目标 8 魔力，没有可掉落的装备时不消耗")
                .exec((n, c) -> {
                    for (LivingEntity t : living(n.arg(0), c)) {
                        EquipmentSlot slot = disarmSlot(t);
                        if (slot == null) continue;
                        if (!c.inst().spend(8, "disarm")) return null;
                        ItemStack stack = t.getItemBySlot(slot);
                        t.setItemSlot(slot, ItemStack.EMPTY);
                        ItemEntity drop = t.spawnAtLocation(lv(t), stack, (float) (t.getBbHeight() * 0.6));
                        if (drop != null) drop.setPickUpDelay(40);
                        c.inst().affected.add(t.getUUID());
                        Vfx.sound(lv(t), Targeting.center(t), SoundEvents.ITEM_BREAK.value(), 0.6f, 1.6f);
                        Vfx.burst(lv(t), t, c.inst().caster.level() == t.level() ? c.inst().caster.getEyePosition() : null, Element.PHYSICAL, Vfx.Burst.FORCE);
                    }
                    return null;
                });
        def("potion", EFFECT, ACTION).each("target").p("effect", Type.MOB_EFFECT).p("seconds", NUM).opt("level", NUM, "1").stab(2)
                .doc("药水效果：按注册名施加，原版和模组的都可以（如 speed、minecraft:regeneration、模组id:效果名）。"
                        + "等级从 1 开始，最高 10。持续效果每秒每级 0.8 魔力，最长 600 秒；瞬间效果（instant_health 等）每级 6 魔力")
                .exec((n, c) -> {
                    var effect = net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.get(Identifier.parse((String) n.arg(1).value)).orElse(null);
                    if (effect == null) return null;
                    double s = clamp(num(n.arg(2), c), 0, 600);
                    int level = (int) clamp(Math.round(num(n.arg(3), c)), 1, 10);
                    boolean instant = effect.value().isInstantenous();
                    double cost = instant ? 6 * level : s * level * 0.8;
                    // An instant effect applies on its first tick; a lasting one needs at least a tick to exist.
                    int ticks = instant ? 1 : Math.max(1, (int) Math.round(s * 20));
                    for (LivingEntity t : living(n.arg(0), c)) {
                        if (!c.inst().spend(cost, "potion")) return null;
                        t.addEffect(new MobEffectInstance(effect, ticks, level - 1), c.inst().caster);
                        c.inst().affected.add(t.getUUID());
                        Vfx.burst(lv(t), t, null, Element.ARCANE, Vfx.potion(effect));
                    }
                    return null;
                });
        def("push", EFFECT, ACTION).each("target").p("strength", NUM).kw("from", POS, "here").stab(1).doc("推离某点（掉落物也可以）。每单位力 1.5 魔力")
                .exec((n, c) -> impulse(n, c, true));
        def("pull", EFFECT, ACTION).each("target").p("strength", NUM).kw("to", POS, "here").stab(1).doc("拉向某点（掉落物也可以：吸取物品）。每单位力 1.5 魔力")
                .exec((n, c) -> impulse(n, c, false));
        def("launch", EFFECT, ACTION).each("target").p("strength", NUM).stab(1).doc("击飞（掉落物也可以）。每单位力 1.5 魔力")
                .exec((n, c) -> {
                    double s = clamp(num(n.arg(1), c), 0, 4);
                    for (Entity t : anyEntities(n.arg(0), c)) {
                        if (!c.inst().spend(s * 1.5, "launch")) return null;
                        Effects.impulse(c.inst(), t, new Vec3(0, 1, 0), s);
                    }
                    return null;
                });
        def("mark", EFFECT, ACTION).each("target").p("mark", STATUS).opt("stacks", NUM, "1").stab(1)
                .doc("刻下印记 #名字，可叠加。法术之间借印记通信。每层 1 魔力")
                .exec((n, c) -> {
                    Status s = status(n.arg(1), c);
                    int k = (int) clamp(Math.round(num(n.arg(2), c)), 1, 99);
                    for (LivingEntity t : living(n.arg(0), c)) {
                        if (!c.inst().spend(k, "mark")) return null;
                        StatusData.mark(t, s, k);
                        c.inst().affected.add(t.getUUID());
                        Vfx.mark(lv(t), t.position().add(0, t.getBbHeight() + 0.3, 0));
                    }
                    return null;
                });
        def("unmark", EFFECT, ACTION).each("target").p("mark", STATUS).stab(1).doc("消除印记。0.5 魔力")
                .exec((n, c) -> {
                    Status s = status(n.arg(1), c);
                    for (LivingEntity t : living(n.arg(0), c)) {
                        if (!c.inst().spend(0.5, "unmark")) return null;
                        StatusData.unmark(t, s);
                    }
                    return null;
                });
        def("glow", EFFECT, ACTION).each("target").p("seconds", NUM).doc("发光（便于观察；掉落物也可以）。每秒 0.2 魔力")
                .exec((n, c) -> {
                    double s = clamp(num(n.arg(1), c), 0, 60);
                    for (Entity t : anyEntities(n.arg(0), c)) {
                        if (!c.inst().spend(s * 0.2, "glow")) return null;
                        if (t instanceof LivingEntity le) le.addEffect(new MobEffectInstance(MobEffects.GLOWING, (int) (s * 20), 0, false, false));
                        else t.setGlowingTag(true);
                    }
                    return null;
                });
        def("execute", EFFECT, ACTION).p("target", ENTITY).on("on-kill", true, "处决成功时").stab(6)
                .doc("处决：直接终结生命。代价由证明决定：4×(已证明的生命上界)×(已证明状态的折减)。前提失效会引发悖论反噬")
                .exec(Runes::execute);
        def("blink", EFFECT, ACTION).p("pos", POS).stab(2).doc("施法者瞬移到某位置，可以跨维度（配合 in-dimension）。2 + 每格 0.5 魔力，跨维度另加 20")
                .exec((n, c) -> {
                    var caster = c.inst().caster;
                    Place p = place(n.arg(0), c);
                    boolean cross = p.level() != caster.level();
                    double cost = 2 + (cross ? 20 : 0.5 * p.pos().distanceTo(caster.position()));
                    if (!c.inst().spend(cost, "blink")) return null;
                    Vfx.burst(lv(caster), caster.position().add(0, 1, 0), Element.VOID, 20);
                    Vec3 to = cross || !standable(p.level(), BlockPos.containing(p.pos())) ? safeSpot(p.level(), p.pos()) : p.pos();
                    caster.teleport(new TeleportTransition(p.level(), to, Vec3.ZERO, caster.getYRot(), caster.getXRot(), TeleportTransition.DO_NOTHING));
                    caster.resetFallDistance();
                    Vfx.sound(p.level(), to, SoundEvents.ENDERMAN_TELEPORT, 0.6f, 1.2f);
                    return null;
                });
        blockEffects();
        def("summon", EFFECT, ACTION).p("creature", CREATURE).kw("at", POS, "(ahead 2)").kw("name", TEXT, null)
                .kw("hunt", FILTER, null).kw("radius", NUM, null).kw("level", NUM, "1").kw("mana", NUM, null).stab(3)
                .doc("召唤一只你击杀过足够多次的生物作为使魔（普通生物 " + dev.logos.runtime.Familiars.NORMAL_KILLS + " 次，Boss "
                        + dev.logos.runtime.Familiars.BOSS_KILLS + " 次），存在一个游戏日。它保留自己的攻击方式，只攻击你在战斗的对象，"
                        + "跟随你，不伤你和你的其他使魔；它的击杀算你的。:hunt 给它一个猎杀姿态（见 stance）。魔力 = 最大生命值 × 2。数量上限见配置 maxFamiliars。"
                        + "召唤 mana_lifeform（魔力生命体）不需要击杀：:level 1–5，至少要 50×等级^1.5 魔力，"
                        + "默认把术式剩下的魔力全部（最多 1000×等级^1.5）凝成它的魔力，:mana 可以指定多少。它一直存在，靠自己的魔力和心核里的载页行动")
                .exec((n, c) -> {
                    if (!(c.inst().caster instanceof ServerPlayer sp)) return null;
                    var type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.tryParse((String) n.arg(0).value));
                    Place at = place(kw(n, "at"), c);
                    Node name = kw(n, "name");
                    Node hunt = kw(n, "hunt"), radius = kw(n, "radius");
                    if (Compiler.MANA_LIFEFORM.equals(n.arg(0).value)) {
                        Node mana = kw(n, "mana");
                        String why = dev.logos.runtime.Lifeforms.summon(c.inst(), sp, at.level(), at.pos(), name == null ? null : text(name, c),
                                (int) Math.floor(num(kw(n, "level"), c)), mana == null ? null : num(mana, c));
                        if (why != null) c.inst().terminate(why);
                        else if (hunt != null || radius != null) {
                            dev.logos.runtime.Familiars.setStance(dev.logos.runtime.Familiars.latest(sp, null),
                                    hunt == null ? null : filter(hunt, c), c.inst(), radius == null ? null : num(radius, c));
                        }
                        return null;
                    }
                    String why = dev.logos.runtime.Familiars.summon(c.inst(), sp, type, at.level(), at.pos(), name == null ? null : text(name, c),
                            hunt == null ? null : filter(hunt, c), radius == null ? null : num(radius, c));
                    if (why != null) c.inst().terminate(why);
                    return null;
                });
        def("dismiss", EFFECT, ACTION).eachOr("target", "(familiar)")
                .doc("遣散你的使魔：它掉落自己捡起的东西后消失。不连目标时遣散最后召唤的那只；对不是你使魔的对象无效。免费")
                .exec((n, c) -> {
                    for (Entity t : entities(n.arg(0), c)) {
                        if (t instanceof net.minecraft.world.entity.Mob m && c.inst().caster.getUUID().equals(dev.logos.runtime.Familiars.masterOf(m))) {
                            dev.logos.runtime.Familiars.dismiss(m);
                        }
                    }
                    return null;
                });
        def("gather", EFFECT, ACTION).eachOr("familiar", "(familiar)").kw("to", POS, "(pos-of caster)").stab(1)
                .doc("召集：让你的使魔走（或飞）到某处，路上不管战斗。不连使魔时是最后召唤的那只，不写 :to 是你的位置。"
                        + "到你身边后继续跟随；到别处则在那里驻守，直到你开战、走出 96 格或下达新命令。到不了时尝试一阵后放弃。每只 1 魔力")
                .exec((n, c) -> {
                    Place to = place(kw(n, "to"), c);
                    for (Entity t : entities(n.arg(0), c)) {
                        if (!(t instanceof net.minecraft.world.entity.Mob m) || !c.inst().caster.getUUID().equals(dev.logos.runtime.Familiars.masterOf(m))) continue;
                        if (m.level() != to.level() || !c.inst().spend(1, "gather")) continue;
                        dev.logos.runtime.Familiars.sendTo(m, to.pos());
                    }
                    return null;
                });
        def("stance", EFFECT, ACTION).eachOr("familiar", "(familiar)").kw("hunt", FILTER, null).kw("radius", NUM, null).kw("role", TEXT, null).stab(1)
                .doc("设置使魔属性（姿态）：:hunt 是它主动猎杀的对象，如 (kind hostile)、\"#minecraft:undead\"；"
                        + "没有你的战斗时，它会去打 :radius 格内（默认 " + (int) dev.logos.runtime.Familiars.HUNT_RADIUS + "，最多 "
                        + (int) dev.logos.runtime.Familiars.MAX_HUNT_RADIUS + "）离它最近、它或你看得见的那个；驻守时以驻守点为圆心。"
                        + "你开战或下令时以你的目标为先。(kind none) 让它收手。只写的项才改。"
                        + "绝不猎杀你、你的使魔和宠物，其他玩家要 :players true。"
                        + ":role 只对魔力生命体有效，调整它的取舍：\"auto\" 自然（默认）、\"striker\" 进攻、\"guard\" 护卫、\"support\" 辅助，也可以写中文。"
                        + "不连使魔时是最后召唤的那只。每只 2 魔力")
                .exec((n, c) -> {
                    Node hunt = kw(n, "hunt"), radius = kw(n, "radius"), roleNode = kw(n, "role");
                    dev.logos.runtime.ManaLifeform.Role role = null;
                    if (roleNode != null) {
                        role = dev.logos.runtime.ManaLifeform.Role.parse(text(roleNode, c).strip());
                        if (role == null) {
                            c.inst().terminate(":role 只能是 auto / striker / guard / support（自然 / 进攻 / 护卫 / 辅助），不是 " + text(roleNode, c));
                            return null;
                        }
                    }
                    for (Entity t : entities(n.arg(0), c)) {
                        if (!(t instanceof net.minecraft.world.entity.Mob m) || !c.inst().caster.getUUID().equals(dev.logos.runtime.Familiars.masterOf(m))) continue;
                        if (!c.inst().spend(2, "stance")) return null;
                        dev.logos.runtime.Familiars.setStance(m, hunt == null ? null : filter(hunt, c), c.inst(), radius == null ? null : num(radius, c));
                        if (role != null && m instanceof dev.logos.runtime.ManaLifeform lf) lf.setRole(role);
                    }
                    return null;
                });
        def("sic", EFFECT, ACTION).p("target", ENTITY).kw("familiar", ENTITIES, "(familiar)").stab(1)
                .doc("下令攻击：让你的使魔立刻把目标当成你在打的对象（监守者也会立刻暴怒）。:familiar 不连时是最后召唤的那只，"
                        + "也可以接 (familiar \"名字\") 或一群使魔。不能对你自己和你的使魔下令。每只 1 魔力")
                .exec((n, c) -> {
                    if (!(entity(n.arg(0), c) instanceof LivingEntity foe)) return null;
                    for (Entity t : entities(kw(n, "familiar"), c)) {
                        if (!(t instanceof net.minecraft.world.entity.Mob m) || !c.inst().caster.getUUID().equals(dev.logos.runtime.Familiars.masterOf(m))) continue;
                        if (!c.inst().spend(1, "sic")) return null;
                        dev.logos.runtime.Familiars.order(m, foe);
                    }
                    return null;
                });
        def("anchor", EFFECT, ACTION).p("anchor", ANCHOR).opt("at", POS, "here").stab(1)
                .doc("在某处立下（或移动）锚点 ^名字，之后 ^名字 就能当位置用，跨维度也行。"
                        + "消耗背包里的一枚锚钉（创造模式不消耗）；没有锚钉或锚点已满时法术熄灭")
                .exec((n, c) -> {
                    if (!(c.inst().caster instanceof ServerPlayer sp)) return null;
                    Place at = place(n.arg(1), c);
                    String why = dev.logos.runtime.Anchors.set(sp, (String) n.arg(0).value, at.level(), at.pos());
                    if (why != null) c.inst().terminate(why);
                    return null;
                });
        def("unanchor", EFFECT, ACTION).p("anchor", ANCHOR).doc("撤去锚点 ^名字（锚钉不退还）。免费")
                .exec((n, c) -> {
                    if (c.inst().caster instanceof ServerPlayer sp) dev.logos.runtime.Anchors.remove(sp, (String) n.arg(0).value);
                    return null;
                });
        def("move", EFFECT, ACTION).p("construct", ENTITY).p("to", POS).kw("speed", NUM, "0.2").stab(1)
                .doc("让本术式的炼成体以 :speed 格/刻 移到某处（中心对准那里），站在上面的会被一起带走，到达时触发它的 on-arrive。通常写 (move self …)。1 魔力 + 每格 0.1")
                .exec((n, c) -> {
                    if (!(entity(n.arg(0), c) instanceof ConstructEntity ce) || ce.owner() == null || !ce.owner().owns(c.inst())) return null;
                    Vec3 to = place(n.arg(1), c).pos();
                    if (!c.inst().spend(1 + 0.1 * to.distanceTo(ce.geometry().center), "move")) return null;
                    ce.owner().moveTo(to, clamp(num(kw(n, "speed"), c), 0.01, 2));
                    return null;
                });
        def("halt", EFFECT, ACTION).p("construct", ENTITY).doc("让本术式的炼成体停在原地。免费")
                .exec((n, c) -> {
                    if (entity(n.arg(0), c) instanceof ConstructEntity ce && ce.owner() != null && ce.owner().owns(c.inst())) ce.owner().halt();
                    return null;
                });
        def("copy", EFFECT, ACTION).each("target").p("count", NUM).kw("spread", NUM, "10").stab(2)
                .doc("复制飞行中的弹射物（箭矢、火球、三叉戟……），复制品左右交替偏开 :spread 度。"
                        + "复制品不能再被复制，复制出的箭矢和三叉戟不能拾取。每份 3 魔力，一次最多 8 份")
                .exec((n, c) -> {
                    int count = (int) clamp(Math.round(num(n.arg(1), c)), 0, 8);
                    double spread = clamp(num(kw(n, "spread"), c), 0, 90);
                    for (Entity t : anyEntities(n.arg(0), c)) {
                        if (!dev.logos.runtime.Copies.canCopy(t)) continue;
                        for (int k = 1; k <= count; k++) {
                            if (!c.inst().spend(3, "copy")) return null;
                            double off = ((k + 1) / 2) * spread * (k % 2 == 1 ? 1 : -1);
                            dev.logos.runtime.Copies.copy((ServerLevel) t.level(), (net.minecraft.world.entity.projectile.Projectile) t, off);
                        }
                    }
                    return null;
                });
        def("phantasm", EFFECT, ACTION).p("particle", PARTICLE).opt("at", POS, "here").kw("count", NUM, "1").kw("spread", NUM, "0")
                .doc("幻像显现：在某处放出一个注册过的粒子效果（%名字，如 %gust_emitter_large、%portal、%sonic_boom）。"
                        + ":count 个数（≤64），:spread 散布半径（≤8）。只是幻象：不收魔力，不改变世界")
                .exec((n, c) -> {
                    Place at = place(n.arg(1), c);
                    Vfx.phantasm(at.level(), at.pos(), Vfx.particleId(text(n.arg(0), c)),
                            (int) clamp(Math.round(num(kw(n, "count"), c)), 1, 64), clamp(num(kw(n, "spread"), c), 0, 8));
                    return null;
                });
        def("echo", EFFECT, ACTION).p("value", ANY).doc("把一个值显示给施法者（调试用）。免费")
                .exec((n, c) -> {
                    Object v = eval(n.arg(0), c);
                    String s = switch (v) {
                        case null -> "空";
                        case Double d -> Node.fmt(d);
                        case Entity e -> e.getName().getString();
                        case Place p -> String.format("(%.1f, %.1f, %.1f) @%s", p.pos().x, p.pos().y, p.pos().z, p.level().dimension().identifier());
                        case List<?> l -> l.size() + " 个";
                        case Vec3 p -> String.format("(%.1f, %.1f, %.1f)", p.x, p.y, p.z);
                        default -> v.toString();
                    };
                    if (c.inst().caster instanceof ServerPlayer sp) {
                        sp.sendSystemMessage(Component.literal("echo " + n.arg(0).show() + " = " + s).withStyle(ChatFormatting.AQUA));
                    }
                    return null;
                });
    }

    /** Two blocks of room with something solid underfoot. */
    private static boolean standable(ServerLevel level, BlockPos feet) {
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP);
    }

    /** The nearest standable spot in the same column (searching up and down), so a blink never lands inside rock. */
    private static Vec3 safeSpot(ServerLevel level, Vec3 at) {
        BlockPos start = BlockPos.containing(at.x, Math.max(level.getMinY() + 1, Math.min(level.getMaxY() - 2, at.y)), at.z);
        for (int d = 0; d < level.getHeight(); d++) {
            for (int sign : new int[] {1, -1}) {
                BlockPos p = start.offset(0, d * sign, 0);
                if (p.getY() <= level.getMinY() || p.getY() >= level.getMaxY() - 1) continue;
                if (standable(level, p)) return Vec3.atBottomCenterOf(p);
            }
        }
        return at;
    }

    // ---- 硫 · blocks: spells that change the world itself ----------------------------------------

    /** Whether the caster may change this block (spawn protection, claims, adventure mode…). */
    private static boolean mayEdit(Ctx c, Place p) {
        if (!(c.inst().caster instanceof ServerPlayer sp)) return true;
        return p.level().mayInteract(sp, p.block()) && !sp.blockActionRestricted(p.level(), p.block(), sp.gameMode.getGameModeForPlayer());
    }

    private static IntegerProperty ageOf(BlockState state) {
        for (Property<?> prop : state.getProperties()) {
            if (prop instanceof IntegerProperty ip && prop.getName().equals("age")) return ip;
        }
        return null;
    }

    private static boolean isPlantDebris(BlockState s) {
        return s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS) || s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS)
                || s.is(Blocks.FERN) || s.is(Blocks.LARGE_FERN) || s.is(Blocks.DEAD_BUSH) || s.is(Blocks.BUSH);
    }

    /** Every block position within {@code radius} of {@code center}. */
    private static List<BlockPos> sphere(Place center, double radius) {
        int r = (int) Math.ceil(radius);
        BlockPos o = center.block();
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(o.offset(-r, -r, -r), o.offset(r, r, r))) {
            if (Vec3.atCenterOf(p).distanceTo(center.pos()) <= radius + 0.5) out.add(p.immutable());
        }
        return out;
    }

    private static void blockEffects() {
        def("toggle-state", EFFECT, ACTION).eachBlock("block").kw("property", TEXT, null).stab(2)
                .doc("像调试棒一样切换方块的一个状态属性（默认第一个属性，可用 :property 指定，如 facing、half、lit）。"
                        + "永久生效，不触发方块更新（不会掉落、不会连锁变化）。每个方块 5 魔力")
                .exec((n, c) -> {
                    Node pn = kw(n, "property");
                    String wanted = pn == null ? "" : text(pn, c).trim();
                    for (Place p : places(n.arg(0), c)) {
                        if (!mayEdit(c, p)) continue;
                        BlockState state = p.level().getBlockState(p.block());
                        Property<?> prop = null;
                        for (Property<?> candidate : state.getProperties()) {
                            if (wanted.isEmpty() || candidate.getName().equals(wanted)) {
                                prop = candidate;
                                break;
                            }
                        }
                        if (prop == null) continue;
                        if (!c.inst().spend(5, "toggle-state")) return null;
                        // Same flags as the debug stick: tell clients, skip neighbour/shape updates.
                        p.level().setBlock(p.block(), state.cycle(prop), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        Vfx.burst(p.level(), p.pos(), Element.ARCANE, 6);
                    }
                    return null;
                });
        def("grow", EFFECT, ACTION).eachBlock("at").opt("radius", NUM, "1").stab(2)
                .doc("生长：范围内有 age 属性的方块（作物、藤蔓…）长一阶；能被骨粉催熟的方块像用了骨粉一样生长；幼年生物长大一些。每个对象 2 魔力")
                .exec((n, c) -> {
                    double r = clamp(num(n.arg(1), c), 0, 6);
                    for (Place center : places(n.arg(0), c)) {
                        ServerLevel level = center.level();
                        for (BlockPos pos : sphere(center, r)) {
                            Place p = new Place(level, Vec3.atCenterOf(pos));
                            BlockState s = level.getBlockState(pos);
                            IntegerProperty age = ageOf(s);
                            if (age != null && s.getValue(age) < java.util.Collections.max(age.getPossibleValues()) && mayEdit(c, p)) {
                                if (!c.inst().spend(2, "grow")) return null;
                                level.setBlock(pos, s.setValue(age, s.getValue(age) + 1), Block.UPDATE_ALL);
                                level.levelEvent(LevelEvent.PARTICLES_AND_SOUND_PLANT_GROWTH, pos, 0);
                            } else if (s.getBlock() instanceof BonemealableBlock b && b.isValidBonemealTarget(level, pos, s) && mayEdit(c, p)) {
                                if (!c.inst().spend(2, "grow")) return null;
                                if (b.isBonemealSuccess(level, level.getRandom(), pos, s)) b.performBonemeal(level, level.getRandom(), pos, s);
                                level.levelEvent(LevelEvent.PARTICLES_AND_SOUND_PLANT_GROWTH, pos, 0);
                            }
                        }
                        for (Entity e : Targeting.near(c.inst(), level, center.pos(), r + 0.5, false, e -> e instanceof AgeableMob)) {
                            AgeableMob mob = (AgeableMob) e;
                            if (!mob.isBaby() || !Targeting.mayAffect(c.inst(), mob)) continue;
                            if (!c.inst().spend(2, "grow")) return null;
                            mob.setAge(Math.min(0, mob.getAge() + 6000));
                            Vfx.burst(level, Targeting.center(mob), Element.LIFE, 8);
                        }
                    }
                    return null;
                });
        def("world-weather", EFFECT, ACTION).p("weather", WEATHER).opt("seconds", NUM, "600").stab(3)
                .doc("全域天气：改变整个世界的天气（所有有天气的维度共用同一种天气），持续若干秒（1～1200，默认 600），之后按原版规律自然变化。"
                        + "按每次改变计费：晴或雨 100 魔力，雷暴 200 魔力。不能下雪，也不能在没有天气的维度施放；被拒绝时不消耗魔力")
                .exec((n, c) -> {
                    Weather w = eval(n.arg(0), c) instanceof Weather x ? x : Weather.RAIN;
                    if (w == Weather.SNOW) {
                        c.inst().terminate("全域天气不能下雪：原版天气无法让全世界下雪，请用区域天气");
                        return null;
                    }
                    if (!c.level().canHaveWeather()) {
                        c.inst().terminate("这个维度没有天气");
                        return null;
                    }
                    int ticks = (int) Math.round(clamp(num(n.arg(1), c), 1, 1200) * 20);
                    if (!c.inst().spend(w == Weather.THUNDER ? 200 : 100, "world-weather")) return null;
                    var server = c.level().getServer();
                    switch (w) {
                        case CLEAR -> server.setWeatherParameters(ticks, 0, false, false);
                        case RAIN -> server.setWeatherParameters(0, ticks, true, false);
                        default -> server.setWeatherParameters(0, ticks, true, true);
                    }
                    Vfx.sound(c.level(), c.inst().caster.getEyePosition(), net.minecraft.sounds.SoundEvents.BEACON_POWER_SELECT, 1f, 0.7f);
                    return null;
                });
        def("weather", EFFECT, ACTION).p("weather", WEATHER).kw("at", POS, "here").kw("radius", NUM, "8").kw("life", NUM, "30").flag("follow").stab(4)
                .doc("区域天气：在以某处为中心的圆柱区域内改变天气，区域外照旧。雨在寒冷的群系里是雪；雪在任何群系都下，地面积雪、水面结冰；"
                        + "晴让区域内不下雨；雷暴在区域内落雷，但不落在施法者和友方附近。展开 10 魔力，之后每秒维持 0.02 × 半径² 魔力（雷暴加倍），"
                        + "魔力耗尽即消散。半径 1～48（默认 8），持续 1～600 秒（默认 30）。:follow 让区域跟随施法者。没有天气的维度不能施放（不消耗魔力）")
                .exec((n, c) -> {
                    Weather w = eval(n.arg(0), c) instanceof Weather x ? x : Weather.RAIN;
                    Place at = place(kw(n, "at"), c);
                    if (!at.level().canHaveWeather()) {
                        c.inst().terminate("这个维度没有天气");
                        return null;
                    }
                    double radius = clamp(num(kw(n, "radius"), c), 1, 48);
                    int life = (int) Math.round(clamp(num(kw(n, "life"), c), 1, 600) * 20);
                    if (!c.inst().spend(AreaWeatherObj.SETUP, "weather")) return null;
                    AreaWeatherObj.start(n, c.at(at), at.pos(), w, radius, life, n.flags.contains("follow"));
                    return null;
                });
        def("time-skip", EFFECT, ACTION).p("seconds", NUM).stab(3)
                .doc("时间加速：把所在维度的时间向前推进若干秒（最多 36000），越推越快直到上限，临近终点时急刹。"
                        + "有日夜的维度推进日夜，同时加快该维度的随机刻（作物、树苗、冰雪）；没有日夜的维度只加快随机刻。"
                        + "边推进边付费，每推进 1 秒 4 魔力，魔力耗尽即停。同一时钟同时只能有一个时间加速")
                .exec((n, c) -> {
                    double s = clamp(num(n.arg(0), c), 0, 36000);
                    if (s > 0) TimeSkipObj.start(n, c, c.here(), s);
                    return null;
                });
        def("decay", EFFECT, ACTION).eachBlock("at").opt("radius", NUM, "1").stab(2)
                .doc("腐败：有 age 属性的方块倒退一阶；树叶像自然凋落一样掉落；草、花、树苗被毁掉；生物变得更年幼（成年变回幼年）。每个对象 2 魔力")
                .exec((n, c) -> {
                    double r = clamp(num(n.arg(1), c), 0, 6);
                    for (Place center : places(n.arg(0), c)) {
                        ServerLevel level = center.level();
                        for (BlockPos pos : sphere(center, r)) {
                            Place p = new Place(level, Vec3.atCenterOf(pos));
                            BlockState s = level.getBlockState(pos);
                            if (s.isAir() || !mayEdit(c, p)) continue;
                            IntegerProperty age = ageOf(s);
                            if (age != null && s.getValue(age) > java.util.Collections.min(age.getPossibleValues())) {
                                if (!c.inst().spend(2, "decay")) return null;
                                level.setBlock(pos, s.setValue(age, s.getValue(age) - 1), Block.UPDATE_ALL);
                            } else if (s.getBlock() instanceof LeavesBlock) {
                                if (!c.inst().spend(2, "decay")) return null;
                                // What LeavesBlock does when it decays naturally: drop its loot, vanish.
                                Block.dropResources(s, level, pos);
                                level.removeBlock(pos, false);
                            } else if (isPlantDebris(s)) {
                                if (!c.inst().spend(2, "decay")) return null;
                                level.destroyBlock(pos, true, c.inst().caster);
                            } else {
                                continue;
                            }
                            Vfx.burst(level, p.pos(), Element.VOID, 4);
                        }
                        for (Entity e : Targeting.near(c.inst(), level, center.pos(), r + 0.5, false, e -> e instanceof AgeableMob)) {
                            AgeableMob mob = (AgeableMob) e;
                            if (!Targeting.mayAffect(c.inst(), mob)) continue;
                            if (!c.inst().spend(2, "decay")) return null;
                            mob.setAge(mob.getAge() - 6000);
                            Vfx.burst(level, Targeting.center(mob), Element.VOID, 8);
                        }
                    }
                    return null;
                });
        def("break-block", EFFECT, ACTION).eachBlock("block").stab(2)
                .doc("破坏方块并掉落（不可破坏的方块无效）。每个 2 + 硬度×2 魔力")
                .exec((n, c) -> {
                    for (Place p : places(n.arg(0), c)) {
                        BlockState s = p.level().getBlockState(p.block());
                        float hardness = s.getDestroySpeed(p.level(), p.block());
                        if (s.isAir() || hardness < 0 || !mayEdit(c, p)) continue;
                        if (!c.inst().spend(2 + hardness * 2, "break-block")) return null;
                        p.level().destroyBlock(p.block(), true, c.inst().caster);
                    }
                    return null;
                });
        def("lightning", EFFECT, ACTION).eachBlock("at").stab(2)
                .doc("落雷：在该位置召下一道闪电，和自然的雷电一样会点火、伤害周围生物，但不会伤到施法者和友方。"
                        + "可以连接方块群，每处各落一道。每道 50 魔力；落点在下雨时 40，在雷暴中只要 20（区域天气覆盖时按区域天气，否则按世界天气）。"
                        + "没有编辑权限的位置不落雷，也不消耗魔力")
                .exec((n, c) -> {
                    for (Place p : places(n.arg(0), c)) {
                        Place ground = Place.center(p.level(), p.block());
                        if (!mayEdit(c, ground)) continue;
                        if (!c.inst().spend(Lightning.cost(p.level(), p.block()), "lightning")) return null;
                        Lightning.strike(c.inst(), p.level(), Vec3.atBottomCenterOf(p.block()));
                        c.inst().touch(p.level(), ground.pos());
                    }
                    return null;
                });
        def("set-fire", EFFECT, ACTION).eachBlock("block").stab(1)
                .doc("在该位置（若是实心方块则在它上方）点火。每处 2 魔力")
                .exec((n, c) -> {
                    for (Place p : places(n.arg(0), c)) {
                        BlockPos pos = p.level().getBlockState(p.block()).isAir() ? p.block() : p.block().above();
                        if (!BaseFireBlock.canBePlacedAt(p.level(), pos, Direction.UP) || !mayEdit(c, Place.center(p.level(), pos))) continue;
                        if (!c.inst().spend(2, "set-fire")) return null;
                        p.level().setBlock(pos, BaseFireBlock.getState(p.level(), pos), Block.UPDATE_ALL);
                    }
                    return null;
                });
    }

    private static Object impulse(Node n, Ctx c, boolean away) {
        double s = clamp(num(n.arg(1), c), 0, 4);
        Vec3 p = vec(kw(n, away ? "from" : "to"), c);
        for (Entity t : anyEntities(n.arg(0), c)) {
            if (!c.inst().spend(s * 1.5, away ? "push" : "pull")) return null;
            Vec3 d = away ? t.position().subtract(p) : p.subtract(t.position());
            double strength = away ? s : Math.min(s, d.length() * 0.25);
            Effects.impulse(c.inst(), t, d, strength);
            // FORCE faces the way the force acts: away from where it seems to come from. Dropped items get
            // none: a pull sweeping up loot would set off one per item.
            if (!(t instanceof ItemEntity) && d.lengthSqr() > 1e-6 && t.level() instanceof ServerLevel level)
                Vfx.burst(level, t, Targeting.center(t).subtract(d.normalize()), Element.PHYSICAL, Vfx.Burst.FORCE);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Object execute(Node n, Ctx c) {
        if (!(entity(n.arg(0), c) instanceof LivingEntity t) || !t.isAlive() || !Targeting.mayAffect(c.inst(), t)) return null;
        List<Fact> proof = n.meta instanceof List<?> l ? (List<Fact>) l : List.of();
        SpellInstance inst = c.inst();
        ServerLevel casterLevel = lv(inst.caster);
        Fact broken = ExecuteCost.firstBroken(t, proof);
        if (broken != null) {
            // The premise no longer holds: the world rejects the proof and the spell collapses on its author.
            inst.caster.hurtServer(casterLevel, inst.caster.damageSources().magic(), 4);
            Vfx.burst(casterLevel, inst.caster, null, Element.VOID, Vfx.Burst.DAMAGE);
            inst.terminate("悖论：前提 " + broken.describe() + " 已不成立，术式反噬");
            return null;
        }
        double cost = ExecuteCost.cost(t, proof);
        if (!inst.spend(cost, "execute(需要 " + Node.fmt(cost) + ")")) return null;
        if (t.level() == inst.caster.level()) Vfx.line(lv(t), Targeting.center(inst.caster), Targeting.center(t), Element.VOID);
        Vfx.execute(lv(t), Targeting.center(t));
        Vfx.sound(lv(t), t.position(), SoundEvents.WITHER_SHOOT, 0.7f, 0.6f);
        if (inst.caster instanceof ServerPlayer sp) {
            sp.sendSystemMessage(Component.literal("∴ 处决 " + t.getName().getString() + " · 代价 " + Node.fmt(cost)
                    + (proof.isEmpty() ? "（无证明）" : " · 证明 " + proof.stream().map(Fact::describe).toList())).withStyle(ChatFormatting.DARK_PURPLE));
        }
        // A colossus is not killed by a proof: its mana is wiped and it powers down (a frenzy burns out).
        if (t instanceof dev.logos.runtime.AlchemicalColossus colossus) {
            colossus.drainAll();
            return null;
        }
        if (Effects.kill(inst, t)) fire(n, "on-kill", c.on(t, Targeting.center(t)));
        return null;
    }

    // ---- 律 Logic ----------------------------------------------------------------------------

    private static void logic() {
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
                        c.inst().terminate("法则失稳：递归超过 " + LogosConfig.maxRecursion() + " 层");
                        return null;
                    }
                    if (!c.inst().spend(3, "recurse")) return null;
                    run(c.inst().spell.body(), c.deeper());
                    return null;
                });
        def("stop", LOGIC, ACTION).doc("立即熄灭整个术式")
                .exec((n, c) -> {
                    c.inst().stop("术式自行终止 (stop)");
                    return null;
                });
    }

    /** One iteration of a timed repeat; the next is scheduled only after this one runs, so a huge count never queues up. */
    private static void repeatEvery(Node n, Ctx c, long k, long times, int every) {
        if (c.inst().dead || !c.inst().spend(1, "repeat")) return;
        run(n.body, c.withIndex(k));
        if (k + 1 < times && !c.inst().dead) SpellRuntime.schedule(c.inst(), every, () -> repeatEvery(n, c, k + 1, times, every));
    }

    // ---- 数 Values ---------------------------------------------------------------------------

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

    private static void values() {
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
        def("random", VALUE, NUM).opt("max", NUM, "1").doc("0~max 的随机数")
                .exec((n, c) -> c.inst().level.getRandom().nextDouble() * num(n.arg(0), c));
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
                    return filter(n.arg(1), c).testBlock(p.level().getBlockState(p.block()));
                });
        def("is-air", VALUE, BOOL).p("pos", POS).doc("该位置是否为空气")
                .exec((n, c) -> {
                    Place p = place(n.arg(0), c);
                    return p.level().getBlockState(p.block()).isAir();
                });
        def("block-count", VALUE, NUM).p("blocks", BLOCKS).doc("方块数量").exec((n, c) -> (double) places(n.arg(0), c).size());
        def("alive", VALUE, BOOL).p("entity", ENTITY).doc("实体存在且存活")
                .exec((n, c) -> entity(n.arg(0), c) instanceof LivingEntity le && le.isAlive());
    }
}
