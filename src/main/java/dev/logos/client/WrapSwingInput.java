package dev.logos.client;

import dev.logos.net.WrapSwingPayload;
import dev.logos.runtime.ManaWrap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Tells the server when the player's wrapped hand swings at an entity or at air, charged to {@link ManaWrap#CHARGE},
 * so the wrap's on-swing goes off on misses too (see {@link dev.logos.runtime.WrapObj#swingOf}). Read before vanilla
 * resets the charge; a swing at a block mines it and does not count.
 */
final class WrapSwingInput {
    private WrapSwingInput() {}

    static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (!event.isAttack() || mc.player == null || mc.player.isSpectator() || ManaWrap.of(mc.player) == null) return;
        if (mc.hitResult == null || mc.hitResult.getType() == HitResult.Type.BLOCK) return;
        if (mc.player.getAttackStrengthScale(.5f) < ManaWrap.CHARGE) return;
        ClientPacketDistributor.sendToServer(new WrapSwingPayload());
    }
}
