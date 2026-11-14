package dev.logos.sage;

import java.util.LinkedHashSet;
import java.util.Set;

import dev.logos.lang.Unlocks;
import dev.logos.runtime.RuneKnowledge;
import net.minecraft.world.entity.player.Player;

/**
 * What the sage may write for a player: the {@link Unlocks} keys ({@code rune:freeze}, {@code element:fire}…) they may
 * write (符文解锁). The prompt lists only those, and a spell using anything else is sent back like a compile error. The
 * client gets the same answer, as its player's knowledge is synced to it.
 */
public final class SageRunes {
    private SageRunes() {}

    public static Set<String> available(Player player) {
        Set<String> keys = new LinkedHashSet<>();
        for (String key : Unlocks.all()) if (RuneKnowledge.knows(player, key)) keys.add(key);
        return keys;
    }
}
