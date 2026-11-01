package dev.logos.client;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;

import dev.logos.client.voice.SpellVoices;
import dev.logos.client.voice.Utterance;
import dev.logos.client.voice.VoiceClient;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 言出法随's page beside the canvas: the player records themselves saying the spell's name three
 * times, says it once more as a test, and applies it; the page written next then casts when that
 * name is said aloud (with a 言灵坠 on the wand). Each take stops by itself when the player stops
 * speaking. Takes are only kept in memory until applied, so a half-finished set never replaces a
 * good one; each is checked as it comes (against the others, and against the player's other
 * recorded names) so a bad take or a name too like another is caught before it matters.
 */
final class VoicePanel extends UIElement {
    /** What the page needs from the editor around it. */
    interface Host {
        /** The spell's name as written, or null while the drawing does not hold. */
        @Nullable String spellName();

        /** The name 言出法随 is applied under for the page being written, or null for none. */
        @Nullable String applied();

        /** Applies 言出法随 under {@code name} (null turns it off) for the next writing of the page. */
        void apply(@Nullable String name);
    }

    static final int TAKES = 3;
    private static final int INK = 0xFF392719, FADED = 0xFF7A6248, SANGUINE = 0xFF8E2F1C, VERDIGRIS = 0xFF2F6B4F;
    /** Ticks a take waits for the player to start speaking before it gives up. */
    private static final int WAIT = 160;

    private enum Mode { IDLE, TAKE, TEST }

    private record Take(float[] audio, Utterance heard) {}

    private final Host host;
    private final Label name = text(Component.empty(), INK);
    private final Label state = text(Component.empty(), FADED);
    private final Label steps = text(Component.empty(), INK);
    private final Label meter = text(Component.empty(), FADED);
    private final Label notes = text(Component.empty(), INK);
    private final Button record = button("", this::onRecord);
    private final Button undo = button("logos.voice.undo", this::undoTake);
    private final Button apply = button("logos.voice.apply", this::applyTakes);
    private final Button restart = button("logos.voice.restart", this::restart);
    private final Button turnOff = button("logos.voice.off", this::turnOff);
    private final List<Take> takes = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    /** The name the takes are of; a different name on the canvas starts the set again. */
    private @Nullable String takesOf;
    /** Recordings already saved for the name on the canvas (-1 until counted). */
    private int saved = -1;
    private Mode mode = Mode.IDLE;
    private boolean testPassed, busy;
    private int waited;

    VoicePanel(Host host) {
        this.host = host;
        setId("voice_panel");
        addClass(ManuscriptControls.INKED);
        layout(l -> l.flex(1).minWidth(0).heightPercent(100).gapAll(5).paddingAll(6));

        Label title = text(Component.translatable("logos.voice.title"), INK);
        Label about = text(Component.translatable("logos.voice.about"), FADED);
        name.setId("voice_name");
        state.setId("voice_state");
        steps.setId("voice_steps");
        meter.setId("voice_meter");
        notes.setId("voice_notes");
        record.setId("voice_record");
        undo.setId("voice_undo");
        apply.setId("voice_apply");
        restart.setId("voice_restart");
        turnOff.setId("voice_off");
        record.layout(l -> l.widthPercent(100).height(20));
        UIElement row = row();
        row.addChildren(undo, restart, turnOff);
        apply.layout(l -> l.widthPercent(100).height(18));
        addChildren(title, name, state, about, steps, record, meter, row, apply, notes);
        addEventListener(UIEvents.TICK, e -> tick());
        refresh();
    }

    /** Called when the page is opened, and as the drawing changes: the name may be new. */
    void opened() {
        refresh();
    }

    private void tick() {
        String current = host.spellName();
        if (!java.util.Objects.equals(current, takesOf) && (mode == Mode.IDLE || current == null)) {
            stop();
            takes.clear();
            log.clear();
            testPassed = false;
            takesOf = current;
            saved = -1;
            if (current != null) VoiceClient.withVoices(v -> v.count(current)).thenAcceptAsync(n -> {
                if (current.equals(takesOf)) {
                    saved = n;
                    refresh();
                }
            }, Minecraft.getInstance());
            refresh();
        }
        if (mode != Mode.IDLE) {
            boolean speaking = VoiceClient.speaking();
            int bars = Math.min(12, Math.round(VoiceClient.level() * 60));
            meter.setText(Component.literal((speaking ? "● " : "○ ") + "▮".repeat(bars) + "▯".repeat(12 - bars) + "  ")
                    .append(Component.translatable(speaking ? "logos.voice.hearing" : "logos.voice.listening", takesOf)));
            if (!speaking && ++waited > WAIT) {
                stop();
                say(Component.translatable("logos.voice.silent"), SANGUINE);
            }
        }
    }

