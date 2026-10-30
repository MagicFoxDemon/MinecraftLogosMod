package dev.logos.client;

import org.jspecify.annotations.Nullable;

import dev.logos.item.StaffItem;
import dev.logos.net.StaffFillPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Left-click with a staff in the main hand fills it ({@link dev.logos.runtime.StaffCharging}): tells the
 * server when the button goes down and up, and keeps the click from attacking or breaking blocks.
 */
final class StaffInput {
    private static boolean filling;

    private StaffInput() {}

    private static boolean holding(@Nullable Player p) {
        return p != null && p.getMainHandItem().getItem() instanceof StaffItem;
    }

    /** Each client tick: whether left-click is held over the world with a staff in hand. */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            filling = false;
            return;
        }
        boolean down = holding(mc.player) && mc.screen == null && !mc.player.isSpectator() && mc.options.keyAttack.isDown();
        if (down != filling) ClientPacketDistributor.sendToServer(new StaffFillPayload(down));
        filling = down;
    }

    static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack() || !holding(Minecraft.getInstance().player)) return;
        event.setSwingHand(false);
        event.setCanceled(true);
    }
}
