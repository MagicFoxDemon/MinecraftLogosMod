package dev.logos.client;

import java.util.List;

import dev.logos.sage.SageAgent;
import net.minecraft.network.chat.Component;

/**
 * The question the sage is working on. It belongs to the player, not to an open editor: closing
 * the inscription table leaves it running, the next editor opens on its conversation again, and
 * if its answer lands while nobody is looking at that conversation, {@link SageToast} says so.
 */
final class SageWork {
    /** The sage page currently showing the conversations; it hears the progress and the answer. */
    interface View {
        void status(Component status);

        void answered(SageChats.Chat chat, SageAgent.Turn answer);
    }

    /** How recently the page must have shown the conversation for its answer to count as seen. */
    private static final long SEEN_MS = 300;

    private static SageChats.Chat chat;
    private static SageClient.Handle handle;
    private static Component status = Component.empty();
    private static View view;
    private static long seenAt;
    /** Bumped by every question and every cancel, so a late answer to a dropped question is ignored. */
    private static int generation;

    private SageWork() {}

    static boolean busy() {
        return chat != null;
    }

    /** The conversation the sage is answering in, or null. */
    static SageChats.Chat chat() {
        return chat;
    }

    static Component status() {
        return status;
    }

    static void watch(View v) {
        view = v;
    }

    /** The page is showing {@link #chat()} right now. */
    static void seen() {
        seenAt = System.currentTimeMillis();
    }

    static void ask(SageChats.Chat in, List<SageAgent.Turn> history, String message, String current, int capacity, boolean emitter) {
        if (busy()) return;
        int asked = ++generation;
        chat = in;
        status = Component.translatable("logos.sage.status.thinking");
        SageClient.Handle h = SageClient.ask(history, message, current, capacity, emitter,
                (key, arg) -> {
                    if (asked != generation) return;
                    status = Component.translatable("logos.sage.status." + key, arg);
                    if (view != null) view.status(status);
                },
                out -> {
                    if (asked != generation) return;
                    clear();
                    SageAgent.Turn answer = out.ok() ? new SageAgent.Turn(false, out.text(), out.source())
                            : new SageAgent.Turn(false, Component.translatable(out.error(), out.detail()).getString(), out.source());
                    SageChats.add(in, answer);
                    boolean looking = System.currentTimeMillis() - seenAt < SEEN_MS;
                    if (view != null) view.answered(in, answer);
                    if (!looking) SageToast.show(out.ok(), in.title);
                });
        // An unconfigured route answers before ask returns; then there is nothing left to cancel.
        if (asked == generation && busy()) handle = h;
    }

    static void cancel() {
        if (!busy()) return;
        SageClient.Handle h = handle;
        clear();
        if (h != null) h.cancel();
    }

    private static void clear() {
        generation++;
        chat = null;
        handle = null;
        status = Component.empty();
    }
}
