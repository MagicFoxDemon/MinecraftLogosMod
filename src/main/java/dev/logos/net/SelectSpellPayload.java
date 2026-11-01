package dev.logos.net;

import dev.logos.LogosMod;
import dev.logos.item.SpellBag;
import dev.logos.item.WandCharmItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client → server: the spell wheel picked entry {@code index} for the held wand, or {@link #VOICE} for 言出法随. */
public record SelectSpellPayload(int index) implements CustomPacketPayload {
    /** The wheel's 言出法随 entry, offered by a 言灵坠 on the wand. */
    public static final int VOICE = -1;
    public static final Type<SelectSpellPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "select_spell"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SelectSpellPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SelectSpellPayload::index, SelectSpellPayload::new);

    @Override
    public Type<SelectSpellPayload> type() {
        return TYPE;
    }

    public static void handle(SelectSpellPayload p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        var wand = SpellBag.heldWand(sp);
        if (wand == null) return;
        if (p.index == VOICE) {
            if (!WandCharmItem.on(wand, LogosMod.VOICE_CHARM.get())) return;
            wand.set(LogosMod.VOICE_SELECTED.get(), true);
            sp.sendOverlayMessage(Component.literal("✦ 已选择言出法随：按住右键，念出咒语").withStyle(ChatFormatting.LIGHT_PURPLE));
            return;
        }
        var entries = SpellBag.entries(sp, wand);
        if (p.index < 0 || p.index >= entries.size()) return;
        wand.remove(LogosMod.VOICE_SELECTED.get());
        wand.set(LogosMod.SELECTED.get(), p.index);
        sp.sendOverlayMessage(Component.literal("✦ 已选择《" + entries.get(p.index).spell().name() + "》").withStyle(ChatFormatting.LIGHT_PURPLE));
    }
}
