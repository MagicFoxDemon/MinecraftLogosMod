package dev.logos.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import dev.logos.LogosMod;
import dev.logos.item.PageTier;
import dev.logos.item.SpellBag;
import dev.logos.item.SpellPages;
import dev.logos.item.StaffItem;
import dev.logos.lang.Node;
import dev.logos.net.ManaHudPayload;
import dev.logos.runtime.ManaHud;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.VoiceCasting;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** The mana HUD stays off the action bar, and its client hears of it only when what it shows changes. */
final class ManaHudGameTests {
    /** 100 asked of a crystal page, which holds 150. */
    private static final String SPELL = "(spell :name \"测\" :mana 100 (mark caster #hud))";

    private ManaHudGameTests() {}

    private static ItemStack hung(ItemStack holder, ItemStack... slots) {
        holder.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(slots)));
        return holder;
    }

    private static ItemStack wand() {
        return hung(new ItemStack(LogosMod.WOOD_WANDS.getFirst().get()), SpellPages.inscribed(PageTier.CRYSTAL, SPELL, null));
    }

    /** A player with 1000 of 1000 mana holding {@code held}. Mock players only tick when told to: see {@link #ticking}. */
    private static ServerPlayer holder(GameTestHelper h, ItemStack held) {
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        PlayerMagic m = PlayerMagic.of(p);
        m.baseMaxMana = m.mana = 1000;
        m.trace = false;
        p.setItemInHand(InteractionHand.MAIN_HAND, held);
        // What a real client announces on joining; a mock player has no client to do it.
        NetworkRegistry.onMinecraftRegister(p.connection.getConnection(), Set.of(ManaHudPayload.TYPE.id()));
        drain(p);
        return p;
    }

    private static void ticking(GameTestHelper h, ServerPlayer p) {
        h.onEachTick(p::doTick);
    }

    /** Everything sent down {@code p}'s mock connection since the last call; the server holds back its flush until the tick ends, so this flushes first. */
    private static List<Object> drain(ServerPlayer p) {
        EmbeddedChannel channel = (EmbeddedChannel) p.connection.getConnection().channel();
        channel.flushOutbound();
        Queue<Object> queue = channel.outboundMessages();
        List<Object> sent = new ArrayList<>(queue);
        queue.clear();
        return sent;
    }

    private static List<ManaHudPayload> huds(List<Object> sent) {
        List<ManaHudPayload> out = new ArrayList<>();
        for (Object o : sent) {
            if (o instanceof ClientboundCustomPayloadPacket c && c.payload() instanceof ManaHudPayload hud) out.add(hud);
        }
        return out;
    }

    private static long overlays(List<Object> sent) {
        return sent.stream().filter(o -> o instanceof ClientboundSystemChatPacket c && c.overlay()).count();
    }

    /** Checks at once and returns the one update that sends, which must carry the spell text. */
    private static ManaHudPayload syncedWithSpell(GameTestHelper h, ServerPlayer p) {
        ManaHud.sync(p);
        List<ManaHudPayload> huds = huds(drain(p));
        h.assertTrue(huds.size() == 1, "expected one update, got " + huds);
        h.assertTrue(huds.getFirst().spell().isPresent(), "expected the update to carry the spell text: " + huds.getFirst());
        return huds.getFirst();
    }

    /** {@code key} as the server reads it out, the way the HUD's own text is put together. */
    private static String t(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private static String line(ManaHudPayload hud) {
        String spell = hud.spell().orElseThrow().getString();
        return spell.isEmpty() ? hud.head().getString() : hud.head().getString() + " " + spell;
    }

    private static void says(GameTestHelper h, ManaHudPayload hud, String expected, ChatFormatting spellColor) {
        h.assertTrue(line(hud).equals(expected), "the HUD says \"" + line(hud) + "\", not \"" + expected + "\"");
        TextColor color = hud.spell().orElseThrow().getStyle().getColor();
        h.assertTrue(TextColor.fromLegacyFormat(spellColor).equals(color), "the spell text is " + color + ", not " + spellColor);
    }

    /** Ticking in a player's hand, a wand never touches the action bar, and real alerts still go there. */
    static void offActionBar(GameTestHelper h) {
        ServerPlayer p = holder(h, wand());
        PlayerMagic.of(p).mana = 500;
        ticking(h, p);
        h.startSequence()
                .thenIdle(ManaHud.INTERVAL * 6)
                .thenExecute(() -> {
                    List<Object> sent = drain(p);
                    h.assertTrue(overlays(sent) == 0, "the HUD sent " + overlays(sent) + " action-bar messages");
                    h.assertTrue(huds(sent).size() >= 2, "regenerating, the HUD should have sent updates: " + huds(sent));
                    PlayerMagic.alert(p, Component.literal("提示"));
                    h.assertTrue(overlays(drain(p)) == 1, "an alert is a real action-bar message");
                })
                .thenSucceed();
    }

    /** A change sends one update, no change sends none, and mana moving within a whole displayed unit is no change. */
    static void sendsOnChange(GameTestHelper h) {
        ServerPlayer p = holder(h, wand());
        PlayerMagic m = PlayerMagic.of(p);
        ticking(h, p);
        h.startSequence()
                .thenIdle(ManaHud.INTERVAL + 1)
                .thenExecute(() -> {
                    List<ManaHudPayload> first = huds(drain(p));
                    h.assertTrue(first.size() == 1 && first.getFirst().mana() == 1000 && first.getFirst().spell().isPresent(), "the first check tells the client everything: " + first);
                })
                .thenIdle(ManaHud.INTERVAL * 6)
                .thenExecute(() -> {
                    List<ManaHudPayload> idle = huds(drain(p));
                    h.assertTrue(idle.isEmpty(), "nothing changed, yet the HUD sent " + idle);
                    m.mana = 900;
                })
                .thenIdle(ManaHud.INTERVAL + 1)
                .thenExecute(() -> {
                    List<ManaHudPayload> changed = huds(drain(p));
                    h.assertTrue(!changed.isEmpty(), "losing mana sent no update");
                    ManaHudPayload last = changed.getLast();
                    h.assertTrue(last.mana() >= 900 && last.mana() < 910, "the update says " + last.mana() + " instead of about 900");
                    h.assertTrue(last.spell().isEmpty(), "the spell text did not change, so it should not be resent");
                    m.mana = 500.1;
                    ManaHud.sync(p);
                    drain(p);
                    m.mana = 500.9;
                    ManaHud.sync(p);
                    List<ManaHudPayload> within = huds(drain(p));
                    h.assertTrue(within.isEmpty(), "a change within a whole unit sent " + within);
                    m.mana = 501;
                    ManaHud.sync(p);
                    List<ManaHudPayload> whole = huds(drain(p));
                    h.assertTrue(whole.size() == 1 && whole.getFirst().mana() == 501, "a whole unit should send one update: " + whole);
                })
                .thenSucceed();
    }

    /** However fast mana changes, updates stay within the cap, and the numbers still catch up the moment it stops. */
    static void rateCapped(GameTestHelper h) {
        ServerPlayer p = holder(h, wand());
        PlayerMagic m = PlayerMagic.of(p);
        boolean[] draining = {true};
        h.onEachTick(() -> {
            if (draining[0]) m.mana -= 10;
            p.doTick();
        });
        int ticks = ManaHud.INTERVAL * 8;
        h.startSequence()
                .thenIdle(ticks)
                .thenExecute(() -> {
                    List<ManaHudPayload> huds = huds(drain(p));
                    h.assertTrue(huds.size() >= 4 && huds.size() <= ticks / ManaHud.INTERVAL + 1, "expected about one update per " + ManaHud.INTERVAL + " ticks, got " + huds.size() + " in " + ticks);
                    draining[0] = false;
                    // The whole cap: the drain above counts as practice and has raised it a little.
                    m.mana = PlayerMagic.maxMana(p);
                })
                .thenIdle(ManaHud.INTERVAL + 1)
                .thenExecute(() -> {
                    List<ManaHudPayload> huds = huds(drain(p));
                    h.assertTrue(!huds.isEmpty() && huds.getLast().mana() == (int) m.mana, "the numbers stuck: " + huds);
                })
                .thenSucceed();
    }

    /** Putting the wand away hides the HUD once; taking one up again brings it all back. */
    static void hidesWithoutWand(GameTestHelper h) {
        ServerPlayer p = holder(h, wand());
        syncedWithSpell(h, p);
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        ManaHud.sync(p);
        ManaHud.sync(p);
        List<ManaHudPayload> put = huds(drain(p));
        h.assertTrue(put.equals(List.of(ManaHudPayload.pool(1000, 1000))), "expected one update with the pool alone, got " + put);
        p.setItemInHand(InteractionHand.MAIN_HAND, wand());
        h.assertTrue(syncedWithSpell(h, p).wand(), "taking up a wand should bring the spell back");
        h.succeed();
    }

    /** With no wand in hand the pool still goes out as it fills, so the HUD can show it; a full, still pool sends nothing. */
    static void poolWithoutWand(GameTestHelper h) {
        ServerPlayer p = holder(h, ItemStack.EMPTY);
        PlayerMagic m = PlayerMagic.of(p);
        ManaHud.sync(p);
        h.assertTrue(huds(drain(p)).equals(List.of(ManaHudPayload.pool(1000, 1000))), "the first check tells the client the pool");
        m.mana = 400;
        ManaHud.sync(p);
        List<ManaHudPayload> low = huds(drain(p));
        h.assertTrue(low.size() == 1 && !low.getFirst().wand() && low.getFirst().mana() == 400, "a pool not full should go out without a wand: " + low);
        ManaHud.sync(p);
        h.assertTrue(huds(drain(p)).isEmpty(), "nothing changed, yet the HUD sent again");
        h.succeed();
    }

    /** Every case says what the action-bar HUD said, to the character. */
    static void wording(GameTestHelper h) {
        ItemStack wand = wand();
        ServerPlayer p = holder(h, wand);
        PlayerMagic m = PlayerMagic.of(p);
        String stability = t("logos.hud.spell.stability", Node.fmt(SpellBag.selected(p, wand).spell().stability()), m.stability);
        String spell = t("logos.hud.spell", "测", "100");
        says(h, syncedWithSpell(h, p), "☿ 1000/1000 " + spell + stability, ChatFormatting.GRAY);
        m.mana = 50;
        ManaHudPayload low = syncedWithSpell(h, p);
        says(h, low, "☿ 50/1000 " + spell + stability, ChatFormatting.RED);
        h.assertTrue(low.cost() == 100 && low.drawing() == 0, "the mark should stand at the spell's 100, nothing drawn yet: " + low);

        ItemStack staff = hung(new ItemStack(LogosMod.WOOD_STAFFS.getFirst().get()), SpellPages.inscribed(PageTier.CRYSTAL, SPELL, null));
        StaffItem.store(staff, 130);
        p.setItemInHand(InteractionHand.MAIN_HAND, staff);
        int capacity = (int) ((StaffItem) staff.getItem()).capacity();
        says(h, syncedWithSpell(h, p), "☿ 50/1000 " + t("logos.hud.staff", 130, capacity) + " " + spell + stability, ChatFormatting.GRAY);

        ItemStack empty = new ItemStack(LogosMod.WOOD_WANDS.getFirst().get());
        p.setItemInHand(InteractionHand.MAIN_HAND, empty);
        ManaHudPayload bare = syncedWithSpell(h, p);
        h.assertTrue(line(bare).equals("☿ 50/1000"), "a wand with no spell should show the bar alone, not \"" + line(bare) + "\"");

        m.mana = 1000;
        ItemStack charm = new ItemStack(LogosMod.VOICE_CHARM.get());
        ItemStack mute = hung(new ItemStack(LogosMod.WOOD_WANDS.getFirst().get()), SpellPages.inscribed(PageTier.CRYSTAL, SPELL, null), charm);
        mute.set(LogosMod.VOICE_SELECTED.get(), true);
        p.setItemInHand(InteractionHand.MAIN_HAND, mute);
        says(h, syncedWithSpell(h, p), "☿ 1000/1000 " + t("logos.hud.voice.none"), ChatFormatting.RED);

        ItemStack page = SpellPages.inscribed(PageTier.CRYSTAL, SPELL, null);
        page.set(LogosMod.VOICE.get(), "测");
        ItemStack voiced = hung(new ItemStack(LogosMod.WOOD_WANDS.getFirst().get()), page, charm);
        voiced.set(LogosMod.VOICE_SELECTED.get(), true);
        p.setItemInHand(InteractionHand.MAIN_HAND, voiced);
        says(h, syncedWithSpell(h, p), "☿ 1000/1000 " + t("logos.hud.voice.ready", 1), ChatFormatting.GRAY);

        h.assertTrue(VoiceCasting.begin(p, voiced, InteractionHand.MAIN_HAND), "listening did not start");
        says(h, syncedWithSpell(h, p), "☿ 1000/1000 " + t("logos.hud.voice.listening"), ChatFormatting.LIGHT_PURPLE);
        p.startUsingItem(InteractionHand.MAIN_HAND);
        for (int i = 0; i < 2; i++) voiced.getItem().onUseTick(h.getLevel(), p, voiced, p.getUseItemRemainingTicks());
        String gathered = Node.fmt(m.listen.injected());
        ManaHudPayload listening = syncedWithSpell(h, p);
        h.assertTrue(line(listening).endsWith(t("logos.hud.voice.listening_gathered", gathered)), "while gathering the HUD says \"" + line(listening) + "\"");
        VoiceCasting.heard(p, 0, "测");
        ManaHudPayload named = syncedWithSpell(h, p);
        h.assertTrue(line(named).endsWith(t("logos.hud.voice.releasing", "测", gathered, "100")), "once named the HUD says \"" + line(named) + "\"");
        h.succeed();
    }
}
