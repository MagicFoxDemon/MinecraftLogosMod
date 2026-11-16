package dev.logos.lang;

import static dev.logos.lang.Category.*;
import static dev.logos.lang.Runes.*;
import static dev.logos.lang.Type.*;
import static dev.logos.runtime.Interp.*;

import java.util.ArrayList;
import java.util.List;

import dev.logos.runtime.Ctx;
import dev.logos.runtime.Group;
import dev.logos.runtime.Filter;
import dev.logos.runtime.Place;
import dev.logos.runtime.SpellObject;
import dev.logos.runtime.SpellWorld;
import dev.logos.runtime.Spots;
import dev.logos.runtime.StatusData;
import dev.logos.runtime.Targeting;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 汞 Mercury / Binding: what a spell points at. Selectors and filters, places and directions. */
final class BindingRunes {
    private BindingRunes() {}

    /** A group selector's {@code :radius}, held to {@code maxGroupRadius}. */
    private static double groupRadius(Node n, Ctx c) {
        return Group.clampRadius(num(kw(n, "radius"), c));
    }

    static void bindings() {
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
        def("nearest-player", BINDING, ENTITY).kw("at", POS, "here").kw("radius", NUM, "10").flag("fresh")
                .stab(1).doc("最近的其他玩家（可能为空；不含施法者，在领域里时也包括施法者自己）。启式没有开启 :players 时总是空，也不花魔力。"
                        + ":fresh 跳过本法术已经影响过的玩家。1 魔力")
                .exec((n, c) -> {
                    if (!c.inst().spell.affectsPlayers()) return null;
                    if (!c.inst().spend(1, "nearest-player")) return null;
                    Place at = place(kw(n, "at"), c);
                    double r = num(kw(n, "radius"), c);
                    var l = Targeting.withCaster(c, Targeting.near(c.inst(), at.level(), at.pos(), r, n.flags.contains("fresh"),
                            e -> e instanceof Player && e != c.inst().caster && e.isAlive() && !e.isSpectator()), at, r, e -> e instanceof Player);
                    return l.isEmpty() ? null : l.getFirst();
                });
        def("master", BINDING, ENTITY)
                .doc("施法者的主人：魔力生命体心核里的术式用它指向召唤它的玩家，例如 (heal (master) 4)；被驯服的施法生物（如拿着法杖的女仆）指向它的主人。玩家自己施法时就是施法者本人。免费")
                .exec((n, c) -> {
                    java.util.UUID m = dev.logos.runtime.Familiars.sideOf(c.inst().caster);
                    return m == null ? c.inst().caster : c.inst().caster.level().getPlayerByUUID(m);
                });
        def("foe", BINDING, ENTITY)
                .doc("施法者正在战斗的对象：魔力生命体普攻时就是它对准的那个，例如普攻页写 (damage (foe) 6)；不是使魔的施法生物（如女仆）是它自己的攻击目标。没有时为空（玩家施法时总是空）。免费")
                .exec((n, c) -> c.inst().caster instanceof net.minecraft.world.entity.Mob m
                        ? dev.logos.runtime.Familiars.isFamiliar(m) ? dev.logos.runtime.Familiars.orderedTarget(m) : m.getTarget() : null);
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
        def("species", BINDING, KIND).p("entity", ENTITY)
                .doc("族属：读取实体的生物注册 ID，输出种类。接入种类筛选后只匹配相同 ID 的实体，也支持其他模组的生物；不判断阵营。实体为空时不匹配任何对象。免费")
                .exec((n, c) -> {
                    Entity e = entity(n.arg(0), c);
                    return e == null ? null : e.getType();
                });
        def("kind", BINDING, FILTER).p("kind", KIND)
                .doc("目标种类：" + TargetKind.words() + "，或族属读取的生物种类。接到任何 :filter 上；只有 all 能筛选方块。种类为空时不匹配任何对象。免费")
                .exec((n, c) -> switch (eval(n.arg(0), c)) {
                    case TargetKind k -> Filter.of(k);
                    case net.minecraft.world.entity.EntityType<?> type -> Filter.entityType(type);
                    case null, default -> Filter.of(TargetKind.NONE);
                });
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
        def("pos-of", BINDING, POS).p("entity", BODY).doc("实体的中心位置；接形体（此形）时是它所在处，光柱是它发出的起点")
                .exec((n, c) -> switch (eval(n.arg(0), c)) {
                    case SpellObject o -> new Place(o.level(), o.position());
                    case Entity e -> new Place(lv(e), Targeting.center(e));
                    case null, default -> c.place();
                });
        def("dir-of", BINDING, DIR).p("of", BODY)
                .doc("朝向：实体的视线方向；形体的朝向——魔弹飞行的方向、光柱的方向、炼成体正面的朝向（领域朝上）。没有时取当前朝向。免费")
                .exec((n, c) -> switch (eval(n.arg(0), c)) {
                    case SpellObject o -> o.facing();
                    case Entity e -> e.getLookAngle();
                    case null, default -> c.aim();
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
        def("offset", BINDING, POS).p("forward", NUM).opt("up", NUM, "0").opt("right", NUM, "0").kw("from", POS, "here").kw("dir", DIR, "aim")
                .doc("相对一个位置的前、上、右若干格。前取方向的水平部分（玩家是视线的水平方向，术式发射器是它的朝向；朝上或朝下的发射器以南为前），上永远是正上方，右在前的右手边。两脚规量出的就是这三个数")
                .exec((n, c) -> {
                    Place p = place(kw(n, "from"), c);
                    Vec3 forward = dev.logos.runtime.Offsets.forward(vec(kw(n, "dir"), c), c.inst().caster.getYRot());
                    return p.moved(dev.logos.runtime.Offsets.apply(p.pos(), forward, num(n.arg(0), c), num(n.arg(1), c), num(n.arg(2), c)));
                });
        def("mix-pos", BINDING, POS).p("a", POS).p("b", POS).p("alpha", NUM)
                .doc("位置调和：按比例 alpha 在 a、b 两处之间取位置，0 是 a，1 是 b，0.5 是连线中点；小于 0 或大于 1 时沿连线越过两端。结果在 a 的维度")
                .exec((n, c) -> {
                    Place a = place(n.arg(0), c);
                    return a.moved(a.pos().lerp(vec(n.arg(1), c), num(n.arg(2), c)));
                });
        def("spot", BINDING, POS).kw("at", POS, "here").kw("radius", NUM, "4").kw("in", Type.FORM, null)
                .choice("shape", "ball", "disk", "cube").choice("where", "inside", "surface").flag("ground")
                .doc("择位：随机取一处位置，每次求值重新抽取。不接 :in 时在 :at 周围 :radius 格内：:shape ball 球、disk 水平圆盘、cube 立方；"
                        + ":where inside 取内部，surface 取表面（球面、圆盘边缘一圈、立方的六个面）。"
                        + ":in 接一个形体时在它里面取：领域的内部是它的球，表面是它的法阵平面；炼成体按它的实际形状取内部或表面；其他形体取它所在处，没有形体时取此处。"
                        + ":ground 把取到的点落到同一竖列最近的可站立处（适合召唤、闪现）。免费")
                .exec((n, c) -> {
                    boolean surface = choice(n, "where").equals("surface");
                    var rand = c.inst().level.getRandom();
                    Place p;
                    if (kw(n, "in") != null) {
                        // No body (its form was refused): here, as pos-of does for a missing entity.
                        p = eval(kw(n, "in"), c) instanceof SpellObject form ? new Place(form.level(), Spots.in(rand, form, surface)) : c.place();
                    } else {
                        Place at = place(kw(n, "at"), c);
                        p = at.moved(Spots.around(rand, at.pos(), clamp(num(kw(n, "radius"), c), 0, 64), choice(n, "shape"), surface));
                    }
                    return n.flags.contains("ground") && SpellWorld.loaded(p.level(), p.pos()) ? p.moved(safeSpot(p.level(), p.pos())) : p;
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
                        c.inst().terminate(Component.translatable("logos.spell.end.no_dimension", id));
                        return from;
                    }
                    if (!dev.logos.runtime.Attunement.reaches(c.inst().caster, target)) {
                        c.inst().terminate(Component.translatable("logos.spell.end.unvisited_dimension", id));
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
        return SpellWorld.clip(lv(caster), eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, caster);
    }

    private static ServerLevel dimension(Ctx c, String id) {
        Identifier key = Identifier.tryParse(id);
        return key == null ? null : c.level().getServer().getLevel(ResourceKey.create(Registries.DIMENSION, key));
    }
}
