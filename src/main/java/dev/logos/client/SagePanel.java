package dev.logos.client;

import java.util.ArrayList;
import java.util.List;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.utils.UIElementProvider;

import dev.logos.lang.Compiler;
import dev.logos.lang.LogosError;
import dev.logos.lang.Node;
import dev.logos.lang.Spell;
import dev.logos.net.SageAdminPayload;
import dev.logos.net.SageAdminStatePayload;
import dev.logos.sage.SageAgent;
import dev.logos.sage.SageEndpoint;
import dev.logos.sage.SageSettings;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 书灵 (the book sprite, called "sage" in the code): its page beside the canvas. The player describes a spell, the sage writes it, and
 * its spell is shown on the canvas as a preview the player adopts or puts back; nothing replaces the
 * drawing until they say so. Conversations are kept and can be started, switched and deleted. The
 * same page holds the player's own sage settings and, for operators, the server's.
 */
final class SagePanel extends UIElement {
    /** What the page needs from the editor around it. */
    interface Canvas {
        /** The spell on the canvas, or empty when it does not hold. */
        String currentSource();

        /** How much mana the page being written holds; the sage writes to fit it unless asked not to. */
        int capacity();

        /** Whether the page lies in a spell emitter, which cannot immolate it to go past {@link #capacity()}. */
        boolean emitter();

        /** Shows {@code spell} on the canvas, keeping the player's drawing to put back. */
        void preview(Spell spell);

        boolean previewing();

        /** Keeps what is shown and forgets the drawing kept aside. */
        void adopt();

        /** Puts the player's own drawing back. */
        void revert();
    }

    private static final int INK = 0xFF392719, FADED = 0xFF7A6248, SANGUINE = 0xFF8E2F1C;

    private enum View { CHAT, MINE, SERVER }

    private final Canvas canvas;
    private final Selector<String> chats = new Selector<>();
    private final ScrollerView messages = new ScrollerView();
    private final TextField input = new TextField();
    private final Label status = text(Component.empty(), FADED);
    private final Button send = button("logos.sage.send", this::submit);
    private final Button cancel = button("logos.sage.cancel", this::stop);
    private final UIElement previewBar = row();
    private final UIElement chatView = column();
    private final UIElement settingsView = column();
    private final Button serverTab = button("logos.sage.settings.server", () -> show(View.SERVER));
    private SageChats.Chat chat;
    private SageClient.Handle running;
    private View view = View.CHAT;

