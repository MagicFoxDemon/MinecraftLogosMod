package dev.logos.runtime;

import dev.logos.item.GuideBookItem;
import dev.logos.item.TieredCharmItem;
import dev.logos.item.WandItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Where a player's magic meets the game: joining, respawning and changing dimension (their client's copies are sent
 * again), leaving and dying (a charge under way breaks), each tick's upkeep, and the passive spells a blow or a kill fires.
 * <p>
 * Registered by {@link dev.logos.LogosMod}'s constructor, not by {@code @EventBusSubscriber}, so that these run before
 * the listeners of the annotated classes: a passive is queued for a blow before any of them cancels it.
 */
public final class PlayerEvents {
    private PlayerEvents() {}

    /** The client keeps a copy of its anchors (editor list, markers); resend whenever the player is (re)created. */
    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            Anchors.sync(sp);
            Attunement.record(sp);
            RuneKnowledge.sync(sp);
            GuideBookItem.welcome(sp);
        }
    }

    @SubscribeEvent
    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            Anchors.sync(sp);
            RuneKnowledge.sync(sp);
        }
    }

    @SubscribeEvent
    static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            Anchors.sync(sp);
            Attunement.record(sp);
            Charging.interrupt(sp);
        }
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            Charging.interrupt(sp);
            StaffCharging.stop(sp);
        }
    }

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        PlayerMagic m = PlayerMagic.of(sp);
        TieredCharmItem.apply(sp);
        WandItem.applyCarried(sp);
        ManaGrowth.tick(sp);
        StaffCharging.tick(sp);
        if (sp.level().getGameTime() % ManaHud.INTERVAL == 0) ManaHud.sync(sp);
    }

    @SubscribeEvent
    static void onDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (Effects.inSpell() || victim.level().isClientSide()) return;
        Entity attacker = event.getSource().getEntity();
        if (victim instanceof ServerPlayer p) {
            SpellRuntime.defer(() -> Casting.firePassives(p, "hurt", attacker, p.getEyePosition()));
        }
        if (attacker instanceof ServerPlayer p && attacker != victim) {
            SpellRuntime.defer(() -> Casting.firePassives(p, "attack", victim, Targeting.center(victim)));
        }
    }

    @SubscribeEvent
    static void onDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead instanceof ServerPlayer sp) {
            Charging.interrupt(sp);
            StaffCharging.stop(sp);
        }
        if (Effects.inSpell() || dead.level().isClientSide()) return;
        if (event.getSource().getEntity() instanceof ServerPlayer p && p != dead) {
            var at = Targeting.center(dead);
            SpellRuntime.defer(() -> Casting.firePassives(p, "kill", dead, at));
        }
    }
}
