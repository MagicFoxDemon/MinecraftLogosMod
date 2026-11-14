package dev.logos.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.lang.LogosError;
import dev.logos.lang.Spell;
import dev.logos.lang.Unlocks;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * What a player has learned of the language (符文解锁): the keys of {@link Unlocks} beyond those known from the start.
 * Only writing is held to it, never casting: a page found or given casts and copies whatever it says. A player with
 * creative materials writes anything, and so does everyone while {@code runeUnlocks} is off. Kept through death, and
 * sent to its own player only, for the editor to draw what they have not learned garbled.
 */
public final class RuneKnowledge {
    public static final MapCodec<RuneKnowledge> CODEC = Codec.STRING.listOf().fieldOf("learned")
            .xmap(keys -> new RuneKnowledge(keys, true), k -> List.copyOf(k.learned));
    /** To its player: whether the server holds writing to what is learned ({@code runeUnlocks}), then what is. */
    public static final StreamCodec<RegistryFriendlyByteBuf, RuneKnowledge> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, k -> LogosConfig.runeUnlocks(),
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), k -> List.copyOf(k.learned),
            (enforced, keys) -> new RuneKnowledge(keys, enforced));

    private final Set<String> learned = new LinkedHashSet<>();
    /** On the client, as the server last said; the server reads its config instead. */
    private final boolean enforced;

    public RuneKnowledge() {
        this(List.of(), true);
    }

    private RuneKnowledge(List<String> keys, boolean enforced) {
        learned.addAll(keys);
        this.enforced = enforced;
    }

    public static RuneKnowledge of(Player p) {
        return p.getData(LogosMod.RUNE_KNOWLEDGE);
    }

    /** Whether {@code p} is held to what they have learned at all: not with creative materials, nor while runeUnlocks is off. */
    public static boolean bound(Player p) {
        return !p.hasInfiniteMaterials() && (p.level().isClientSide() ? of(p).enforced : LogosConfig.runeUnlocks());
    }

    /** Whether {@code p} may write the word {@code key}. A word that is not locked is always known. */
    public static boolean knows(Player p, String key) {
        return !bound(p) || !Unlocks.lockable(key) || Unlocks.STARTING.contains(key) || of(p).learned.contains(key);
    }

    /** Whether {@code p} learned {@code key} themselves, whatever their game mode: what a book has left to teach them. */
    public static boolean learned(Player p, String key) {
        return Unlocks.STARTING.contains(key) || of(p).learned.contains(key);
    }

    /** The lockable keys {@code p} has not learned, in {@link Unlocks#all} order. */
    public static List<String> unlearned(Player p) {
        List<String> out = new ArrayList<>();
        for (String key : Unlocks.all()) if (!learned(p, key)) out.add(key);
        return out;
    }

    /**
     * Sends {@code p} what they have learned and whether it binds them: on login and respawn, as nothing may have been
     * learned yet to send, and when the config changes, as the switch rides along.
     */
    public static void sync(ServerPlayer p) {
        of(p);
        p.syncData(LogosMod.RUNE_KNOWLEDGE);
    }

    /** After the config file changed: every player online hears whether runeUnlocks still binds them. */
    public static void resyncAll() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) server.execute(() -> server.getPlayerList().getPlayers().forEach(RuneKnowledge::sync));
    }

    /** Teaches {@code p} the word {@code key}; false if it is not a word to learn or they knew it already. */
    public static boolean learn(ServerPlayer p, String key) {
        if (!Unlocks.lockable(key) || learned(p, key)) return false;
        of(p).learned.add(key);
        p.syncData(LogosMod.RUNE_KNOWLEDGE);
        return true;
    }

    /** Takes {@code key} from what {@code p} has learned; the words known from the start cannot be forgotten. */
    public static boolean forget(ServerPlayer p, String key) {
        if (!of(p).learned.remove(key)) return false;
        p.syncData(LogosMod.RUNE_KNOWLEDGE);
        return true;
    }

    /**
     * Whether {@code p} may write {@code spell} over a page that said {@code before} (null or unreadable: a blank page).
     * If not, tells them how many words in it they have not learned, without naming them.
     */
    public static boolean mayWrite(ServerPlayer p, Spell spell, @Nullable String before) {
        if (!bound(p)) return true;
        List<String> beyond = Unlocks.beyond(spell, compiled(before), key -> knows(p, key));
        if (beyond.isEmpty()) return true;
        p.sendOverlayMessage(refusal(beyond.size()));
        return false;
    }

    /** What a refused page says: shared by the server and the editor, which refuses before sending. */
    public static Component refusal(int words) {
        return Component.translatable("logos.unlock.refused", words).withStyle(ChatFormatting.RED);
    }

    private static @Nullable Spell compiled(@Nullable String source) {
        if (source == null || source.isBlank()) return null;
        try {
            return Casting.compile(source);
        } catch (LogosError e) {
            return null;
        }
    }
}