    SagePanel(Canvas canvas) {
        this.canvas = canvas;
        setId("sage_panel");
        addClass(ManuscriptControls.INKED);
        layout(l -> l.flex(1).minWidth(0).heightPercent(100).gapAll(4).paddingAll(4));

        Label title = text(Component.translatable("logos.sage.title"), INK);
        title.layout(l -> l.flex(1).widthAuto().minWidth(0));
        Button fresh = button("logos.sage.new", this::newChat);
        fresh.setId("sage_new");
        Button settings = button("logos.sage.settings", () -> show(view == View.CHAT ? View.MINE : View.CHAT));
        settings.setId("sage_settings");
        UIElement header = row();
        header.addChildren(title, fresh, settings);

        chats.setId("sage_chats");
        chats.setCandidateUIProvider(UIElementProvider.text(id -> {
            SageChats.Chat c = SageChats.find(id);
            return c == null || c.title.isEmpty() ? Component.translatable("logos.sage.untitled") : Component.literal(c.title);
        }));
        chats.layout(l -> l.flex(1).minWidth(0));
        chats.setOnValueChanged(id -> {
            SageChats.Chat c = id == null ? null : SageChats.find(id);
            if (c != null && c != chat) open(c);
        });
        ManuscriptControls.paper(chats);
        Button delete = button("logos.sage.delete", this::deleteChat);
        delete.setId("sage_delete");
        UIElement chatRow = row();
        chatRow.addChildren(chats, delete);

        messages.setId("sage_messages");
        messages.layout(l -> l.widthPercent(100).flex(1).minHeight(0));
        messages.style(s -> s.backgroundTexture(IGuiTexture.EMPTY));
        messages.viewPort(v -> v.style(s -> s.backgroundTexture(IGuiTexture.EMPTY)));

        Button adopt = button("logos.sage.adopt", () -> {
            canvas.adopt();
            refreshPreview();
        });
        adopt.setId("sage_adopt");
        Button revert = button("logos.sage.revert", () -> {
            canvas.revert();
            refreshPreview();
        });
        revert.setId("sage_revert");
        Label previewing = text(Component.translatable("logos.sage.previewing"), SANGUINE);
        previewing.layout(l -> l.flex(1).widthAuto().minWidth(0));
        previewBar.addChildren(previewing, adopt, revert);

        status.setId("sage_status");
        status.layout(l -> l.flex(1).widthAuto().minWidth(0));
        cancel.setId("sage_cancel");
        UIElement statusRow = row();
        statusRow.addChildren(status, cancel);

        input.setId("sage_input");
        input.setAnyString();
        input.layout(l -> l.flex(1).minWidth(0).height(14));
        ManuscriptControls.blank(input);
        input.addEventListener(UIEvents.KEY_DOWN, e -> {
            if (e.keyCode == GLFW.GLFW_KEY_ENTER || e.keyCode == GLFW.GLFW_KEY_KP_ENTER) submit();
        });
        send.setId("sage_send");
        UIElement inputRow = row();
        inputRow.addChildren(input, send);

        chatView.layout(l -> l.widthPercent(100).flex(1).minHeight(0).gapAll(4));
        chatView.addChildren(chatRow, messages, previewBar, statusRow, inputRow);
        settingsView.layout(l -> l.widthPercent(100).flex(1).minHeight(0).gapAll(4));
        settingsView.setDisplay(TaffyDisplay.NONE);
        addChildren(header, chatView, settingsView);

        // Writing the page down adopts a preview without passing through this page.
        addEventListener(UIEvents.TICK, e -> refreshPreview());
        List<SageChats.Chat> kept = SageChats.all();
        open(kept.isEmpty() ? SageChats.create() : kept.getFirst());
        idle();
        refreshPreview();
    }

    /** Called when the page is opened: whether the sage can be reached may have changed since. */
    void opened() {
        if (view == View.CHAT && running == null) idle();
        refreshPreview();
    }

    void dress() {
        ManuscriptControls.dressList(chats);
    }

    // ---- conversation ----

    private void newChat() {
        if (chat != null && chat.turns.isEmpty()) return;
        stop();
        open(SageChats.create());
    }

    private void deleteChat() {
        stop();
        if (chat != null) SageChats.delete(chat);
        List<SageChats.Chat> kept = SageChats.all();
        open(kept.isEmpty() ? SageChats.create() : kept.getFirst());
    }

    private void open(SageChats.Chat c) {
        if (running != null && c != chat) stop();
        chat = c;
        refreshChats();
        messages.clearAllScrollViewChildren();
        if (c.turns.isEmpty()) messages.addScrollViewChild(text(Component.translatable("logos.sage.greeting"), FADED));
        for (SageAgent.Turn t : c.turns) messages.addScrollViewChild(turn(t));
        scrollDown();
    }

    private void refreshChats() {
        List<String> ids = new ArrayList<>();
        for (SageChats.Chat c : SageChats.all()) ids.add(c.id);
        chats.setCandidates(ids);
        chats.setSelected(chat.id, false);
    }

