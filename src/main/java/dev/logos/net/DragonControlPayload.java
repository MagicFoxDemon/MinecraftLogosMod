package dev.logos.net;

import dev.logos.LogosMod;
import dev.logos.runtime.DragonFamiliar;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client → server: a dragon rider's control in drive mode (a fireball, breath on, breath off); see {@link DragonFamiliar}. */
public record DragonControlPayload(byte action) implements CustomPacketPayload {
    public static final Type<DragonControlPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "dragon_control"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DragonControlPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BYTE, DragonControlPayload::action, DragonControlPayload::new);

    @Override
    public Type<DragonControlPayload> type() {
        return TYPE;
    }

    public static void handle(DragonControlPayload p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp) DragonFamiliar.control(sp, p.action);
    }
}
