package dev.logos.net;

import dev.logos.LogosMod;
import dev.logos.runtime.Anchors;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server, from the anchor screens: drive an anchor nail into the face of block {@code pos}
 * under {@code name}, or (with {@code remove}) pull up anchor {@code name}.
 */
public record AnchorEditPayload(String name, BlockPos pos, Direction face, boolean remove) implements CustomPacketPayload {
    public static final Type<AnchorEditPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "anchor_edit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AnchorEditPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, AnchorEditPayload::name, BlockPos.STREAM_CODEC, AnchorEditPayload::pos,
            Direction.STREAM_CODEC, AnchorEditPayload::face, ByteBufCodecs.BOOL, AnchorEditPayload::remove, AnchorEditPayload::new);

    public static AnchorEditPayload set(String name, BlockPos pos, Direction face) {
        return new AnchorEditPayload(name, pos, face, false);
    }

    public static AnchorEditPayload remove(String name) {
        return new AnchorEditPayload(name, BlockPos.ZERO, Direction.UP, true);
    }

    @Override
    public Type<AnchorEditPayload> type() {
        return TYPE;
    }

    public static void handle(AnchorEditPayload p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        if (p.remove) {
            if (Anchors.remove(sp, p.name)) sp.sendOverlayMessage(Component.literal("⚓ 已撤去锚点 " + p.name).withStyle(ChatFormatting.GOLD));
            return;
        }
        if (!sp.isWithinBlockInteractionRange(p.pos, 1.0)) return;
        // The anchor stands on the clicked face: in the block just outside it, at its floor.
        BlockPos at = p.pos.relative(p.face);
        String why = Anchors.set(sp, p.name, sp.level(), Vec3.atBottomCenterOf(at));
        if (why != null) {
            sp.sendOverlayMessage(Component.literal("⚓ " + why).withStyle(ChatFormatting.RED));
            return;
        }
        sp.level().playSound(null, at, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 0.8f);
        sp.sendOverlayMessage(Component.literal("⚓ 锚点 ^" + Anchors.clean(p.name) + " 已立于 " + at.toShortString()).withStyle(ChatFormatting.GOLD));
    }
}