    private void submit() {
        String message = input.getValue().strip();
        if (message.isEmpty() || running != null) return;
        if (message.length() > SageAgent.MAX_MESSAGE) message = message.substring(0, SageAgent.MAX_MESSAGE);
        if (SageClient.route() == SageClient.Route.NONE) {
            status.setText(Component.translatable("logos.sage.error.unconfigured"));
            return;
        }
        List<SageAgent.Turn> history = List.copyOf(chat.turns);
        if (chat.turns.isEmpty()) messages.clearAllScrollViewChildren();
        SageAgent.Turn asked = new SageAgent.Turn(true, message, "");
        SageChats.add(chat, asked);
        refreshChats();
        messages.addScrollViewChild(turn(asked));
        scrollDown();
        input.setValue("", false);
        SageChats.Chat askedIn = chat;
        busy();
        running = SageClient.ask(history, message, canvas.currentSource(), canvas.capacity(), canvas.emitter(),
                (key, arg) -> {
                    if (chat == askedIn) status.setText(Component.translatable("logos.sage.status." + key, arg));
                },
                out -> {
                    running = null;
                    SageAgent.Turn answer = out.ok() ? new SageAgent.Turn(false, out.text(), out.source())
                            : new SageAgent.Turn(false, Component.translatable(out.error(), out.detail()).getString(), out.source());
                    SageChats.add(askedIn, answer);
                    if (chat == askedIn) {
                        refreshChats();
                        messages.addScrollViewChild(turn(answer));
                        scrollDown();
                        idle();
                    }
                });
    }

    private void stop() {
        if (running == null) return;
        running.cancel();
        running = null;
        idle();
        status.setText(Component.translatable("logos.sage.cancelled"));
    }

    private void busy() {
        send.setDisplay(TaffyDisplay.NONE);
        cancel.setDisplay(TaffyDisplay.FLEX);
        status.setText(Component.translatable("logos.sage.status.thinking"));
    }

    private void idle() {
        send.setDisplay(TaffyDisplay.FLEX);
        cancel.setDisplay(TaffyDisplay.NONE);
        status.setText(switch (SageClient.route()) {
            case SERVER -> Component.translatable("logos.sage.route.server");
            case LOCAL -> Component.translatable("logos.sage.route.local");
            case NONE -> Component.translatable("logos.sage.error.unconfigured");
        });
    }

    private void refreshPreview() {
        previewBar.setDisplay(canvas.previewing() ? TaffyDisplay.FLEX : TaffyDisplay.NONE);
    }

    private void scrollDown() {
        List<UIElement> children = messages.viewContainer.getChildren();
        if (!children.isEmpty()) messages.scrollToChildDelayed(children.getLast());
    }

    /** One line of the conversation; a spell the sage handed over comes as a card to preview or read. */
    private UIElement turn(SageAgent.Turn t) {
        UIElement box = column();
        box.layout(l -> l.paddingAll(3).gapAll(2).marginBottom(2));
        box.addClass(t.player() ? "sage-player" : "sage-reply");
        Component who = Component.translatable(t.player() ? "logos.sage.you" : "logos.sage.sage");
        box.addChild(text(who.copy().append("：").append(t.text()), t.player() ? FADED : INK));
        if (t.player() || t.source().isEmpty()) return box;

        Spell spell;
        Component summary;
        try {
            spell = Compiler.compile(t.source());
            summary = Component.translatable("logos.sage.card", spell.name(), Node.fmt(spell.mana()), Math.round(spell.profile().estimate()),
                    spell.profile().intentsZh());
        } catch (LogosError e) {
            spell = null;
            summary = Component.translatable("logos.sage.card.broken", e.describe());
        }
        Label source = text(Component.literal(t.source().strip()), FADED);
        source.setDisplay(TaffyDisplay.NONE);
        Spell shown = spell;
        Button preview = button("logos.sage.preview", () -> {
            if (shown == null) return;
            canvas.preview(shown);
            refreshPreview();
        });
        preview.addClass("sage-preview");
        Button raw = button("logos.sage.source", () -> source.setDisplay(!source.isDisplayed()));
        raw.addClass("sage-source");
        UIElement actions = row();
        if (spell != null) actions.addChild(preview);
        actions.addChild(raw);
        box.addChildren(text(summary, SANGUINE), actions, source);
        return box;
    }

    // ---- settings ----