    // ---- recording ----

    private void onRecord() {
        if (mode != Mode.IDLE) {
            stop();
            refresh();
            return;
        }
        if (takesOf == null || busy) return;
        mode = takes.size() < TAKES ? Mode.TAKE : Mode.TEST;
        waited = 0;
        if (!VoiceClient.listen(this::heard, () -> mode != Mode.IDLE && isDisplayed() && Minecraft.getInstance().screen != null)) {
            mode = Mode.IDLE;
            say(Component.translatable("logos.voice.no_microphone"), SANGUINE);
        }
        refresh();
    }

    private void stop() {
        if (mode != Mode.IDLE) VoiceClient.stopListening();
        mode = Mode.IDLE;
        meter.setText(Component.empty());
    }

    /** One utterance arrived: a take is checked against the others and the other names; a test is matched. */
    private void heard(float[] audio) {
        Mode was = mode;
        String of = takesOf;
        stop();
        if (of == null) return;
        busy = true;
        refresh();
        List<Take> before = List.copyOf(takes);
        VoiceClient.withVoices(v -> was == Mode.TEST ? test(v, of, audio, before) : take(v, of, audio, before))
                .whenCompleteAsync((r, ex) -> {
                    busy = false;
                    if (!of.equals(takesOf)) return;
                    if (ex != null) say(Component.translatable("logos.voice.failed"), SANGUINE);
                    else r.run();
                    refresh();
                }, Minecraft.getInstance());
    }

    /** On the voice worker: what a recorded take came to, as the change it makes to the page. */
    private Runnable take(SpellVoices v, String of, float[] audio, List<Take> before) {
        Utterance u = v.hear(audio);
        if (u == null) return () -> say(Component.translatable("logos.voice.too_quiet"), SANGUINE);
        double spread = 0;
        for (Take t : before) spread = Math.max(spread, u.distance(t.heard));
        SpellVoices.Ranked other = v.closestOther(of, u);
        double far = spread;
        return () -> {
            takes.add(new Take(audio, u));
            testPassed = false;
            int n = takes.size();
            if (n > 1 && far > SpellVoices.ACCEPT) say(Component.translatable("logos.voice.take_unlike", n, fmt(far)), SANGUINE);
            else say(Component.translatable("logos.voice.take_ok", n, String.format("%.1f", u.seconds())), VERDIGRIS);
            if (other != null && other.distance() < SpellVoices.ACCEPT)
                say(Component.translatable("logos.voice.confusable", other.name(), fmt(other.distance())), SANGUINE);
        };
    }

    /** On the voice worker: whether a test is heard as this name, closer than any other recorded name. */
    private Runnable test(SpellVoices v, String of, float[] audio, List<Take> set) {
        Utterance u = v.hear(audio);
        if (u == null) return () -> say(Component.translatable("logos.voice.too_quiet"), SANGUINE);
        double own = Double.POSITIVE_INFINITY;
        for (Take t : set) own = Math.min(own, u.distance(t.heard));
        if (set.isEmpty()) {
            SpellVoices.Recognition r = v.recognize(audio, List.of(of));
            if (r != null) own = r.distance();
        }
        SpellVoices.Ranked other = v.closestOther(of, u);
        double mine = own;
        boolean ok = SpellVoices.accepts(mine, other == null ? Double.POSITIVE_INFINITY : other.distance());
        return () -> {
            testPassed = ok;
            if (ok) say(Component.translatable("logos.voice.test_ok", fmt(mine)), VERDIGRIS);
            else if (other != null && other.distance() <= mine) say(Component.translatable("logos.voice.test_other", other.name(), fmt(other.distance()), fmt(mine)), SANGUINE);
            else say(Component.translatable("logos.voice.test_far", fmt(mine)), SANGUINE);
        };
    }

    /** Writes the page without 言出法随; the recordings stay, to apply again later. */
    private void turnOff() {
        host.apply(null);
        refresh();
    }

    private void undoTake() {
        if (mode != Mode.IDLE || busy || takes.isEmpty()) return;
        takes.removeLast();
        testPassed = false;
        say(Component.translatable("logos.voice.undone", takes.size() + 1), FADED);
        refresh();
    }

