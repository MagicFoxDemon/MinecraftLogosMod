package dev.logos.lang;

import static dev.logos.lang.Category.*;
import static dev.logos.lang.Runes.*;
import static dev.logos.lang.Type.*;
import static dev.logos.runtime.Interp.*;

import java.util.ArrayList;
import java.util.List;

import dev.logos.block.SpellEmitterBlockEntity;
import dev.logos.runtime.AreaWeatherObj;
import dev.logos.runtime.Ctx;
import dev.logos.runtime.Lightning;
import dev.logos.runtime.ManaInfusion;
import dev.logos.runtime.ManaVessel;
import dev.logos.runtime.Place;
import dev.logos.runtime.SpellWorld;
import dev.logos.runtime.Targeting;
import dev.logos.runtime.TimeSkipObj;
import dev.logos.runtime.Vfx;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * 硫 · blocks: spells that change the world itself. These are effect runes too: {@link EffectRunes#effects} registers
 * them part-way through its own, where they sit in the rune table.
 */
final class BlockEffectRunes {
    private BlockEffectRunes() {}

    /** Whether the caster may change this block (spawn protection, claims, adventure mode…). */
    private static boolean mayEdit(Ctx c, Place p) {
        return !(c.inst().caster instanceof ServerPlayer sp) || Targeting.mayEdit(sp, p.level(), p.block());
    }

    private static IntegerProperty ageOf(BlockState state) {
        for (Property<?> prop : state.getProperties()) {
            if (prop instanceof IntegerProperty ip && prop.getName().equals("age")) return ip;
        }
        return null;
    }

    private static boolean isPlantDebris(BlockState s) {
        return s.is(FRAGILE_PLANTS);
    }

    /** Every block position within {@code radius} of {@code center}. */
    private static List<BlockPos> sphere(Place center, double radius) {
        int r = (int) Math.ceil(radius);
        BlockPos o = center.block();
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(o.offset(-r, -r, -r), o.offset(r, r, r))) {
            if (Vec3.atCenterOf(p).distanceTo(center.pos()) <= radius + 0.5 && SpellWorld.loaded(center.level(), p)) out.add(p.immutable());
        }
        return out;
    }

    static void blockEffects() {
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
        def("use-block", EFFECT, ACTION).eachBlock("block").kw("item", LOOK, null).stab(2)
                .doc("无形之手：像施法者对着该方块按一下右键——按下按钮、扳动拉杆、开关门和活板门、调中继器……"
                        + "不会为施法者打开箱子、熔炉之类的界面。默认空手；:item 指定拿着某物品使用，背包里要有这件物品，"
                        + "用掉了就真的扣掉（骨粉、打火石的耐久、装满水的桶换回来的空桶都算）。术式发射器没有背包，总是空手。"
                        + "从朝向施法者的那一面点。每次起了作用 2 魔力，没起作用不消耗；施法者不是玩家时无效")
                .exec((n, c) -> {
                    if (!(c.inst().caster instanceof ServerPlayer caster)) return null;
                    Node itemNode = kw(n, "item");
                    net.minecraft.world.item.Item item = null;
                    if (itemNode != null && !(caster instanceof SpellEmitterBlockEntity.Shadow)) {
                        Identifier id = Identifier.tryParse(text(itemNode, c));
                        item = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                        if (item == null) return null;
                    }
                    var inventory = caster.getInventory();
                    for (Place p : places(n.arg(0), c)) {
                        if (!mayEdit(c, p)) continue;
                        if (2 > c.inst().mana + 1e-9) {
                            c.inst().spend(2, "use-block");
                            return null;
                        }
                        int slot = -1;
                        if (item != null) {
                            for (int i = 0; i < inventory.getContainerSize() && slot < 0; i++) {
                                if (inventory.getItem(i).is(item)) slot = i;
                            }
                            if (slot < 0) return null;
                        }
                        // A stand-in clicks for them: it opens no screens, and hands back whatever it ends up holding.
                        FakePlayer hand = FakePlayerFactory.get(p.level(), caster.getGameProfile());
                        hand.snapTo(caster.getX(), caster.getY(), caster.getZ(), caster.getYRot(), caster.getXRot());
                        hand.setYHeadRot(caster.getYHeadRot());
                        if (hand.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) hand.setGameMode(GameType.SURVIVAL);
                        hand.getInventory().clearContent();
                        ItemStack held = slot < 0 ? ItemStack.EMPTY : inventory.removeItemNoUpdate(slot);
                        hand.setItemInHand(InteractionHand.MAIN_HAND, held);
                        Vec3 centre = Vec3.atCenterOf(p.block());
                        Direction face = Direction.getApproximateNearest(caster.getEyePosition().subtract(centre));
                        BlockHitResult hit = new BlockHitResult(centre.add(face.getUnitVec3().scale(0.5)), face, p.block(), false);
                        InteractionResult result = hand.gameMode.useItemOn(hand, p.level(), held, InteractionHand.MAIN_HAND, hit);
                        ItemStack back = hand.getMainHandItem();
                        hand.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        if (slot >= 0 && inventory.getItem(slot).isEmpty()) inventory.setItem(slot, back);
                        else if (!back.isEmpty()) inventory.placeItemBackInInventory(back);
                        for (int i = 0; i < hand.getInventory().getContainerSize(); i++) {
                            ItemStack extra = hand.getInventory().removeItemNoUpdate(i);
                            if (!extra.isEmpty()) inventory.placeItemBackInInventory(extra);
                        }
                        inventory.setChanged();
                        if (!result.consumesAction()) continue;
                        c.inst().spend(2, "use-block");
                        Vfx.burst(p.level(), hit.getLocation(), Element.ARCANE, 4);
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
                        c.inst().terminate(Component.translatable("logos.spell.end.no_global_snow"));
                        return null;
                    }
                    if (!c.level().canHaveWeather()) {
                        c.inst().terminate(Component.translatable("logos.spell.end.no_weather"));
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
                        + "晴让区域内不下雨；雷暴在区域内落雷。展开 10 魔力，之后每秒维持 0.02 × 半径² 魔力（雷暴加倍），"
                        + "魔力耗尽即消散。半径 1～48（默认 8），持续 1～600 秒（默认 30）。:follow 让区域跟随施法者。没有天气的维度不能施放（不消耗魔力）")
                .exec((n, c) -> {
                    Weather w = eval(n.arg(0), c) instanceof Weather x ? x : Weather.RAIN;
                    Place at = place(kw(n, "at"), c);
                    if (!at.level().canHaveWeather()) {
                        c.inst().terminate(Component.translatable("logos.spell.end.no_weather"));
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
                .doc("落雷：在该位置召下一道闪电，和自然的雷电一样会点火、伤害周围生物。"
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
        def("infuse-block", EFFECT, ACTION).eachBlock("at").p("amount", NUM).stab(2)
                .doc("灌魔：把本次法术的魔力灌进该位置能容纳魔力的方块，如炼金术桌（两半都算）。"
                        + "可以连接方块群，每处各灌一次、分别付费。只灌方块还装得下的部分，已满、容纳不了魔力或没有编辑权限的位置不花魔力。每灌 1 点耗 1.1 魔力")
                .exec((n, c) -> {
                    double amt = Math.max(0, num(n.arg(1), c));
                    for (Place p : places(n.arg(0), c)) {
                        Place at = Place.center(p.level(), p.block());
                        ManaVessel vessel = ManaInfusion.vesselAt(p.level(), p.block());
                        if (vessel == null || !mayEdit(c, at)) continue;
                        double give = Math.min(amt, vessel.maxMana() - vessel.mana());
                        if (give <= 0) continue;
                        if (!c.inst().spend(give * ManaInfusion.COST, "infuse-block")) return null;
                        vessel.receiveMana(give);
                        c.inst().touch(p.level(), at.pos());
                        if (p.level() == c.inst().caster.level()) Vfx.line(p.level(), Targeting.center(c.inst().caster), at.pos(), Element.ARCANE);
                    }
                    return null;
                });
    }
}
