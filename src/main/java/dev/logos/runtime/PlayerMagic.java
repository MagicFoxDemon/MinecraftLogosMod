package dev.logos.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.logos.LogosMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** Persistent per-player magic state: mana pool and stability capacity. Passive spells live on wand pages. */
public final class PlayerMagic {
    public static final MapCodec<PlayerMagic> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.DOUBLE.fieldOf("mana").forGetter(m -> m.mana),
            Codec.DOUBLE.fieldOf("max_mana").forGetter(m -> m.maxMana),
            Codec.INT.fieldOf("stability").forGetter(m -> m.stability),
            Codec.BOOL.fieldOf("trace").forGetter(m -> m.trace)
    ).apply(i, PlayerMagic::new));

    public double mana;
    public double maxMana;
    public int stability;
    public boolean trace;
    /** Not persisted: passive spell name → game tick when it may fire again. */
    public final Map<String, Long> cooldowns = new HashMap<>();
    /** Not persisted: the mana HUD stays quiet until this tick so alerts remain readable. */
    public long hudQuietUntil;

    public PlayerMagic() {
        this(Limits.DEFAULT_MAX_MANA, Limits.DEFAULT_MAX_MANA, Limits.DEFAULT_STABILITY, true);
    }

    private PlayerMagic(double mana, double maxMana, int stability, boolean trace) {
        this.mana = mana;
        this.maxMana = maxMana;
        this.stability = stability;
        this.trace = trace;
    }

    public static PlayerMagic of(Player p) {
        return p.getData(LogosMod.PLAYER_MAGIC);
    }

    /** Shows an action-bar alert and holds the mana HUD back for two seconds. */
    public static void alert(ServerPlayer p, Component message) {
        of(p).hudQuietUntil = p.level().getGameTime() + 40;
        p.sendOverlayMessage(message);
    }
}
