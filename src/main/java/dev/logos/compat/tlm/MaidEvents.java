package dev.logos.compat.tlm;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import com.github.tartaricacid.touhoulittlemaid.api.event.InteractMaidEvent;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import dev.logos.LogosMod;
import dev.logos.item.CodexItem;
import dev.logos.item.PageContainer;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.item.WandItem;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import dev.logos.runtime.CreatureMana;
import dev.logos.runtime.Limits;
import dev.logos.runtime.Lifeforms;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * The maid mod's events, as Logos hears them; registered by {@link LogosMaidExtension} only when the maid mod is there.
 * Every tamed maid's pool refills; in the 施法 mode her owner refills it with mana fuel (what a lifeform drinks), and the
 * passive pages on her wand and codex fire as on a player's: when she is hurt, when her owner strikes something, and
 * when her foe falls.
 */
final class MaidEvents {
    /** Per maid, per passive page: the tick it may fire again. */
    private static final Map<EntityMaid, Map<String, Long>> COOLDOWNS = new WeakHashMap<>();

    private MaidEvents() {}

    @SubscribeEvent
    static void onMaidTick(MaidTickEvent event) {
        MaidMana.tick(event.getMaid());
    }

    static boolean casting(EntityMaid maid) {
        return SpellcastingTask.UID.equals(maid.getTask().getUid());
    }

    /** Her owner gives her mana fuel: her pool takes it, as a lifeform's does. Only in the 施法 mode, so other modes keep their clicks. */
    @SubscribeEvent
    static void onInteract(InteractMaidEvent event) {
        EntityMaid maid = event.getMaid();
        ItemStack held = event.getStack();
        if (held.isEmpty() || !casting(maid)) return;
        Lifeforms.ManaFuel fuel = held.typeHolder().getData(LogosMod.MANA_FUEL);
        if (fuel == null) return;
        event.setCanceled(true);
        if (event.getWorld().isClientSide()) return;
        CreatureMana pool = MaidMana.of(maid);
        if (pool == null) return;
        if (pool.mana() >= pool.maxMana()) {
            event.getPlayer().sendOverlayMessage(Component.literal("魔力已满").withStyle(ChatFormatting.GRAY));
            return;
        }
        pool.receiveMana(fuel.mana());
        if (!event.getPlayer().getAbilities().instabuild) held.shrink(1);
        maid.level().playSound(null, maid.getX(), maid.getY(), maid.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1f,
                1.2f + maid.getRandom().nextFloat() * 0.4f);
        event.getPlayer().sendOverlayMessage(status(maid, pool));
    }

    static Component status(EntityMaid maid, CreatureMana pool) {
        return Component.literal(maid.getName().getString() + " · 魔力 " + Math.round(pool.mana()) + " / " + Math.round(pool.maxMana()))
                .withStyle(ChatFormatting.AQUA);
    }

    // ---- passive pages --------------------------------------------------------------------------

    @SubscribeEvent
    static void onDamaged(LivingDamageEvent.Post event) {
        LivingEntity victim = event.getEntity();
        Entity attacker = event.getSource().getEntity();
        if (!(victim.level() instanceof ServerLevel level)) return;
        if (victim instanceof EntityMaid maid && attacker != maid) LogosMod.defer(() -> firePassives(maid, "hurt", attacker));
        // Her owner strikes something not on their side: their maids' attack passives join in, as a lifeform's do.
        if (attacker instanceof ServerPlayer owner && victim != owner && !(victim instanceof EntityMaid m && m.isOwnedBy(owner))) {
            for (EntityMaid maid : level.getEntitiesOfClass(EntityMaid.class, owner.getBoundingBox().inflate(32), m -> m.isOwnedBy(owner) && casting(m))) {
                LogosMod.defer(() -> firePassives(maid, "attack", victim));
            }
        }
    }

    /** Her foe falls: her kill passives. */
    @SubscribeEvent
    static void onDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (!(dead.level() instanceof ServerLevel level)) return;
        for (EntityMaid maid : level.getEntitiesOfClass(EntityMaid.class, dead.getBoundingBox().inflate(32),
                m -> casting(m) && m.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == dead)) {
            LogosMod.defer(() -> firePassives(maid, "kill", dead));
        }
    }

    /** Casts the passive pages on her wand and codex bound to {@code trigger}, each at most once a second, from her pool. */
    static void firePassives(EntityMaid maid, String trigger, @Nullable Entity target) {
        if (!maid.isAlive() || !casting(maid)) return;
        CreatureMana pool = MaidMana.of(maid);
        if (pool == null) return;
        long now = maid.level().getGameTime();
        int stability = MaidSpells.stability(maid);
        Map<String, Long> cooldowns = COOLDOWNS.computeIfAbsent(maid, k -> new HashMap<>());
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack holder = maid.getItemInHand(hand);
            if (!(holder.getItem() instanceof WandItem) && !(holder.getItem() instanceof CodexItem)) continue;
            for (int i = 0; i < PageContainer.size(holder); i++) {
                ItemStack page = PageContainer.get(holder, i);
                if (!SpellPages.isInscribed(page)) continue;
                Spell s = SpellPages.spell(page);
                if (s == null || !s.isPassive() || !s.trigger().equals(trigger) || s.stability() > stability) continue;
                if (cooldowns.getOrDefault(s.name(), 0L) > now) continue;
                PageTier tier = SpellPages.tier(page);
                if (tier == null) tier = PageTier.BASIC;
                double pay = Math.min(Casting.injection(s, tier, 0).mana(), pool.mana());
                if (pay <= 0) return;
                cooldowns.put(s.name(), now + Limits.PASSIVE_COOLDOWN_TICKS);
                pool.take(pay);
                Casting.castAs(maid, s, pay, tier.recovery(), target, null);
            }
        }
    }
}
