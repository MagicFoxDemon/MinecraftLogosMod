package dev.logos.net;

import dev.logos.LogosMod;
import dev.logos.runtime.VoiceCasting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: while listening (言出法随), the player named the spell on entry {@code index} of
 * what their main-hand wand reaches; {@code name} is that page's voice name. Recognition runs on the
 * client, against the player's own recordings, which never leave it.
 */
public record VoiceCastPayload(int index, String name) implements CustomPacketPayload {
    public static final Type<VoiceCastPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "voice_cast"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VoiceCastPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, VoiceCastPayload::index,
            ByteBufCodecs.stringUtf8(256), VoiceCastPayload::name,
            VoiceCastPayload::new);

    @Override
    public Type<VoiceCastPayload> type() {
        return TYPE;
    }

    public static void handle(VoiceCastPayload p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp) VoiceCasting.heard(sp, p.index, p.name);
    }
}
