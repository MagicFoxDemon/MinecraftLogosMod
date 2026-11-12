package dev.logos.gametest;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * A test's mock player has no client, so its connection knows no mod's channels, and a mod that sends to every player
 * who joins without asking (Touhou Little Maid's sync on joining a level) fails the test that made the player. Mock
 * players joining in a test run are given that mod's channels first, as a client with the mod would have them.
 */
final class MockClientChannels {
    private MockClientChannels() {}

    /** From now on, every player joining a level knows the play channels the mod {@code namespace} registered. */
    static void knowChannelsOf(String namespace) {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (EntityJoinLevelEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer p && p.connection != null) {
                ChannelAttributes.getOrCreateAdHocChannels(p.connection.getConnection()).addAll(channels(namespace));
            }
        });
    }

    /** The play payloads {@code namespace} registered (NeoForge keeps them to itself, so looked up by reflection). */
    @SuppressWarnings("unchecked")
    private static Set<Identifier> channels(String namespace) {
        try {
            var field = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
            field.setAccessible(true);
            var all = (Map<ConnectionProtocol, Map<Identifier, ?>>) field.get(null);
            return all.get(ConnectionProtocol.PLAY).keySet().stream().filter(id -> id.getNamespace().equals(namespace)).collect(Collectors.toSet());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not read the registered payloads", e);
        }
    }
}
