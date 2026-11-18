package dev.logos.gametest;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * GameTestHelper.makeMockServerPlayerInLevel, with the connection NeoForge sets up for tests: 1.21.1's vanilla helper
 * leaves its fake connection without any channel, so the first synced attachment sent to the player throws.
 */
final class MockPlayers {
    private MockPlayers() {}

    static ServerPlayer inLevel(GameTestHelper h) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "test-mock-player"), false);
        ServerPlayer player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation()) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return true;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        NetworkRegistry.configureMockConnection(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    /**
     * Lets {@code player} be hurt from now on (26.1's {@code connection.markClientLoaded()}): 1.21.1 spares a player for
     * three seconds after joining.
     */
    static void loaded(ServerPlayer player) {
        player.spawnInvulnerableTime = 0;
    }
}
