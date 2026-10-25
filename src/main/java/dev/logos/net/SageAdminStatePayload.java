package dev.logos.net;

import dev.logos.LogosMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server → an operator's client: the server sage's settings for its settings page. The key itself
 * is never included, only {@code keyHint} (its last four characters); the {@code *FromEnv} flags say
 * which fields an environment variable supplies, so the page shows them locked.
 */
public record SageAdminStatePayload(boolean offered, String url, String model, String keyHint, boolean urlFromEnv, boolean modelFromEnv,
                                    boolean keyFromEnv, int cooldownSeconds, int dailyLimit, int maxConcurrent) implements CustomPacketPayload {
    public static final Type<SageAdminStatePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "sage_admin_state"));
    public static final StreamCodec<FriendlyByteBuf, SageAdminStatePayload> CODEC = CustomPacketPayload.codec(SageAdminStatePayload::write,
            b -> new SageAdminStatePayload(b.readBoolean(), b.readUtf(512), b.readUtf(128), b.readUtf(16), b.readBoolean(), b.readBoolean(),
                    b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readVarInt()));

    private void write(FriendlyByteBuf b) {
        b.writeBoolean(offered);
        b.writeUtf(url, 512);
        b.writeUtf(model, 128);
        b.writeUtf(keyHint, 16);
        b.writeBoolean(urlFromEnv);
        b.writeBoolean(modelFromEnv);
        b.writeBoolean(keyFromEnv);
        b.writeVarInt(cooldownSeconds);
        b.writeVarInt(dailyLimit);
        b.writeVarInt(maxConcurrent);
    }

    @Override
    public Type<SageAdminStatePayload> type() {
        return TYPE;
    }
}
