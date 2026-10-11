package dev.logos.runtime;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import dev.logos.LogosMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobDespawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Where the familiar bond meets the game: targeting, combat, kills, despawning, drops and griefing. */
@EventBusSubscriber(modid = LogosMod.MODID)
final class FamiliarEvents {
    /** Creatures whose own way of changing the world is blocks: as familiars, they leave blocks alone. */
    private static final List<EntityType<?>> NO_BLOCK_GRIEF = List.of(EntityType.CREEPER, EntityType.WITHER, EntityType.ENDER_DRAGON,
            EntityType.ENDERMAN, EntityType.RAVAGER, EntityType.SILVERFISH, EntityType.GHAST);
    private static final Map<Mob, List<ItemStack>> DYING_WITH = new WeakHashMap<>();

    private FamiliarEvents() {}

    /** A familiar never takes a target of its own: only what its master is fighting. */
    @SubscribeEvent
    static void onChangeTarget(LivingChangeTargetEvent event) {
        if (!(event.getEntity() instanceof Mob m) || !Familiars.isFamiliar(m)) return;
        if (m instanceof AlchemicalColossus c && c.isBerserk()) return; // a frenzied colossus answers to no one
        LivingEntity wanted = event.getNewAboutToBeSetTarget();
        LivingEntity ordered = Familiars.orderedTarget(m);
        if (wanted != null && wanted != ordered) event.setNewAboutToBeSetTarget(ordered);
    }

    @SubscribeEvent
    static void onDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        Entity attacker = event.getSource().getEntity();
        UUID attackerMaster = Familiars.masterOf(attacker);
        // A frenzied colossus spares no one, its master included, and earns them nothing.
        if (attacker instanceof AlchemicalColossus c && c.isBerserk()) attackerMaster = null;
        if (attackerMaster != null) {
            // No friendly fire: not the master, not their other familiars.
            if (Familiars.friendly(attackerMaster, victim)) {
                event.setCanceled(true);
                return;
            }
            // Like a tamed wolf: what a familiar kills, its master killed (stats, experience, player-only drops).
            if (victim.level().getPlayerByUUID(attackerMaster) instanceof net.minecraft.world.entity.player.Player master) {
                victim.setLastHurtByPlayer(master, 100);
            }
        }
        if (victim instanceof ServerPlayer master && attacker instanceof LivingEntity a && a != master) Familiars.rally(master, a);
        if (attacker instanceof ServerPlayer master && victim != master) Familiars.rally(master, victim);
    }

    @SubscribeEvent
    static void onTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof Mob m && !m.level().isClientSide() && Familiars.live().contains(m)) Familiars.tick(m);
    }

    /** Familiars are never kept: one that was saved with its chunk does not come back. */
    @SubscribeEvent
    static void onJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() && Familiars.isFamiliar(event.getEntity())) {
            // A made familiar is kept with the world: it answers again, unless it was unmade meanwhile.
            if (event.getEntity() instanceof Mob lf && lf instanceof KeptFamiliar && !event.getLevel().isClientSide()) {
                boolean kept = Lifeforms.registered(lf);
                Lifeforms.onLoaded(lf);
                if (kept) return;
            }
            event.setCanceled(true);
            return;
        }
        // The End's own dragon has been brought back: summoned dragons there are unmade at once.
        if (event.getEntity() instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon d && !Familiars.isFamiliar(d)
                && event.getLevel() instanceof ServerLevel level && level.getDragonFight() != null) {
            DragonFamiliar.throneReclaimed(level);
        }
    }

    @SubscribeEvent
    static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof Mob m) Familiars.forget(m);
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        for (Mob m : Familiars.of(event.getEntity())) if (!Familiars.kept(m)) Familiars.dismiss(m);
    }

    /** Not even peaceful difficulty sends a familiar away; only its bond does. */
    @SubscribeEvent
    static void onDespawn(MobDespawnEvent event) {
        if (Familiars.isFamiliar(event.getEntity())) event.setResult(MobDespawnEvent.Result.DENY);
    }

    /** Dying, it drops only what it picked up: no loot of its own, no experience. */
    @SubscribeEvent
    static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob m) || !Familiars.isFamiliar(m)) return;
        if (m instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon) {
            // No ten-second death throes: those shower experience and belong to the End fight.
            event.setCanceled(true);
            Familiars.dismiss(m);
            return;
        }
        DYING_WITH.put(m, Familiars.pickedUp(m));
    }

    /** A dragon never truly dies (it drops to 1 health and plays its death): unmake a familiar one at the lethal blow instead. */
    @SubscribeEvent
    static void onDamaged(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        if (event.getEntity() instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon d && d.isDeadOrDying() && Familiars.isFamiliar(d)) {
            Familiars.dismiss(d);
        }
    }

    @SubscribeEvent
    static void onDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Mob m) || !Familiars.isFamiliar(m)) return;
        event.getDrops().clear();
        for (ItemStack s : DYING_WITH.getOrDefault(m, List.of())) {
            event.getDrops().add(new ItemEntity(m.level(), m.getX(), m.getY() + 0.5, m.getZ(), s));
        }
        DYING_WITH.remove(m);
    }

    @SubscribeEvent
    static void onExperience(LivingExperienceDropEvent event) {
        if (Familiars.isFamiliar(event.getEntity())) event.setCanceled(true);
    }

    /** Creepers, withers, the dragon, endermen… bound as familiars break no blocks (their attacks still hurt). */
    @SubscribeEvent
    static void onGrief(EntityMobGriefingEvent event) {
        Entity e = event.getEntity();
        if (Familiars.isFamiliar(e) && NO_BLOCK_GRIEF.contains(e.getType())) event.setCanGrief(false);
        else if (!Familiars.isFamiliar(e) && Familiars.masterOf(e) != null) event.setCanGrief(false); // a familiar's projectile
    }

    /** A familiar's explosion breaks no blocks and spares its master's side. */
    @SubscribeEvent
    static void onDetonate(ExplosionEvent.Detonate event) {
        var ex = event.getExplosion();
        UUID master = Familiars.masterOf(ex.getDirectSourceEntity());
        if (master == null) master = Familiars.masterOf(ex.getIndirectSourceEntity());
        if (master == null) return;
        event.getAffectedBlocks().clear();
        UUID m = master;
        event.getAffectedEntities().removeIf(e -> Familiars.friendly(m, e));
    }
}