    private void show(View v) {
        view = v;
        chatView.setDisplay(v == View.CHAT ? TaffyDisplay.FLEX : TaffyDisplay.NONE);
        settingsView.setDisplay(v == View.CHAT ? TaffyDisplay.NONE : TaffyDisplay.FLEX);
        SageClient.adminListener = s -> {};
        if (v == View.MINE) mine();
        if (v == View.SERVER) {
            settingsView.clearAllChildren();
            settingsView.addChildren(tabs(), text(Component.translatable("logos.sage.settings.loading"), FADED));
            SageClient.adminListener = this::server;
            ClientPacketDistributor.sendToServer(SageAdminPayload.read());
        }
        if (v == View.CHAT) idle();
    }

    private UIElement tabs() {
        UIElement r = row();
        Button mine = button("logos.sage.settings.mine", () -> show(View.MINE));
        Button back = button("logos.sage.settings.back", () -> show(View.CHAT));
        r.addChild(mine);
        serverTab.setDisplay(SageClient.admin ? TaffyDisplay.FLEX : TaffyDisplay.NONE);
        r.addChildren(serverTab, back);
        return r;
    }

    /** The player's own endpoint; it never leaves this computer. */
    private void mine() {
        settingsView.clearAllChildren();
        SageSettings.Loaded loaded = SageSettings.clientEndpoint();
        SageSettings.Client own = SageSettings.client();
        TextField url = field(loaded.endpoint().url(), loaded.urlFromEnv());
        url.setId("sage_url");
        TextField model = field(loaded.endpoint().model(), loaded.modelFromEnv());
        model.setId("sage_model");
        TextField key = secret(loaded.keyFromEnv());
        key.setId("sage_key");
        Toggle prefer = new Toggle();
        prefer.setText(Component.translatable("logos.sage.settings.prefer_server"));
        prefer.setOn(own.preferServer(), false);
        Label saved = text(Component.empty(), SANGUINE);
        Button save = button("logos.sage.settings.save", () -> {
            String k = loaded.keyFromEnv() || key.getValue().isBlank() ? loaded.endpoint().apiKey() : key.getValue();
            SageSettings.saveClient(new SageSettings.Client(new SageEndpoint(url.getValue(), model.getValue(), k), prefer.getValue()));
            saved.setText(Component.translatable("logos.sage.settings.saved"));
            mine();
        });
        save.setId("sage_save");
        Button clear = button("logos.sage.settings.clear_key", () -> {
            SageSettings.saveClient(new SageSettings.Client(SageSettings.client().endpoint().withKey(""), prefer.getValue()));
            mine();
        });
        UIElement actions = row();
        actions.addChildren(save, clear);
        ScrollerView page = page();
        page.addScrollViewChildren(
                text(Component.translatable("logos.sage.settings.mine.about"), FADED),
                text(Component.translatable("logos.sage.settings.url"), INK), url, envNote(loaded.urlFromEnv(), "LOGOS_AI_URL"),
                text(Component.translatable("logos.sage.settings.model"), INK), model, envNote(loaded.modelFromEnv(), "LOGOS_AI_MODEL"),
                text(keyLabel(loaded.endpoint().keyHint()), INK), key, envNote(loaded.keyFromEnv(), "LOGOS_AI_KEY"),
                prefer, actions, saved,
                text(Component.translatable("logos.sage.settings.where", SageSettings.dir.toString()), FADED));
        settingsView.addChildren(tabs(), page);
    }

