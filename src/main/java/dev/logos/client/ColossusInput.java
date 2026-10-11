package dev.logos.client;

import com.mojang.blaze3d.platform.InputConstants;

import dev.logos.net.ColossusControlPayload;
import dev.logos.runtime.AlchemicalColossus;
import dev.logos.runtime.Colossi;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * A colossus rider's keys. The mode key (V by default) switches between riding along and driving.
 * Driving, the rider's movement keys move it, left click is its blow (or grab), and the use button,
 * held, raises and charges its palm cannon; let go, it fires.
 */
final class ColossusInput {
    static final KeyMapping MODE = new KeyMapping("key.logos.colossus_mode", InputConstants.KEY_V, LogosClient.CATEGORY);
    private static boolean useWasDown;

    private ColossusInput() {}

    private static AlchemicalColossus ridden(Player p) {
        return p != null && p.getVehicle() instanceof AlchemicalColossus c ? c : null;
    }

    private static void send(byte action) {
        ClientPacketDistributor.sendToServer(new ColossusControlPayload(action));
    }

    /** Each client tick: the mode key, and the cannon button's press and release. */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        AlchemicalColossus c = ridden(mc.player);
        while (MODE.consumeClick()) if (c != null) send(Colossi.TOGGLE_DRIVE);
        boolean down = c != null && c.isDriven() && mc.screen == null && mc.options.keyUse.isDown();
        if (down && !useWasDown) send(Colossi.CHARGE);
        if (!down && useWasDown) send(Colossi.LOOSE);
        useWasDown = down;
    }

    /** Driving, attack and use go to the colossus instead of the rider's own hands. */
    static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        AlchemicalColossus c = ridden(Minecraft.getInstance().player);
        if (c == null || !c.isDriven()) return;
        if (event.isAttack()) {
            send(Colossi.STRIKE);
            event.setSwingHand(false);
            event.setCanceled(true);
        } else if (event.isUseItem()) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }
}
