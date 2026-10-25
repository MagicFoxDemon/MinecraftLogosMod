package dev.logos.net;

import dev.logos.LogosMod;
import dev.logos.sage.SageServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server, operators only: read the server sage's settings ({@code save} false), or save
 * them. An empty {@code key} keeps the stored one and {@code clearKey} removes it, so the key is only
 * ever sent up, never back down ({@link SageAdminStatePayload} carries a hint of it).
 */
public record SageAdminPayload(boolean save, boolean offered, String url, String model, String key, boolean clearKey,
                               int cooldownSeconds, int dailyLimit, int maxConcurrent) implements CustomPacketPayload {
    public static final Type<SageAdminPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "sage_admin"));
    public static final StreamCodec<FriendlyByteBuf, SageAdminPayload> CODEC = CustomPacketPayload.codec(SageAdminPayload::write,
            b -> new SageAdminPayload(b.readBoolean(), b.readBoolean(), b.readUtf(512), b.readUtf(128), b.readUtf(512), b.readBoolean(),
                    b.readVarInt(), b.readVarInt(), b.readVarInt()));

    public static SageAdminPayload read() {
        return new SageAdminPayload(false, false, "", "", "", false, 0, 0, 0);
    }

    private void write(FriendlyByteBuf b) {
        b.writeBoolean(save);
        b.writeBoolean(offered);
        b.writeUtf(url, 512);
        b.writeUtf(model, 128);
        b.writeUtf(key, 512);
        b.writeBoolean(clearKey);
        b.writeVarInt(cooldownSeconds);
        b.writeVarInt(dailyLimit);
        b.writeVarInt(maxConcurrent);
    }

    @Override
    public Type<SageAdminPayload> type() {
        return TYPE;
    }

    public static void handle(SageAdminPayload p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp) SageServer.admin(sp, p);
    }
}