    private void restart() {
        stop();
        takes.clear();
        log.clear();
        testPassed = false;
        refresh();
    }

    /** Saves the takes as this name's recordings (replacing any before) and turns 言出法随 on for the page. */
    private void applyTakes() {
        String of = takesOf;
        if (of == null || busy || !testPassed) return;
        if (takes.isEmpty()) {
            host.apply(of);
            say(Component.translatable("logos.voice.applied"), VERDIGRIS);
            refresh();
            return;
        }
        List<float[]> audio = takes.stream().map(Take::audio).toList();
        busy = true;
        refresh();
        VoiceClient.withVoices(v -> {
            try {
                v.replace(of, audio);
                return v.count(of);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).whenCompleteAsync((n, ex) -> {
            busy = false;
            if (ex != null) {
                say(Component.translatable("logos.voice.failed"), SANGUINE);
            } else {
                saved = n;
                takes.clear();
                host.apply(of);
                say(Component.translatable("logos.voice.applied"), VERDIGRIS);
            }
            refresh();
        }, Minecraft.getInstance());
    }

    // ---- drawing ----

    private void refresh() {
        String of = takesOf;
        boolean on = of != null && of.equals(host.applied());
        name.setText(of == null ? Component.translatable("logos.voice.no_spell") : Component.literal("《" + of + "》"));
        if (of == null) state.setText(Component.empty());
        else if (on) state.setText(Component.translatable("logos.voice.state.on", of));
        else if (host.applied() != null) state.setText(Component.translatable("logos.voice.state.renamed", host.applied()));
        else if (saved > 0) state.setText(Component.translatable("logos.voice.state.saved", saved));
        else state.setText(Component.translatable("logos.voice.state.off"));

        // ① ② ③ for the takes, then the test: filled once done, the one in hand in red chalk.
        StringBuilder sb = new StringBuilder();
        boolean useSaved = takes.isEmpty() && saved >= TAKES;
        for (int i = 0; i < TAKES; i++) sb.append(i < takes.size() || useSaved ? "● " : i == takes.size() && mode == Mode.TAKE ? "◉ " : "○ ");
        sb.append(testPassed ? "✔" : mode == Mode.TEST ? "◉" : "○");
        steps.setText(Component.translatable("logos.voice.steps", sb.toString()));

        boolean recording = mode != Mode.IDLE;
        boolean testing = takes.size() >= TAKES || useSaved;
        record.setText(recording ? Component.translatable("logos.voice.stop")
                : testing ? Component.translatable(testPassed ? "logos.voice.test_again" : "logos.voice.test")
                : Component.translatable("logos.voice.record", takes.size() + 1));
        record.setActive(of != null && !busy);
        undo.setActive(!recording && !busy && !takes.isEmpty());
        restart.setActive(!recording && !busy && (!takes.isEmpty() || saved > 0));
        restart.setDisplay(useSaved || !takes.isEmpty() ? TaffyDisplay.FLEX : TaffyDisplay.NONE);
        turnOff.setDisplay(on ? TaffyDisplay.FLEX : TaffyDisplay.NONE);
        apply.setActive(of != null && testPassed && !busy && !recording && !on);
        apply.setText(Component.translatable(on ? "logos.voice.applied_short" : "logos.voice.apply"));
        notes.setText(Component.literal(String.join("\n", log)));
    }

    private void say(Component line, int color) {
        log.add(line.getString());
        if (log.size() > 6) log.removeFirst();
        notes.textStyle(s -> s.textColor(color));
    }

    private static String fmt(double d) {
        return String.format("%.2f", d);
    }

    private static Label text(Component c, int color) {
        Label l = new Label();
        l.setText(c);
        l.textStyle(s -> s.textWrap(TextWrap.WRAP).adaptiveHeight(true).textColor(color).textShadow(false));
        l.layout(x -> x.widthPercent(100));
        return l;
    }

    private static Button button(String key, Runnable onClick) {
        Button b = new Button().setText(key.isEmpty() ? Component.empty() : Component.translatable(key));
        b.setOnClick(e -> onClick.run());
        b.layout(l -> l.flexShrink(0).paddingHorizontal(3));
        ManuscriptControls.paper(b);
        return b;
    }

    private static UIElement row() {
        return new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(3).widthPercent(100));
    }
}
