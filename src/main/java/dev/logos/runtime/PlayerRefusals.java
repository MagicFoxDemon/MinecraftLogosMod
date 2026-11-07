package dev.logos.runtime;

import dev.logos.LogosMod;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * What a player's own hands may not do while they are inside a creature ({@link Possession}) or their
 * time is stopped ({@link TimeStop}): attack, use an item, use or hit a block, handle a creature.
 * <p>
 * Listeners of one priority run in the order their classes are found, which on a normal build is by
 * name. This name keeps these after FamiliarRiding, Incarnation and ManaWrap, which act on the same
 * events and so still see what is refused here; renaming the class can reorder them.
 */
@EventBusSubscriber(modid = LogosMod.MODID)
public final class PlayerRefusals {
    private PlayerRefusals() {}

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
        if (Possession.handsBound(p) || TimeStop.stopped(p)) event.setCanceled(true);
    }
}
