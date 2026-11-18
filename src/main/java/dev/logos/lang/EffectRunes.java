package dev.logos.lang;

import static dev.logos.lang.Category.*;
import static dev.logos.lang.Runes.*;
import static dev.logos.lang.Type.*;
import static dev.logos.runtime.Interp.*;

import java.util.List;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.runtime.ConstructEntity;
import dev.logos.runtime.Ctx;
import dev.logos.runtime.Effects;
import dev.logos.runtime.Group;
import dev.logos.runtime.ManaInfusion;
import dev.logos.runtime.Place;
import dev.logos.runtime.Resized;
import dev.logos.runtime.SpellInstance;
import dev.logos.runtime.SpellObject;
import dev.logos.runtime.SpellRuntime;
import dev.logos.runtime.StatusData;
import dev.logos.runtime.Targeting;
import dev.logos.runtime.TimeStop;
import dev.logos.runtime.Vfx;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * 硫 Sulfur / Effect: what a spell does to what it reaches. The effects that change blocks and the world itself are in
 * {@link BlockEffectRunes}; {@link #effects} registers them part-way through its own, where they sit in the rune table.
 */
final class EffectRunes {
    private static final List<EquipmentSlot> EQUIPMENT_ORDER = List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private EffectRunes() {}

    /** What disarm takes first: main hand, off hand, then head, chest, legs, feet; armour bound by a curse stays. */
    private static EquipmentSlot disarmSlot(LivingEntity t) {
        for (EquipmentSlot slot : EQUIPMENT_ORDER) {
            ItemStack s = t.getItemBySlot(slot);
            if (s.isEmpty()) continue;
            if (slot.isArmor() && net.minecraft.world.item.enchantment.EnchantmentHelper.has(s,
                    net.minecraft.world.item.enchantment.EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) continue;
            return slot;
        }
        return null;
    }

    /** Equip fills the first empty slot, even when the item would normally belong in another slot. */
    private static EquipmentSlot emptyEquipmentSlot(LivingEntity t) {
        for (EquipmentSlot slot : EQUIPMENT_ORDER) if (t.getItemBySlot(slot).isEmpty()) return slot;
        return null;
    }

    /** The same inventory search as anchor nails; the actual stack keeps its components when transferred. */
    private static ItemStack equipmentMaterial(ServerPlayer caster, net.minecraft.world.item.Item item) {
        var inventory = caster.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) return stack;
        }
        return ItemStack.EMPTY;
    }

    /**
     * Group effects pay once, however many targets there are: a multiple of the single-target price
     * that grows with the radius {@code group} was gathered in (see {@link Group#costMultiplier}).
     */
    private static boolean spendGroup(Ctx c, List<Entity> group, int targets, double singleCost, String what) {
        return targets > 0 && c.inst().spend(singleCost * Group.costMultiplier(Group.radiusOf(group)), what);
    }

    static void effects() {
        def("damage", EFFECT, ACTION).each("target").p("amount", NUM).opt("element", ELEMENT, "physical")
                .on("on-kill", true, "目标因此死亡时").stab(2)
                .doc("造成伤害。每点伤害消耗：火0.7 物理0.8 冰/奥秘1.0 雷1.1 虚空1.5。"
                        + "虚空是真实伤害：无视护甲、盾牌、保护附魔、抗性提升、魔法抗性和生物自身的减伤，默认也无视伤害吸收，但伤不到创造模式玩家")
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
                        c.inst().caster.heal((float) (amt / 2 * c.inst().modifiers.heal()));
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
                        t.heal((float) (amt * c.inst().modifiers.heal()));
                        Vfx.burst(lv(t), t, null, Element.LIFE, Vfx.Burst.HEAL);
                    }
                    return null;
                });
        def("shield", EFFECT, ACTION).each("target").p("amount", NUM).stab(2).doc("护盾（伤害吸收），可叠加，不设上限。每点 1.5 魔力")
                .exec((n, c) -> {
                    double amt = Math.max(0, num(n.arg(1), c));
                    for (LivingEntity t : living(n.arg(0), c)) {
                        if (!c.inst().spend(amt * 1.5, "shield")) return null;
                        Effects.shield(t, amt * c.inst().modifiers.heal());
                        Vfx.burst(lv(t), t, null, Element.LIFE, Vfx.Burst.WARD);
                    }
                    return null;
                });
        def("infuse", EFFECT, ACTION).each("target").p("amount", NUM).stab(2)
                .doc("渡魔：把本次法术的魔力渡给目标——玩家、魔力生命体、巨像这类能容纳魔力的生物。"
                        + "只渡目标还装得下的部分，已满或不能容纳魔力的目标不花魔力。每渡 1 点耗 1.1 魔力")
                .exec((n, c) -> {
                    double amt = Math.max(0, num(n.arg(1), c));
                    for (LivingEntity t : living(n.arg(0), c)) {
                        double give = Math.min(amt, ManaInfusion.room(t));
                        if (give <= 0) continue;
                        if (!c.inst().spend(give * ManaInfusion.COST, "infuse")) return null;
                        ManaInfusion.give(t, give);
                        if (t.level() == c.inst().caster.level()) Vfx.line(lv(t), Targeting.center(c.inst().caster), Targeting.center(t), Element.ARCANE);
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
                        + "消耗与目标数无关：半径 5 以内为单体的 3 倍（每点火2.1 物理2.4 冰/奥秘3.0 雷3.3 虚空4.5），"
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
                        ItemEntity drop = t.spawnAtLocation(stack, (float) (t.getBbHeight() * 0.6));
                        if (drop != null) drop.setPickUpDelay(40);
                        c.inst().affected.add(t.getUUID());
                        Vfx.sound(lv(t), Targeting.center(t), SoundEvents.ITEM_BREAK, 0.6f, 1.6f);
                        Vfx.burst(lv(t), t, c.inst().caster.level() == t.level() ? c.inst().caster.getEyePosition() : null, Element.PHYSICAL, Vfx.Burst.FORCE);
                    }
                    return null;
                });
        def("equip", EFFECT, ACTION).each("target").p("item", LOOK).stab(2)
                .doc("授装：从施法者背包取出一个指定物品，强制装备到目标的第一个空位。"
                        + "依次检查主手、副手、头、胸、腿、脚，不覆盖已有装备，也不限制物品通常适用的栏位。"
                        + "每个成功目标消耗一件实物和 8 魔力；保留物品附魔、耐久等数据，创造模式也需要实物。"
                        + "没有空位、没有材料或施法者不是玩家时跳过，不消耗魔力")
                .exec((n, c) -> {
                    if (!(c.inst().caster instanceof ServerPlayer caster)) return null;
                    ResourceLocation id = ResourceLocation.tryParse(text(n.arg(1), c));
                    var item = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                    if (item == null) return null;
                    for (LivingEntity t : living(n.arg(0), c)) {
                        EquipmentSlot slot = emptyEquipmentSlot(t);
                        if (slot == null) continue;
                        ItemStack material = equipmentMaterial(caster, item);
                        if (material.isEmpty()) return null;
                        if (!c.inst().spend(8, "equip")) return null;
                        t.setItemSlot(slot, material.split(1));
                        caster.getInventory().setChanged();
                        c.inst().affected.add(t.getUUID());
                        Vfx.sound(lv(t), Targeting.center(t), SoundEvents.ARMOR_EQUIP_GENERIC.value(), 0.6f, 1.2f);
                        Vfx.burst(lv(t), t, caster.level() == t.level() ? caster.getEyePosition() : null, Element.PHYSICAL, Vfx.Burst.FORCE);
                    }
                    return null;
                });
        def("potion", EFFECT, ACTION).each("target").p("effect", Type.MOB_EFFECT).p("seconds", NUM).opt("level", NUM, "1").stab(2)
                .doc("药水效果：按注册名施加，原版和模组的都可以（如 speed、minecraft:regeneration、模组id:效果名）。"
                        + "等级从 1 开始，最高 10。持续效果每秒每级 0.8 魔力，最长 600 秒；瞬间效果（instant_health 等）每级 6 魔力")
                .exec((n, c) -> {
                    var effect = net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse((String) n.arg(1).value)).orElse(null);
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
        def("resize", EFFECT, ACTION).each("target").p("scale", NUM).opt("seconds", NUM, "30").stab(2)
                .doc("缩放：把生物的大小乘上一个倍数（原版的 scale 属性），持续若干秒（最长 600，默认 30）后恢复。"
                        + "倍数 1/16～16，按每翻一倍 16 档取最近的一档；碰撞箱、视角高度、步幅随之改变。再次缩放会取代上一次，倍数 1 即恢复原样。"
                        + "牛奶能解除。每个目标 2 魔力，另加每秒 0.2 ×（放大或缩小的倍数 − 1）")
                .exec((n, c) -> {
                    int amplifier = Resized.amplifierFor(num(n.arg(1), c));
                    double scale = Resized.scaleOf(amplifier);
                    double s = clamp(num(n.arg(2), c), 0, 600);
                    double cost = 2 + s * 0.2 * (Math.max(scale, 1 / scale) - 1);
                    int ticks = Math.max(1, (int) Math.round(s * 20));
                    for (LivingEntity t : living(n.arg(0), c)) {
                        if (!c.inst().spend(cost, "resize")) return null;
                        // Removed first: vanilla would keep a larger amplifier over a smaller one.
                        t.removeEffect(LogosMod.RESIZED);
                        if (amplifier != Resized.ONE) t.addEffect(new MobEffectInstance(LogosMod.RESIZED, ticks, amplifier, false, false, false), c.inst().caster);
                        c.inst().affected.add(t.getUUID());
                        Vfx.burst(lv(t), t, null, Element.ARCANE, Vfx.Burst.FORCE);
                    }
                    return null;
                });
        def("water-walk", EFFECT, ACTION).each("target").opt("seconds", NUM, "60").stab(1)
                .doc("水上行走（状态 water_walking），持续若干秒（最长 600，默认 60）：任何流体的表面都像地面一样托住目标，"
                        + "水、熔岩、模组流体，静止的、流动的，含水方块里的水都算，可以在上面走、跑、跳。从高处落到流体表面，和落在地上一样受到摔落伤害。"
                        + "潜行沉入流体；不潜行时泡在流体里会浮回表面。站在熔岩表面不会着火，掉进去照样会。牛奶能解除。每个目标每秒 0.3 魔力")
                .exec((n, c) -> {
                    double s = clamp(num(n.arg(1), c), 0, 600);
                    int ticks = (int) Math.round(s * 20);
                    if (ticks <= 0) return null;
                    for (LivingEntity t : living(n.arg(0), c)) {
                        if (!c.inst().spend(s * 0.3, "water-walk")) return null;
                        t.addEffect(new MobEffectInstance(LogosMod.WATER_WALKING, ticks), c.inst().caster);
                        c.inst().affected.add(t.getUUID());
                        Vfx.burst(lv(t), t, null, Element.ARCANE, Vfx.Burst.POTION_BENEFICIAL);
                    }
                    return null;
                });
        def("possess", EFFECT, ACTION).p("target", ENTITY).opt("seconds", NUM, "30").stab(3)
                .doc("附身：灵魂离开身体，进入一只生物，用自己的按键操控它若干秒（1～600，默认 30）：移动、跳跃，左键是它的近战，"
                        + "右键是它的专属技能（苦力怕自爆、末影人传送、烈焰人和恶魂的火球、唤魔者的尖牙、监守者的音波等），没有专属技能的会射击的生物（骷髅、女巫等）右键射击。它自己的 AI 暂停。身体留在原地，受到的伤害都由你承受，"
                        + "原本盯上你的生物会去打它；附身的生物也能打到它。潜行主动解除；时间到、生物离身体超过 128 格、你死亡或下线也会解除，"
                        + "回到身体里。附身期间生物死亡，你受到最大生命值 25% 的伤害（无视护甲）。不能附身玩家、Boss 和载着别人的生物，"
                        + "目标须在 128 格内。魔力 = 目标当前生命值 ÷ 5 × √秒数；附身自己的使魔免费")
                .exec((n, c) -> {
                    if (!(c.inst().caster instanceof ServerPlayer p) || p instanceof FakePlayer) return null;
                    if (!(entity(n.arg(0), c) instanceof net.minecraft.world.entity.Mob mob) || !dev.logos.runtime.Possession.canStart(p, mob)) return null;
                    double s = clamp(num(n.arg(1), c), 1, dev.logos.runtime.Possession.MAX_SECONDS);
                    if (!c.inst().spend(dev.logos.runtime.Possession.cost(mob, p, s), "possess")) return null;
                    Vfx.burst(lv(p), p, null, Element.ARCANE, Vfx.Burst.FORCE);
                    if (dev.logos.runtime.Possession.start(p, mob, s)) c.inst().affected.add(mob.getUUID());
                    return null;
                });
        def("incarnate", EFFECT, ACTION).p("creature", CREATURE).opt("seconds", NUM, "30").stab(3)
                .doc("化身：自己变成一种生物若干秒（1～600，默认 30）。和召唤使魔一样要先击杀够数（普通生物 " + dev.logos.runtime.Familiars.NORMAL_KILLS
                        + " 次，Boss " + dev.logos.runtime.Familiars.BOSS_KILLS + " 次）。体型、视线高度、生命上限、攻击、护甲、速度、跳跃都变成它的，"
                        + "生命按比例换算；会飞的能飞（连按两次跳跃），水生的不会溺水，免疫火焰的不怕火。只有配置 incarnationHands 里的生物还能用手："
                        + "拿东西、放方块、挖掘、施法；其他生物用自己的身体攻击。有专属技能的生物（苦力怕自爆、末影人传送、烈焰人和恶魂的火球、唤魔者的尖牙、监守者的音波等，和附身相同）右键放技能，有手的生物要空着主手；苦力怕自爆不伤自己，爆炸后恢复原样。也有它的特性：蜘蛛爬墙、鸡缓降、炽足兽走岩浆、猫等不受摔落伤害；僵尸和骷髅白天在阳光下燃烧（头盔替你挡）、末影人等碰水受伤、鱼离水会窒息。特性由实体标签决定（#minecraft:burn_in_daylight、#minecraft:fall_damage_immune、#logos:incarnation/…），整合包可以改。背包仍是你的，化身时死亡就是真的死亡；下线不会解除。"
                        + "时间到、被万咒皆终熄灭或用解除化身（revert）时恢复原样，自己也可以随时按 V 免费解除（按键可在设置里改）；再次化身会换成新的生物。不能化身为玩家和魔力生命体，周围要容得下它的体型。"
                        + "魔力 = 它的最大生命值 ÷ 5 × √秒数")
                .exec((n, c) -> {
                    if (!(c.inst().caster instanceof ServerPlayer p) || p instanceof FakePlayer) return null;
                    var type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.tryParse((String) n.arg(0).value)).orElse(null);
                    Component why = dev.logos.runtime.Incarnation.refuse(p, type);
                    if (why != null) {
                        c.inst().terminate(why);
                        return null;
                    }
                    double s = clamp(num(n.arg(1), c), 1, dev.logos.runtime.Incarnation.MAX_SECONDS);
                    if (!c.inst().spend(dev.logos.runtime.Incarnation.cost(type, s), "incarnate")) return null;
                    Vfx.burst(lv(p), p, null, Element.ARCANE, Vfx.Burst.FORCE);
                    dev.logos.runtime.Incarnation.start(p, type, s);
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
                .doc("处决：以虚空真实伤害直接终结生命，抗性提升和伤害吸收都挡不住，不死图腾按虚空的配置。代价由证明决定：4×(已证明的生命上界)×(已证明状态的折减)。前提失效会引发悖论反噬")
                .exec(EffectRunes::execute);
        def("blink", EFFECT, ACTION).p("pos", POS).eachOr("target", "caster").stab(2)
                .doc("把目标瞬移到某位置，可以跨维度（配合 in-dimension）。目标不写时是施法者自己；也可以接 target 或实体群，"
                        + "其他玩家需要 :players true。每个目标 2 + 每格 0.5 魔力（按它自己走的距离），跨维度另加 20")
                .exec((n, c) -> {
                    Place p = place(n.arg(0), c);
                    for (Entity t : anyEntities(n.arg(1), c)) {
                        if (c.inst().dead) return null;
                        boolean cross = p.level() != t.level();
                        double cost = 2 + (cross ? 20 : 0.5 * p.pos().distanceTo(t.position()));
                        if (!c.inst().spend(cost, "blink")) return null;
                        Vfx.burst(lv(t), t.position().add(0, t.getBbHeight() / 2, 0), Element.VOID, 20);
                        // The one rune that still loads a chunk, whatever spellsLoadChunks says: whoever lands there needs it, and a far blink pays by the block.
                        Vec3 to = cross || !standable(p.level(), BlockPos.containing(p.pos())) ? safeSpot(p.level(), p.pos()) : p.pos();
                        Entity moved = t.changeDimension(new net.minecraft.world.level.portal.DimensionTransition(p.level(), to, Vec3.ZERO, t.getYRot(), t.getXRot(), net.minecraft.world.level.portal.DimensionTransition.DO_NOTHING));
                        if (moved != null) moved.resetFallDistance();
                        Vfx.sound(p.level(), to, SoundEvents.ENDERMAN_TELEPORT, 0.6f, 1.2f);
                    }
                    return null;
                });
        BlockEffectRunes.blockEffects();
        def("summon", EFFECT, ACTION).yields(ENTITY).p("creature", CREATURE).kw("at", POS, "(ahead 2)").kw("name", TEXT, null)
                .kw("hunt", FILTER, null).kw("radius", NUM, null).kw("level", NUM, "1").kw("mana", NUM, null).stab(3)
                .doc("召唤一只你击杀过足够多次的生物作为使魔（普通生物 " + dev.logos.runtime.Familiars.NORMAL_KILLS + " 次，Boss "
                        + dev.logos.runtime.Familiars.BOSS_KILLS + " 次），存在一个游戏日。它保留自己的攻击方式，只攻击你在战斗的对象，"
                        + "跟随你，不伤你和你的其他使魔；它的击杀算你的。:hunt 给它一个猎杀姿态（见 stance）。魔力 = 最大生命值 × 2。数量上限见配置 maxFamiliars。"
                        + "召唤 mana_lifeform（魔力生命体）不需要击杀：:level 1–5，至少要 50×等级^1.5 魔力，"
                        + "默认把术式剩下的魔力全部（最多 1000×等级^1.5）凝成它的魔力，:mana 可以指定多少。它一直存在，靠自己的魔力和心核里的载页行动")
                .exec((n, c) -> {
                    if (!(c.inst().caster instanceof ServerPlayer sp)) return null;
                    var type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.tryParse((String) n.arg(0).value));
                    Place at = place(kw(n, "at"), c);
                    Node name = kw(n, "name");
                    Node hunt = kw(n, "hunt"), radius = kw(n, "radius");
                    if (Compiler.MANA_LIFEFORM.equals(n.arg(0).value)) {
                        Node mana = kw(n, "mana");
                        Component why = dev.logos.runtime.Lifeforms.summon(c.inst(), sp, at.level(), at.pos(), name == null ? null : text(name, c),
                                (int) Math.floor(num(kw(n, "level"), c)), mana == null ? null : num(mana, c));
                        if (why != null) c.inst().terminate(why);
                        else if (hunt != null || radius != null) {
                            dev.logos.runtime.Familiars.setStance(dev.logos.runtime.Familiars.latest(sp, null),
                                    hunt == null ? null : filter(hunt, c), c.inst(), radius == null ? null : num(radius, c));
                        }
                        return why == null ? dev.logos.runtime.Familiars.latest(sp, null) : null;
                    }
                    Component why = dev.logos.runtime.Familiars.summon(c.inst(), sp, type, at.level(), at.pos(), name == null ? null : text(name, c),
                            hunt == null ? null : filter(hunt, c), radius == null ? null : num(radius, c));
                    if (why != null) c.inst().terminate(why);
                    // The one just called: summon's 吾之使魔 output.
                    return why == null ? dev.logos.runtime.Familiars.latest(sp, null) : null;
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
                            c.inst().terminate(Component.translatable("logos.spell.end.bad_role", text(roleNode, c)));
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
                    Component why = dev.logos.runtime.Anchors.set(sp, (String) n.arg(0).value, at.level(), at.pos());
                    if (why != null) c.inst().terminate(why);
                    return null;
                });
        def("unanchor", EFFECT, ACTION).p("anchor", ANCHOR).doc("撤去锚点 ^名字（锚钉不退还）。免费")
                .exec((n, c) -> {
                    if (c.inst().caster instanceof ServerPlayer sp) dev.logos.runtime.Anchors.remove(sp, (String) n.arg(0).value);
                    return null;
                });
        def("move", EFFECT, ACTION).p("construct", BODY).p("to", POS).kw("speed", NUM, "0.2").stab(1)
                .doc("移动本术式的形体：炼成体与领域以 :speed 格/刻 滑到那里（炼成体到达时 on-arrive，站在上面的一起带走；跟随施法者的领域从此留在原地）；"
                        + "魔弹以自己的速度改道飞向那里并继续飞过去；光柱改从那里发出；游脉以自己的速度改朝那里爬（环扩不受影响）。通常写 (move form …) 或 (move self …)。1 魔力 + 每格 0.1")
                .exec((n, c) -> {
                    SpellObject f = ownForm(n.arg(0), c);
                    if (f == null) return null;
                    Vec3 to = place(n.arg(1), c).pos();
                    if (!c.inst().spend(1 + 0.1 * to.distanceTo(f.position()), "move")) return null;
                    f.moveTo(to, clamp(num(kw(n, "speed"), c), 0.01, 2));
                    return null;
                });
        def("halt", EFFECT, ACTION).p("construct", BODY)
                .doc("让本术式的形体停住：炼成体与领域停在原地（领域也不再跟随施法者），魔弹悬停（仍会击中碰到它的），光柱定住起点与方向，游脉停在原地（仍会碰到走进来的）。寿命照走。免费")
                .exec((n, c) -> {
                    SpellObject f = ownForm(n.arg(0), c);
                    if (f != null) f.halt();
                    return null;
                });
        def("turn", EFFECT, ACTION).p("form", BODY).p("dir", DIR)
                .doc("转向：让本术式的形体朝向某方向。魔弹以自己的速度改朝那边飞；光柱改指那边（不再跟随视线）；墙、圆盾等炼成体原地转过去（跟随施法者的墙和圆盾仍随视线）；游脉改朝那边的水平方向爬（追猎从此不再追，环扩不受影响）；领域没有朝向。免费")
                .exec((n, c) -> {
                    SpellObject f = ownForm(n.arg(0), c);
                    if (f != null) f.turn(vec(n.arg(1), c));
                    return null;
                });
        def("disperse", EFFECT, ACTION).p("form", BODY)
                .doc("散形：让本术式的一个形体立刻消散，和寿命走完一样会触发它的 on-expire。免费")
                .exec((n, c) -> {
                    SpellObject f = ownForm(n.arg(0), c);
                    if (f != null) f.disperse();
                    return null;
                });
        def("end-all", EFFECT, ACTION).kw("at", POS, "here").kw("radius", NUM, "6").stab(4)
                .doc("万咒皆终：熄灭 :radius 格内有形体的术式——每碰到一个形体，就熄灭它所属的整个术式（它别处的形体与排着队的延迟、重复一起），"
                        + "不触发消散时事件。离中心最近的先停；每停一个，付它此刻剩下的魔力 + 2，付不起就跳过它。"
                        + "施法者自己的其他术式也会停；其他玩家的术式要 :players true。已经施加的效果（时间停止、护盾、印记、药水）不受影响，"
                        + "只有化身例外：范围内化身的玩家恢复原样（你自己也会；其他玩家要 :players true），每个付它剩下时间的化身价格 + 2")
                .exec((n, c) -> {
                    Place at = place(kw(n, "at"), c);
                    double r = clamp(num(kw(n, "radius"), c), 0, 32);
                    int ended = 0;
                    double paid = 0;
                    for (SpellInstance s : SpellRuntime.spellsNear(at.level(), at.pos(), r)) {
                        if (s == c.inst() || s.dead) continue;
                        if (s.caster instanceof Player && s.caster != c.inst().caster && !c.inst().spell.affectsPlayers()) continue;
                        double cost = 2 + s.mana;
                        if (cost > c.inst().mana + 1e-9) continue; // too strong to stop now: a weaker one may still be
                        c.inst().spend(cost, "end-all");
                        s.terminate(Component.translatable("logos.spell.end.end_all"), false);
                        if (s.caster instanceof ServerPlayer owner && owner != c.inst().caster) {
                            owner.sendSystemMessage(Component.translatable("logos.end_all.yours_ended", s.spell.name()).withStyle(ChatFormatting.RED));
                        }
                        ended++;
                        paid += cost;
                    }
                    for (ServerPlayer t : dev.logos.runtime.Incarnation.near(at.level(), at.pos(), r)) {
                        if (t != c.inst().caster && !c.inst().spell.affectsPlayers()) continue;
                        double cost = 2 + dev.logos.runtime.Incarnation.remainingCost(t);
                        if (cost > c.inst().mana + 1e-9) continue;
                        c.inst().spend(cost, "end-all");
                        dev.logos.runtime.Incarnation.end(t);
                        if (t != c.inst().caster) t.sendSystemMessage(Component.translatable("logos.end_all.incarnation_ended").withStyle(ChatFormatting.RED));
                        ended++;
                        paid += cost;
                    }
                    Vfx.burst(at.level(), at.pos(), Element.VOID, 30);
                    Vfx.sound(at.level(), at.pos(), SoundEvents.BEACON_DEACTIVATE, 1f, 0.8f);
                    if (c.inst().caster instanceof ServerPlayer p) {
                        p.sendSystemMessage((ended == 0 ? Component.translatable("logos.end_all.none")
                                : Component.translatable("logos.end_all.ended", ended, Node.fmt(paid))).withStyle(ChatFormatting.GRAY));
                    }
                    return null;
                });
        def("revert", EFFECT, ACTION).eachOr("target", "caster").stab(1)
                .doc("解除化身：让化身的玩家恢复原样。不连目标时是你自己，免费；也可以接别的玩家或一群实体，解除他们的化身（其他玩家要 :players true），"
                        + "每个付他这次化身的价格减去已经用掉的部分：它的最大生命值 ÷ 5 × (√总秒数 − √已过秒数)。没在化身的目标不受影响")
                .exec((n, c) -> {
                    for (LivingEntity t : living(n.arg(0), c)) {
                        if (!(t instanceof ServerPlayer p) || !dev.logos.runtime.Incarnation.active(p)) continue;
                        boolean own = p == c.inst().caster;
                        if (!own && !c.inst().spend(dev.logos.runtime.Incarnation.unusedCost(p), "revert")) return null;
                        dev.logos.runtime.Incarnation.end(p);
                        c.inst().affected.add(p.getUUID());
                        Vfx.burst(lv(p), p, null, Element.ARCANE, Vfx.Burst.FORCE);
                        if (!own) p.sendSystemMessage(Component.translatable("logos.revert.yours_reverted").withStyle(ChatFormatting.RED));
                    }
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
                        + ":count 个数（上限见配置 maxPhantasmParticles，默认 64），:spread 散布半径（≤8）。只是幻象，不改变世界。每个粒子 0.02 魔力（64 个 1.28）")
                .exec((n, c) -> {
                    Place at = place(n.arg(1), c);
                    int count = (int) clamp(Math.round(num(kw(n, "count"), c)), 1, LogosConfig.maxPhantasmParticles());
                    if (!c.inst().spend(count * PHANTASM_PARTICLE, "phantasm")) return null;
                    Vfx.phantasm(at.level(), at.pos(), Vfx.particleId(text(n.arg(0), c)), count, clamp(num(kw(n, "spread"), c), 0, 8));
                    return null;
                });
        def("echo", EFFECT, ACTION).p("value", ANY).doc("把一个值显示给施法者（调试用）。免费")
                .exec((n, c) -> {
                    Object v = eval(n.arg(0), c);
                    String s = switch (v) {
                        case null -> "空";
                        case Double d -> Node.fmt(d);
                        case Entity e -> e.getName().getString();
                        case Place p -> String.format("(%.1f, %.1f, %.1f) @%s", p.pos().x, p.pos().y, p.pos().z, p.level().dimension().location());
                        case List<?> l -> l.size() + " 个";
                        case Vec3 p -> String.format("(%.1f, %.1f, %.1f)", p.x, p.y, p.z);
                        default -> v.toString();
                    };
                    if (c.inst().caster instanceof ServerPlayer sp) {
                        sp.sendSystemMessage(Component.translatable("logos.echo", n.arg(0).show(), s).withStyle(ChatFormatting.AQUA));
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
            inst.caster.hurt(inst.caster.damageSources().magic(), 4);
            Vfx.burst(casterLevel, inst.caster, null, Element.VOID, Vfx.Burst.DAMAGE);
            inst.terminate(Component.translatable("logos.spell.end.paradox", broken.describe()));
            return null;
        }
        double cost = ExecuteCost.cost(t, proof);
        if (!inst.spend(cost, "execute(需要 " + Node.fmt(cost) + ")", Category.EFFECT)) return null;
        if (t.level() == inst.caster.level()) Vfx.line(lv(t), Targeting.center(inst.caster), Targeting.center(t), Element.VOID);
        Vfx.execute(lv(t), Targeting.center(t));
        Vfx.sound(lv(t), t.position(), SoundEvents.WITHER_SHOOT, 0.7f, 0.6f);
        if (inst.caster instanceof ServerPlayer sp) {
            sp.sendSystemMessage((proof.isEmpty() ? Component.translatable("logos.execute.done_unproven", t.getName(), Node.fmt(cost))
                    : Component.translatable("logos.execute.done", t.getName(), Node.fmt(cost), proof.stream().map(Fact::describe).toList().toString())).withStyle(ChatFormatting.DARK_PURPLE));
        }
        // A colossus is not killed by a proof: its mana is wiped and it powers down (a frenzy burns out). Configured as /kill, it dies like anything else.
        if (t instanceof dev.logos.runtime.AlchemicalColossus colossus && !LogosConfig.voidIsOutOfWorld()) {
            colossus.drainAll();
            return null;
        }
        if (Effects.kill(inst, t)) fire(n, "on-kill", c.on(t, Targeting.center(t)));
        return null;
    }

    /** The body a slot names, if this spell made it: a form's own value, or a construct's entity (self). */
    private static @javax.annotation.Nullable SpellObject ownForm(Node n, Ctx c) {
        SpellObject f = switch (eval(n, c)) {
            case SpellObject o -> o;
            case ConstructEntity ce -> ce.owner();
            case null, default -> null;
        };
        return f != null && f.owns(c.inst()) ? f : null;
    }
}
