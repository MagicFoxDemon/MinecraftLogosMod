package dev.logos.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.MapCodec;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * The dimensions a player has stood in. {@code in-dimension} only reaches those, so a far realm is
 * opened by travelling there once, not by knowing its name. Visits are recorded whatever the config
 * says, so turning {@code travelUnvisitedDimensions} off later still honours every earlier visit.
 */
public final class Attunement {
    public static final MapCodec<Attunement> CODEC = Level.RESOURCE_KEY_CODEC.listOf().fieldOf("visited")
            .xmap(Attunement::new, a -> new ArrayList<>(a.visited));

    private final Set<ResourceKey<Level>> visited = new LinkedHashSet<>();

    public Attunement() {}

    private Attunement(List<ResourceKey<Level>> list) {
        visited.addAll(list);
    }

    public static Attunement of(Player p) {
        return p.getData(LogosMod.ATTUNEMENT);
    }

    /** Notes the dimension the player stands in now. */
    public static void record(ServerPlayer p) {
        visit(p, p.level().dimension());
    }

    public static void visit(Player p, ResourceKey<Level> dimension) {
        Attunement a = of(p);
        if (a.visited.add(dimension)) p.setData(LogosMod.ATTUNEMENT, a);
    }

    public boolean visited(ResourceKey<Level> dimension) {
        return visited.contains(dimension);
    }

    /**
     * Whether a spell cast by {@code caster} may name {@code target}: always its own dimension; any
     * other only if the caster (or a familiar's master) has been there, unless the config allows all.
     */
    public static boolean reaches(LivingEntity caster, ServerLevel target) {
        if (LogosConfig.travelUnvisitedDimensions() || caster.level() == target) return true;
        Player p = attuned(caster);
        return p != null && of(p).visited(target.dimension());
    }

    private static @Nullable Player attuned(LivingEntity caster) {
        if (caster instanceof Player p) return p;
        UUID master = Familiars.masterOf(caster);
        return master == null ? null : caster.level().getServer().getPlayerList().getPlayer(master);
    }
}
