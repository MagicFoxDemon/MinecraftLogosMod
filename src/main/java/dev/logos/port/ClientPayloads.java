package dev.logos.port;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.logging.LogUtils;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.fml.ModLoader;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 26.1 registers a payload the server sends without a handler, which the client gives later; 1.21.1 wants the handler
 * at once. {@link #playToClient} registers such a payload with a handler that looks up the client's, given through
 * {@link RegisterClientPayloadHandlersEvent}. On a dedicated server nothing is ever looked up.
 */
public final class ClientPayloads {
    private static final Map<CustomPacketPayload.Type<?>, IPayloadHandler<?>> HANDLERS = new ConcurrentHashMap<>();
    private static boolean posted;

    private ClientPayloads() {}

    public static <T extends CustomPacketPayload> void playToClient(PayloadRegistrar registrar, CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        registrar.playToClient(type, codec, ClientPayloads::dispatch);
    }

    static <T extends CustomPacketPayload> void handle(CustomPacketPayload.Type<T> type, IPayloadHandler<T> handler) {
        HANDLERS.put(type, handler);
    }

    /** Asks the client's listeners for their handlers; call once, on the client, during setup. */
    public static synchronized void postRegistration() {
        if (posted) return;
        posted = true;
        ModLoader.postEvent(new RegisterClientPayloadHandlersEvent());
    }

    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> void dispatch(T payload, IPayloadContext context) {
        IPayloadHandler<T> handler = (IPayloadHandler<T>) HANDLERS.get(payload.type());
        if (handler == null) {
            LogUtils.getLogger().warn("No client handler for payload {}", payload.type().id());
            return;
        }
        handler.handle(payload, context);
    }
}
