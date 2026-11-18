package dev.logos.client.port;

import dev.logos.port.PlayerInputs;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Sends the player's movement keys whenever they change ({@link PlayerInputs}), as a 26.1 client always does. */
@EventBusSubscriber(modid = "logos", value = Dist.CLIENT)
public final class PlayerInputSender {
    private static PlayerInputs.Input sent = PlayerInputs.Input.EMPTY;

    private PlayerInputSender() {}

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            sent = PlayerInputs.Input.EMPTY;
            return;
        }
        var o = mc.options;
        var now = new PlayerInputs.Input(o.keyUp.isDown(), o.keyDown.isDown(), o.keyLeft.isDown(), o.keyRight.isDown(),
                o.keyJump.isDown(), o.keyShift.isDown(), o.keySprint.isDown());
        if (now.equals(sent)) return;
        sent = now;
        PacketDistributor.sendToServer(new PlayerInputs.Payload(now));
    }
}
