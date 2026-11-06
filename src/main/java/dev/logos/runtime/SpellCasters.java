package dev.logos.runtime;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

import dev.logos.LogosMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The one rule for a caster leaving the world, for every form alike. A player who logs out takes their spells with
 * them; one who respawns keeps them, as {@link SpellInstance#caster} becomes the new body; any other caster that has
 * been removed (a dead familiar, one unloaded with its chunk) ends its spells. An emitter's {@code Shadow} is never in
 * a world, so never removed, and though it wears its owner's UUID it is not them: casters are told apart as objects.
 */
@EventBusSubscriber(modid = LogosMod.MODID)
final class SpellCasters {
    private static final String LEFT = "施法者已离开世界";

    /** The spells still running; weak, so a spell dropped without ending (a test's) is not kept for this. */
    private static final Set<SpellInstance> RUNNING = Collections.newSetFromMap(new WeakHashMap<>());

    private SpellCasters() {}

    static void started(SpellInstance inst) {
        RUNNING.add(inst);
    }

    static void ended(SpellInstance inst) {
        RUNNING.remove(inst);
    }

    static void clear() {
        RUNNING.clear();
    }

    /** Removed for good: a dead player stays on the player list until they respawn or leave, which are their own events. */
    private static boolean gone(LivingEntity caster) {
        if (!caster.isRemoved()) return false;
        return !(caster instanceof ServerPlayer p) || p.level().getServer().getPlayerList().getPlayer(p.getUUID()) != p;
    }

    private static void extinguish(Predicate<SpellInstance> left) {
        for (SpellInstance inst : RUNNING.stream().filter(left).toList()) inst.terminate(LEFT);
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        extinguish(inst -> inst.caster == event.getEntity());
    }

    @SubscribeEvent
    static void onRespawn(PlayerEvent.Clone event) {
        for (SpellInstance inst : RUNNING) if (inst.caster == event.getOriginal()) inst.caster = event.getEntity();
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (!RUNNING.isEmpty()) extinguish(inst -> gone(inst.caster));
    }
}
