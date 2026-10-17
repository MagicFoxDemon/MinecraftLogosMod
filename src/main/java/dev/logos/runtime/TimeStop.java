package dev.logos.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import dev.logos.LogosMod;
import dev.logos.lang.Element;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * 时停: one creature's time stands still while the world around it goes on. A mob simply does not
 * tick (no movement, AI, fire or potion clock). A player's movement is their own client's, so
 * instead their speed, jump and gravity are held at zero (attributes sync to that client) and their
 * attacks and item uses are refused. Damage taken meanwhile is kept and dealt all at once when time
 * resumes. Server side only, and never saved: a stop that outlives its world simply ends.
 */
@EventBusSubscriber(modid = LogosMod.MODID)
public final class TimeStop {
    private static final Identifier HOLD = Identifier.fromNamespaceAndPath(LogosMod.MODID, "time_stop");

    private record Hit(DamageSource source, float amount) {}

    private static final class Stop {
        long until;
        final List<Hit> hits = new ArrayList<>();
    }

    private static final Map<LivingEntity, Stop> STOPPED = new WeakHashMap<>();

    private TimeStop() {}

    /** Stops {@code t}'s time for {@code seconds}, or lengthens a stop already holding it. */
    public static void stop(LivingEntity t, double seconds) {
        long until = t.level().getGameTime() + Math.round(seconds * 20);
        Stop s = STOPPED.get(t);
        if (s == null) {
            s = new Stop();
            STOPPED.put(t, s);
            if (t instanceof Player p) hold(p);
            Vfx.sound((ServerLevel) t.level(), Targeting.center(t), SoundEvents.BEACON_DEACTIVATE, 1f, 1.6f);
        }
        s.until = Math.max(s.until, until);
    }

    public static boolean stopped(Entity e) {
        return !e.level().isClientSide() && e instanceof LivingEntity l && STOPPED.containsKey(l);
    }

    /** Damage kept for the moment time resumes, summed. */
    public static double stored(LivingEntity t) {
        Stop s = STOPPED.get(t);
        return s == null ? 0 : s.hits.stream().mapToDouble(Hit::amount).sum();
    }

    private static void hold(Player p) {
        for (var attr : List.of(Attributes.MOVEMENT_SPEED, Attributes.JUMP_STRENGTH, Attributes.GRAVITY)) {
            AttributeInstance a = p.getAttribute(attr);
            if (a != null) a.addOrUpdateTransientModifier(new AttributeModifier(HOLD, -1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        p.setDeltaMovement(Vec3.ZERO);
        p.hurtMarked = true; // sends the zero velocity to their client
    }

    private static void release(LivingEntity t, Stop s) {
        STOPPED.remove(t);
        if (t instanceof Player p) {
            for (var attr : List.of(Attributes.MOVEMENT_SPEED, Attributes.JUMP_STRENGTH, Attributes.GRAVITY)) {
                AttributeInstance a = p.getAttribute(attr);
                if (a != null) a.removeModifier(HOLD);
            }
        }
        ServerLevel level = (ServerLevel) t.level();
        Vfx.sound(level, Targeting.center(t), SoundEvents.BEACON_ACTIVATE, 1f, 1.6f);
        if (!s.hits.isEmpty()) Vfx.burst(level, Targeting.center(t), Element.ARCANE, 16);
        // Every kept hit lands at once: each its own blow, none turned away by the last one's invulnerability.
        for (Hit h : s.hits) {
            if (!t.isAlive()) break;
            t.invulnerableTime = 0;
            t.hurtServer(level, h.source, h.amount);
        }
    }

    /** A stopped mob skips its tick; once the stop runs out, its kept damage lands. */
    @SubscribeEvent
    static void onEntityTick(EntityTickEvent.Pre event) {
        Entity e = event.getEntity();
        if (e.level().isClientSide() || STOPPED.isEmpty() || !(e instanceof LivingEntity t)) return;
        Stop s = STOPPED.get(t);
        if (s == null) return;
        if (t.level().getGameTime() >= s.until) {
            release(t, s);
        } else if (!(t instanceof Player)) {
            event.setCanceled(true);
        }
    }

    /** Damage to a stopped creature is kept, not dealt; only what bypasses invulnerability (the void, /kill) gets through. */
    @SubscribeEvent
    static void onDamage(LivingIncomingDamageEvent event) {
        LivingEntity t = event.getEntity();
        if (t.level().isClientSide() || event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return;
        Stop s = STOPPED.get(t);
        if (s == null) return;
        s.hits.add(new Hit(event.getSource(), event.getAmount()));
        event.setCanceled(true);
    }

    @SubscribeEvent
    static void onAttack(AttackEntityEvent event) {
        refuse(event.getEntity(), event);
    }

    @SubscribeEvent
    static void onUseItem(PlayerInteractEvent.RightClickItem event) {
        refuse(event.getEntity(), event);
    }

    @SubscribeEvent
    static void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        refuse(event.getEntity(), event);
    }

    @SubscribeEvent
    static void onHitBlock(PlayerInteractEvent.LeftClickBlock event) {
        refuse(event.getEntity(), event);
    }

    @SubscribeEvent
    static void onUseEntity(PlayerInteractEvent.EntityInteract event) {
        refuse(event.getEntity(), event);
    }

    private static void refuse(Player p, ICancellableEvent event) {
        if (stopped(p)) event.setCanceled(true);
    }
}
