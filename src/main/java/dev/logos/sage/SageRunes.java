package dev.logos.sage;

import java.util.LinkedHashSet;
import java.util.Set;

import dev.logos.lang.Rune;
import dev.logos.lang.Runes;
import net.minecraft.world.entity.player.Player;

/**
 * Which runes the sage may write for a player. There is no rune learning yet, so every rune is
 * open; once there is, this is the one place to hook it in (and the client then needs the server's list).
 */
public final class SageRunes {
    private SageRunes() {}

    public static Set<String> available(Player player) {
        Set<String> all = new LinkedHashSet<>();
        for (Rune r : Runes.all()) all.add(r.name);
        return all;
    }
}
