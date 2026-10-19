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
 * Client → server, from the anchor screens: drive an anchor nail at block {@code pos} under
 * {@code name}, on the clicked face or into the block itself, or (with {@code remove}) pull up anchor {@code name}.
 */
public record AnchorEditPayload(String name, BlockPos pos, Direction face, boolean into, boolean remove) implements CustomPacketPayload {
    public static final Type<AnchorEditPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "anchor_edit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AnchorEditPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, AnchorEditPayload::name, BlockPos.STREAM_CODEC, AnchorEditPayload::pos,
            Direction.STREAM_CODEC, AnchorEditPayload::face, ByteBufCodecs.BOOL, AnchorEditPayload::into,
            ByteBufCodecs.BOOL, AnchorEditPayload::remove, AnchorEditPayload::new);

    /** {@code into}: in the clicked block itself, at its centre; otherwise on the clicked face, at the floor of the block outside it. */
    public static AnchorEditPayload set(String name, BlockPos pos, Direction face, boolean into) {
        return new AnchorEditPayload(name, pos, face, into, false);
    }

    public static AnchorEditPayload remove(String name) {
        return new AnchorEditPayload(name, BlockPos.ZERO, Direction.UP, false, true);
    }

    /** Where an anchor driven at {@code pos}'s {@code face} stands: see {@link #set}. */
    public static Vec3 place(BlockPos pos, Direction face, boolean into) {
        return into ? Vec3.atCenterOf(pos) : Vec3.atBottomCenterOf(pos.relative(face));
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
        BlockPos at = p.into ? p.pos : p.pos.relative(p.face);
        String why = Anchors.set(sp, p.name, sp.level(), place(p.pos, p.face, p.into));
        if (why != null) {
            sp.sendOverlayMessage(Component.literal("⚓ " + why).withStyle(ChatFormatting.RED));
            return;
        }
        sp.level().playSound(null, at, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 0.8f);
        sp.sendOverlayMessage(Component.literal("⚓ 锚点 ^" + Anchors.clean(p.name) + (p.into ? " 已钉进方块 " : " 已立于 ") + at.toShortString())
                .withStyle(ChatFormatting.GOLD));
    }
}
