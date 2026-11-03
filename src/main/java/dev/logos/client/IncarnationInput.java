package dev.logos.client;

import com.mojang.blaze3d.platform.InputConstants;

import dev.logos.net.FormSkillPayload;
import dev.logos.net.RevertPayload;
import dev.logos.runtime.Incarnation;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;

/**
 * An incarnated player's keys: the revert key (V by default) gives them their own shape back, for
 * free, whatever the form — one without hands cannot hold a wand to cast 解除化身; and the use
 * button is their form's skill, if it has one.
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

    /**
     * The use button as the form's skill ({@link Incarnation#skillOnUse}), at what the player aims at.
     * A form with hands still opens a door or a chest, or talks to a villager, in front of it.
     */
    static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        if (!event.isUseItem() || !Incarnation.skillOnUse(p)) return;
        Incarnation.Form f = Incarnation.form(p);
        if (f.hands() && mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) return;
        event.setSwingHand(false);
        event.setCanceled(true);
        Entity target = PossessionInput.aimed(p);
        ClientPacketDistributor.sendToServer(new FormSkillPayload(target == null ? -1 : target.getId()));
    }
}
