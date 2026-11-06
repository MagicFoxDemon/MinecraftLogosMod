package dev.logos.net;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.WeakHashMap;

import com.google.common.io.ByteStreams;
import com.google.common.io.CountingOutputStream;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * What the server holds client edits to. A spell's drawing is kept on the page and travels with it
 * (in wands, chests and chunk data), so it has to stay a sane size; and one player's edits may only
 * come so fast, since each is compiled and saved on the server thread.
 */
public final class EditLimits {
    /**
     * A drawing's size as written to the network or disk. A large one averages about 700 bytes a node,
     * so this holds about 460 nodes, against 47 in the largest example spell. It stays under the packet
     * codec's 2 MiB quota, which counts a real drawing about six times over and drops the connection
     * rather than refuse: what fits here always arrives.
     */
    public static final int MAX_GRAPH_BYTES = 320 * 1024;
    /** A drawing's node count: about what fits in {@link #MAX_GRAPH_BYTES}, against empty nodes that are cheap to send but not to load. */
    public static final int MAX_GRAPH_NODES = 512;

    /** Rewriting a page or emitter by hand: a few saves in a row, then one a second. */
    static final Throttle SPELL_EDITS = new Throttle(5, 20);
    /** Laying and wiring alchemy cards: each is a single click or drop, faster than anyone works by hand. */
    static final Throttle CARD_EDITS = new Throttle(30, 2);

    private EditLimits() {}

    /** Whether a drawing may be kept on a page. */
    public static boolean fits(CompoundTag graph) {
        return nodes(graph) <= MAX_GRAPH_NODES && bytes(graph) <= MAX_GRAPH_BYTES;
    }

    /** What to tell whoever drew {@code graph}, when it does not {@link #fits fit}. */
    public static Component tooLarge(CompoundTag graph) {
        return Component.translatable("logos.editor.graph_too_large", nodes(graph), MAX_GRAPH_NODES, bytes(graph) / 1024, MAX_GRAPH_BYTES / 1024)
                .withStyle(ChatFormatting.YELLOW);
    }

    /** Whether an edit carrying {@code graph} goes ahead; if not, {@code player} is told why. */
    static boolean admit(ServerPlayer player, CompoundTag graph) {
        if (!SPELL_EDITS.allow(player)) {
            player.sendOverlayMessage(Component.translatable("logos.editor.saving_too_fast").withStyle(ChatFormatting.YELLOW));
            return false;
        }
        if (fits(graph)) return true;
        player.sendOverlayMessage(tooLarge(graph));
        return false;
    }

    private static int nodes(CompoundTag graph) {
        return graph.getCompound("_additional").flatMap(a -> a.getList("nodes")).map(ListTag::size).orElse(0);
    }

    private static int bytes(CompoundTag graph) {
        CountingOutputStream counted = new CountingOutputStream(ByteStreams.nullOutputStream());
        try {
            NbtIo.write(graph, new DataOutputStream(counted));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return (int) counted.getCount();
    }

    /**
     * Lets a player send {@code burst} edits at once and one more every {@code ticksEach} ticks after
     * that. Payloads are handled on the server thread; a player's entry goes when the player does.
     */
    static final class Throttle {
        private final int burst, ticksEach;
        /** The tick from which each player's allowance is full again. */
        private final Map<ServerPlayer, Long> fullAt = new WeakHashMap<>();

        Throttle(int burst, int ticksEach) {
            this.burst = burst;
            this.ticksEach = ticksEach;
        }

        boolean allow(ServerPlayer player) {
            long now = player.level().getServer().getTickCount();
            long full = Math.max(fullAt.getOrDefault(player, now), now);
            if (full - now >= (long) burst * ticksEach) return false;
            fullAt.put(player, full + ticksEach);
            return true;
        }
    }
}
