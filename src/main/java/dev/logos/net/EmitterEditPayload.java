package dev.logos.net;

import dev.logos.LogosMod;
import dev.logos.block.SpellEmitterBlockEntity;
import dev.logos.graph.SpecialNodes.EmitterMode;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.lang.LogosError;
import dev.logos.lang.Report;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: save an edit made in a spell emitter's workbench, the page's spell and the
 * emitter's own settings, and with {@code fire} (试射) cast it once as soon as it is charged. As with
 * the inscription table, the server recompiles {@code source} and keeps {@code graph} for reopening.
 */
public record EmitterEditPayload(BlockPos pos, String source, CompoundTag graph, EmitterMode mode, int interval, boolean fire)
        implements CustomPacketPayload {
    public static final Type<EmitterEditPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "emitter_edit"));
    private static final StreamCodec<ByteBuf, EmitterMode> MODE = ByteBufCodecs.idMapper(i -> EmitterMode.values()[Math.floorMod(i, EmitterMode.values().length)], EmitterMode::ordinal);
    public static final StreamCodec<RegistryFriendlyByteBuf, EmitterEditPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, EmitterEditPayload::pos,
            ByteBufCodecs.stringUtf8(InscribePayload.MAX_SOURCE), EmitterEditPayload::source,
            ByteBufCodecs.COMPOUND_TAG, EmitterEditPayload::graph,
            MODE, EmitterEditPayload::mode,
            ByteBufCodecs.VAR_INT, EmitterEditPayload::interval,
            ByteBufCodecs.BOOL, EmitterEditPayload::fire,
            EmitterEditPayload::new);

    @Override
    public Type<EmitterEditPayload> type() {
        return TYPE;
    }

    public static void handle(EmitterEditPayload p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp) || sp.distanceToSqr(p.pos.getCenter()) > 64) return;
        if (!(sp.level().getBlockEntity(p.pos) instanceof SpellEmitterBlockEntity emitter) || emitter.page().isEmpty() || !emitter.mayChange(sp)) return;
        Spell spell;
        try {
            spell = Casting.compile(p.source);
        } catch (LogosError e) {
            Casting.message(sp, e);
            return;
        }
        if (spell.isPassive()) {
            sp.sendOverlayMessage(Component.literal("术式发射器只施放主动术式").withStyle(ChatFormatting.YELLOW));
            return;
        }
        PageTier tier = SpellPages.tier(emitter.page());
        emitter.edit(SpellPages.write(emitter.page(), p.source, p.graph), p.mode, p.interval, sp);
        sp.level().playSound(null, p.pos, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 1, 1.2f);
        Report.summary(spell, PlayerMagic.of(sp).stability, sp::sendSystemMessage);
        dev.logos.item.SpellPageItem.warnCapacity(sp, spell, tier == null ? PageTier.BASIC : tier);
        if (p.fire) emitter.testFire();
    }
}
