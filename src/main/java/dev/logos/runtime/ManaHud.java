package dev.logos.runtime;

import java.util.Optional;

import dev.logos.item.SpellBag;
import dev.logos.item.StaffItem;
import dev.logos.lang.Node;
import dev.logos.lang.Spell;
import dev.logos.net.ManaHudPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** What the mana HUD shows. The client draws it (client/ManaHudLayer), so the action bar stays free for real messages. */
public final class ManaHud {
    /** Ticks between checks, which caps updates at two a second, as often as the old HUD, however fast mana flows. */
    public static final int INTERVAL = 10;

    private ManaHud() {}

    /**
     * Tells {@code sp}'s client what its HUD shows, if that differs from what it last heard. Only displayed values
     * count: mana moving within a whole unit sends nothing, and neither does a full pool. A player's new
     * {@link PlayerMagic} (login, respawn) has told it nothing yet, so even a hidden HUD goes out once.
     */
    public static void sync(ServerPlayer sp) {
        if (!sp.connection.hasChannel(ManaHudPayload.TYPE)) return;
        PlayerMagic m = PlayerMagic.of(sp);
        ManaHudPayload now = state(sp, m);
        ManaHudPayload sent = m.hudSent;
        if (now.equals(sent)) return;
        m.hudSent = now;
        PacketDistributor.sendToPlayer(sp, sent != null && now.spell().equals(sent.spell()) ? now.withoutSpell() : now);
    }

    private static ManaHudPayload state(ServerPlayer sp, PlayerMagic m) {
        ItemStack wand = SpellBag.heldWand(sp);
        if (wand == null) return ManaHudPayload.HIDDEN;
        double cap = PlayerMagic.maxMana(sp);
        int filled = (int) Math.min(10, Math.round(10 * m.mana / Math.max(1, cap)));
        // A staff casts from what it holds, not from the pool.
        double source = m.mana;
        int stored = 0, capacity = 0;
        if (wand.getItem() instanceof StaffItem staff) {
            source = StaffCharging.held(sp, wand);
            stored = (int) source;
            capacity = (int) staff.capacity();
        }
        return new ManaHudPayload(true, (int) m.mana, (int) cap, filled, stored, capacity, Optional.of(spellText(sp, m, wand, source)));
    }

    private static Component spellText(ServerPlayer sp, PlayerMagic m, ItemStack wand, double source) {
        if (VoiceCasting.armed(wand)) {
            // 言出法随: listening, then the spell that was named (where the selected spell would show).
            if (m.listen != null) {
                return (m.listen.injected() > 0 ? Component.translatable("logos.hud.voice.listening_gathered", Node.fmt(m.listen.injected()))
                        : Component.translatable("logos.hud.voice.listening"))
                        .withStyle(ChatFormatting.LIGHT_PURPLE);
            }
            if (m.charge != null && sp.isUsingItem()) {
                return Component.translatable("logos.hud.voice.releasing", m.charge.spell().name(), Node.fmt(m.charge.injected), Node.fmt(m.charge.in.mana()))
                        .withStyle(ChatFormatting.LIGHT_PURPLE);
            }
            int n = VoiceCasting.spoken(sp, wand).size();
            return (n == 0 ? Component.translatable("logos.hud.voice.none") : Component.translatable("logos.hud.voice.ready", n))
                    .withStyle(n == 0 ? ChatFormatting.RED : ChatFormatting.GRAY);
        }
        var selected = SpellBag.selected(sp, wand);
        if (selected == null) return Component.empty();
        Spell s = selected.spell();
        var in = Casting.injection(s, selected.tier(), Casting.immolation(sp, wand));
        ChatFormatting c = source >= in.mana() ? ChatFormatting.GRAY : ChatFormatting.RED;
        String inject = in.mana() < s.mana() ? Node.fmt(in.mana()) + "/" + Node.fmt(s.mana()) : Node.fmt(in.mana());
        MutableComponent line = Component.translatable("logos.hud.spell", s.name(), inject);
        if (in.burns()) line.append(Component.translatable("logos.hud.spell.burns"));
        return line.append(Component.translatable("logos.hud.spell.stability", Node.fmt(s.stability()), m.stability)).withStyle(c);
    }
}
