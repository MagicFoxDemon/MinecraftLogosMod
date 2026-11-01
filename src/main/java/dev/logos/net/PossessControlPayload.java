package dev.logos.net;

import dev.logos.LogosMod;
import dev.logos.runtime.Possession;
import dev.logos.runtime.PossessionInventory;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: a possessing player's left click (a blow) or use button (a shot) at the entity
 * they aim at (or -1), their inventory key (the creature's inventory), or the instinct key pressed
 * (at what they aim at) or let go.
 */
public record PossessControlPayload(byte action, int target) implements CustomPacketPayload {
    public static final byte STRIKE = 0, SHOOT = 1, INVENTORY = 2, INSTINCT = 3, STEER = 4;
    public static final Type<PossessControlPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "possess_control"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PossessControlPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BYTE, PossessControlPayload::action, ByteBufCodecs.VAR_INT, PossessControlPayload::target, PossessControlPayload::new);

    @Override
    public Type<PossessControlPayload> type() {
        return TYPE;
    }

    public static void handle(PossessControlPayload p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        var target = p.target < 0 ? null : sp.level().getEntity(p.target);
        switch (p.action) {
            case STRIKE -> Possession.strike(sp, target);
            case SHOOT -> Possession.shoot(sp, target);
            case INVENTORY -> PossessionInventory.open(sp);
            case INSTINCT -> Possession.instinct(sp, target, true);
            case STEER -> Possession.instinct(sp, null, false);
            default -> {}
        }
    }
}
