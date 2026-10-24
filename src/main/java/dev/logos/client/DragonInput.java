package dev.logos.client;

import dev.logos.net.DragonControlPayload;
import dev.logos.runtime.DragonFamiliar;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * A dragon rider's keys, with the colossus's mode key (V by default). The rider always flies it with
 * the movement keys; the mode key only decides whose the mouse buttons are. Riding along, they stay
 * the rider's own (items, spells); driving, left click is a dragon fireball and the use button, held,
 * its breath. The mode is the rider's own choice, kept here; the server checks only that a master is
 * on the dragon's back.
 */
final class DragonInput {
    private static boolean driving, useWasDown;

    private DragonInput() {}

    /** The dragon {@code p} sits at the front of (where its master sits), or null. */
    private static EnderDragon ridden(Player p) {
        return p != null && p.getVehicle() instanceof EnderDragon d && d.getFirstPassenger() == p ? d : null;
    }

    private static void send(byte action) {
        ClientPacketDistributor.sendToServer(new DragonControlPayload(action));
    }

    /** Each client tick, before the colossus's: the mode key while on a dragon, and the breath button's press and release. */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (ridden(mc.player) == null) {
            driving = false;
            useWasDown = false;
            return;
        }
        while (ColossusInput.MODE.consumeClick()) {
            driving = !driving;
            mc.gui.setOverlayMessage(Component.literal(driving ? "🐉 驾驭模式：左键龙息火球，按住右键持续吐息" : "🐉 乘坐模式：双手自由，移动仍由你控制"), false);
        }
        boolean down = driving && mc.screen == null && mc.options.keyUse.isDown();
        if (down && !useWasDown) send(DragonFamiliar.BREATH_ON);
        if (!down && useWasDown) send(DragonFamiliar.BREATH_OFF);
        useWasDown = down;
    }

    /** Driving, attack and use go to the dragon instead of the rider's own hands. */
    static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!driving || ridden(Minecraft.getInstance().player) == null) return;
        if (event.isAttack()) {
            send(DragonFamiliar.FIRE);
            event.setSwingHand(false);
            event.setCanceled(true);
        } else if (event.isUseItem()) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }
}
