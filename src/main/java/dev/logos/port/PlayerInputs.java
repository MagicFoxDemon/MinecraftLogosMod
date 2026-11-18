package dev.logos.port;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Stands in for 26.1's {@code ServerPlayer#getLastClientInput}: the movement keys a player last held, which the
 * client sends ({@code dev.logos.client.port.PlayerInputSender}) whenever they change. 1.21.1's server knows them
 * only partly, and only while riding.
 */
public final class PlayerInputs {
    /** Stands in for 26.1's {@code net.minecraft.world.entity.player.Input}. */
    public record Input(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean shift, boolean sprint) {
        public static final Input EMPTY = new Input(false, false, false, false, false, false, false);

        int bits() {
            return (forward ? 1 : 0) | (backward ? 2 : 0) | (left ? 4 : 0) | (right ? 8 : 0) | (jump ? 16 : 0) | (shift ? 32 : 0) | (sprint ? 64 : 0);
        }

        static Input of(int b) {
            return new Input((b & 1) != 0, (b & 2) != 0, (b & 4) != 0, (b & 8) != 0, (b & 16) != 0, (b & 32) != 0, (b & 64) != 0);
        }
    }

    public record Payload(Input input) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("logos", "player_input"));
        public static final StreamCodec<ByteBuf, Payload> CODEC = ByteBufCodecs.VAR_INT.map(b -> new Payload(Input.of(b)), p -> p.input().bits());

        @Override
        public Type<Payload> type() {
            return TYPE;
        }
    }

    private static final Map<UUID, Input> LAST = new ConcurrentHashMap<>();

    private PlayerInputs() {}

    public static Input last(Player player) {
        return LAST.getOrDefault(player.getUUID(), Input.EMPTY);
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(Payload.TYPE, Payload.CODEC, PlayerInputs::handle);
    }

    private static void handle(Payload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sp) LAST.put(sp.getUUID(), payload.input());
    }

    @net.neoforged.fml.common.EventBusSubscriber(modid = "logos")
    static final class Cleanup {
        private Cleanup() {}

        @net.neoforged.bus.api.SubscribeEvent
        static void onLogout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
            forget(event.getEntity());
        }
    }

    /** Forgets a player who left. */
    public static void forget(Player player) {
        LAST.remove(player.getUUID());
    }
}