    /** The server's endpoint and limits, as the server sent them: the key itself never comes down, only its hint. */
    private void server(SageAdminStatePayload s) {
        if (view != View.SERVER) return;
        settingsView.clearAllChildren();
        Toggle offered = new Toggle();
        offered.setText(Component.translatable("logos.sage.settings.offered"));
        offered.setOn(s.offered(), false);
        TextField url = field(s.url(), s.urlFromEnv());
        TextField model = field(s.model(), s.modelFromEnv());
        TextField key = secret(s.keyFromEnv());
        TextField cooldown = number(s.cooldownSeconds(), 3600);
        TextField daily = number(s.dailyLimit(), 10_000);
        TextField concurrent = number(s.maxConcurrent(), 64);
        Button save = button("logos.sage.settings.save", () -> ClientPacketDistributor.sendToServer(new SageAdminPayload(true, offered.getValue(),
                url.getValue(), model.getValue(), s.keyFromEnv() ? "" : key.getValue(), false,
                parse(cooldown), parse(daily), parse(concurrent))));
        Button clear = button("logos.sage.settings.clear_key", () -> ClientPacketDistributor.sendToServer(new SageAdminPayload(true, offered.getValue(),
                url.getValue(), model.getValue(), "", true, parse(cooldown), parse(daily), parse(concurrent))));
        UIElement actions = row();
        actions.addChildren(save, clear);
        ScrollerView page = page();
        page.addScrollViewChildren(
                text(Component.translatable("logos.sage.settings.server.about"), FADED),
                offered,
                text(Component.translatable("logos.sage.settings.url"), INK), url, envNote(s.urlFromEnv(), "LOGOS_AI_SERVER_URL"),
                text(Component.translatable("logos.sage.settings.model"), INK), model, envNote(s.modelFromEnv(), "LOGOS_AI_SERVER_MODEL"),
                text(keyLabel(s.keyHint()), INK), key, envNote(s.keyFromEnv(), "LOGOS_AI_SERVER_KEY"),
                text(Component.translatable("logos.sage.settings.cooldown"), INK), cooldown,
                text(Component.translatable("logos.sage.settings.daily"), INK), daily,
                text(Component.translatable("logos.sage.settings.concurrent"), INK), concurrent,
                actions);
        settingsView.addChildren(tabs(), page);
    }

    private static Component keyLabel(String hint) {
        return hint.isEmpty() ? Component.translatable("logos.sage.settings.key")
                : Component.translatable("logos.sage.settings.key.kept", hint);
    }

    private static UIElement envNote(boolean fromEnv, String variable) {
        Label l = text(Component.translatable("logos.sage.settings.env", variable), FADED);
        l.setDisplay(fromEnv ? TaffyDisplay.FLEX : TaffyDisplay.NONE);
        return l;
    }

    private static int parse(TextField f) {
        try {
            return Integer.parseInt(f.getValue().strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private ScrollerView page() {
        ScrollerView page = new ScrollerView();
        page.layout(l -> l.widthPercent(100).flex(1).minHeight(0));
        page.style(s -> s.backgroundTexture(IGuiTexture.EMPTY));
        page.viewPort(v -> v.style(s -> s.backgroundTexture(IGuiTexture.EMPTY)));
        return page;
    }

    private static TextField field(String value, boolean locked) {
        TextField f = new TextField();
        f.setAnyString();
        f.setValue(value, false);
        f.layout(l -> l.widthPercent(100).height(14));
        ManuscriptControls.blank(f);
        if (locked) f.setActive(false);
        return f;
    }

    /** A key field: what is typed shows as dots, and it starts empty (empty keeps the stored key). */
    private static TextField secret(boolean locked) {
        TextField f = field("", locked);
        f.setFormatter(s -> Component.literal("•".repeat(s.length())));
        return f;
    }

    private static TextField number(int value, int max) {
        TextField f = field(String.valueOf(value), false);
        f.setNumbersOnlyInt(0, max);
        return f;
    }

    // ---- little builders ----

    private static Label text(Component c, int color) {
        Label l = new Label();
        l.setText(c);
        l.textStyle(s -> s.textWrap(TextWrap.WRAP).adaptiveHeight(true).textColor(color).textShadow(false));
        l.layout(x -> x.widthPercent(100));
        return l;
    }

    private static Button button(String key, Runnable onClick) {
        Button b = new Button().setText(Component.translatable(key));
        b.setOnClick(e -> onClick.run());
        b.layout(l -> l.flexShrink(0).paddingHorizontal(3));
        ManuscriptControls.paper(b);
        return b;
    }

    private static UIElement row() {
        return new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(3).widthPercent(100));
    }

    private static UIElement column() {
        return new UIElement().layout(l -> l.widthPercent(100));
    }
}
