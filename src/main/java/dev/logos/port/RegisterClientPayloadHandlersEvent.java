package dev.logos.port;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

/**
 * Stands in for 26.1's {@code net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent}: where the
 * client says how it handles the payloads the server sends. Posted on the mod buses once, during client setup
 * ({@link ClientPayloads#postRegistration}).
 */
public final class RegisterClientPayloadHandlersEvent extends Event implements IModBusEvent {
    RegisterClientPayloadHandlersEvent() {}

    public <T extends CustomPacketPayload> void register(CustomPacketPayload.Type<T> type, IPayloadHandler<T> handler) {
        ClientPayloads.handle(type, handler);
    }
}
