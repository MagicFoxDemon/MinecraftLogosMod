package dev.logos.compat.tlm;

import java.util.List;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.github.tartaricacid.touhoulittlemaid.api.task.IRangedAttackTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.Lists;
import com.mojang.datafixers.util.Pair;

import dev.logos.LogosMod;
import dev.logos.item.WandItem;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.StartAttacking;
import net.minecraft.world.entity.ai.behavior.StopAttackingIfTargetInvalid;
import net.minecraft.world.item.ItemStack;

/**
 * 施法: the work mode in which a maid casts Logos spells. With a wand or staff in her main hand (and a codex in her off
 * hand if her owner likes), or a wand in her off hand beside a weapon, she chooses what to do as a mana lifeform does ({@link MaidMind}): picks pages by their
 * intent, keeps at her attacks' reach, and pays from her own pool ({@link MaidMana}), a staff through filling it. It is an attack mode to the
 * maid mod, so its attack list and target rules apply, and she does not panic when hurt.
 */
final class SpellcastingTask implements IRangedAttackTask {
    static final Identifier UID = Identifier.fromNamespaceAndPath(LogosMod.MODID, "spellcasting");

    @Override
    public Identifier getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        return LogosMod.WAND.get().getDefaultInstance();
    }

    @Override
    public @Nullable SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        BehaviorControl<EntityMaid> startAttacking = StartAttacking.create(
                (level, e) -> canFight(e),
                (level, e) -> IRangedAttackTask.findFirstValidAttackTarget(e));
        BehaviorControl<EntityMaid> stopAttacking = StopAttackingIfTargetInvalid.create(
                (level, target) -> !canFight(maid) || farAway(target, maid));
        return Lists.newArrayList(
                Pair.of(5, startAttacking),
                Pair.of(5, stopAttacking),
                Pair.of(5, new CastSpells()));
    }

    /** Riding or told to wait she cannot move, so she only casts. */
    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createRideBrainTasks(EntityMaid maid) {
        BehaviorControl<EntityMaid> startAttacking = StartAttacking.create(
                (level, e) -> canFight(e),
                (level, e) -> IRangedAttackTask.findFirstValidAttackTarget(e));
        BehaviorControl<EntityMaid> stopAttacking = StopAttackingIfTargetInvalid.create(
                (level, target) -> !canFight(maid) || farAway(target, maid));
        return Lists.newArrayList(
                Pair.of(5, startAttacking),
                Pair.of(5, stopAttacking),
                Pair.of(5, new CastSpells()));
    }

    /** She takes on foes with an attack page and mana to cast it, or, with a wand in her off hand, a weapon in her main hand. */
    private static boolean canFight(EntityMaid maid) {
        var pool = MaidMana.of(maid);
        if (pool == null) return false;
        if (MaidSpells.melee(maid)) return true;
        return (pool.mana() > 0 || dev.logos.item.StaffItem.stored(maid.getMainHandItem()) > 0) && !MaidSpells.attacks(MaidSpells.active(maid)).isEmpty();
    }

    private boolean farAway(LivingEntity target, EntityMaid maid) {
        return maid.distanceTo(target) > searchRadius(maid);
    }

    @Override
    public float searchRadius(EntityMaid maid) {
        return Math.max(maid.getHomeRadius(), (float) CastSpells.REACH);
    }

    /** Not used: her casts are charged in {@link CastSpells}, not shot by the maid mod's bow behaviours. */
    @Override
    public void performRangedAttack(EntityMaid shooter, LivingEntity target, float distanceFactor) {
    }

    @Override
    public boolean isWeapon(EntityMaid maid, ItemStack stack) {
        // A weapon counts too beside a wand in her off hand: then she fights up close as well.
        return stack.getItem() instanceof WandItem || (stack == maid.getMainHandItem() && MaidSpells.melee(maid));
    }

    @Override
    public List<Pair<String, Predicate<EntityMaid>>> getConditionDescription(EntityMaid maid) {
        return List.of(Pair.<String, Predicate<EntityMaid>>of("has_wand", MaidSpells::holdsWand),
                Pair.<String, Predicate<EntityMaid>>of("has_spells", e -> !MaidSpells.active(e).isEmpty()));
    }

    @Override
    public String getMaidActionSummary() {
        return "Cast Logos spells from the wand or staff in the main hand (and a spell codex in the off hand): strike enemies, heal and ward the owner, gather free mana, keep a staff filled; with a wand in the off hand and a weapon in the main hand, also fight up close. Needs a wand or staff with written spell pages";
    }
}
