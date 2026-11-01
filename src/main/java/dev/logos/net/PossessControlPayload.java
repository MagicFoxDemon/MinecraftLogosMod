package dev.logos.net;

import dev.logos.LogosMod;
import dev.logos.runtime.Possession;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client → server: a possessing player's left click (a blow) or use button (a shot), at the entity they aim at, or -1. */
public record PossessControlPayload(boolean shoot, int target) implements CustomPacketPayload {
    public static final Type<PossessControlPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "possess_control"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PossessControlPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, PossessControlPayload::shoot, ByteBufCodecs.VAR_INT, PossessControlPayload::target, PossessControlPayload::new);

    @Override
    public Type<PossessControlPayload> type() {
        return TYPE;
    }

    public static void handle(PossessControlPayload p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        var target = p.target < 0 ? null : sp.level().getEntity(p.target);
        if (p.shoot) Possession.shoot(sp, target);
        else Possession.strike(sp, target);
    }
}
