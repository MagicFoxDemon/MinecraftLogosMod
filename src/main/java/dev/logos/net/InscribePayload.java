package dev.logos.net;

import dev.logos.LogosMod;
import dev.logos.block.InscriptionTableBlock;
import dev.logos.lang.LogosError;
import dev.logos.lang.Report;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: write a spell drawn in the workbench onto the page lying on the inscription
 * table at {@code pos}. The server never trusts the client's compilation; it recompiles
 * {@code source} and keeps {@code graph} only so the editor can reopen the drawing.
 */
public record InscribePayload(BlockPos pos, String source, CompoundTag graph) implements CustomPacketPayload {
    public static final int MAX_SOURCE = 32_000;

    public static final Type<InscribePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "inscribe"));
    public static final StreamCodec<RegistryFriendlyByteBuf, InscribePayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, InscribePayload::pos,
            ByteBufCodecs.stringUtf8(MAX_SOURCE), InscribePayload::source,
            ByteBufCodecs.COMPOUND_TAG, InscribePayload::graph,
            InscribePayload::new);

    @Override
    public Type<InscribePayload> type() {
        return TYPE;
    }

    public static void handle(InscribePayload p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        Spell spell;
        try {
            spell = Casting.compile(p.source);
        } catch (LogosError e) {
            Casting.message(sp, e);
            return;
        }
        var tier = InscriptionTableBlock.inscribe(sp, p.pos, p.source, p.graph);
        if (tier != null) {
            Report.summary(spell, PlayerMagic.of(sp).stability, sp::sendSystemMessage);
            dev.logos.item.SpellPageItem.warnCapacity(sp, spell, tier);
        }
    }
}
