package dev.logos.client;

import com.mojang.blaze3d.platform.InputConstants;

import dev.logos.net.RevertPayload;
import dev.logos.runtime.Incarnation;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;

/**
 * An incarnated player's own key: the revert key (V by default) gives them their own shape back,
 * for free, whatever the form — one without hands cannot hold a wand to cast 解除化身.
 */
final class IncarnationInput {
    /** Active only while incarnated, so sharing V with the instinct and mount mode keys is no conflict. */
    private static final IKeyConflictContext INCARNATED = new IKeyConflictContext() {
        @Override
        public boolean isActive() {
            return Minecraft.getInstance().screen == null && Incarnation.active(Minecraft.getInstance().player);
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return this == other;
        }
    };
    static final KeyMapping REVERT = new KeyMapping("key.logos.revert", INCARNATED, InputConstants.Type.KEYSYM, InputConstants.KEY_V, LogosClient.CATEGORY);

    private IncarnationInput() {}

    /** Each client tick, before the game's own keys. */
    static void tick() {
        while (REVERT.consumeClick()) {
            if (Incarnation.active(Minecraft.getInstance().player)) ClientPacketDistributor.sendToServer(RevertPayload.INSTANCE);
        }
    }
}
