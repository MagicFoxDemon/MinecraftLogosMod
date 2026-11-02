package dev.logos.client.uitest;

import java.util.List;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.LiveVein;
import dev.logos.client.VeinVisuals;
import dev.logos.lang.Element;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * The vein's shared look, from real casts: a fire vein crawls from under its caster's feet and steps up onto a
 * platform, and clients keep one head for it on the ground, then drop it once it has run out; a frost ring spreads
 * every way at once as many heads; a fire sweep goes round an arc; a climbing frost vein goes up a wall.
 */
@LDLRegisterClient(name = "logos_vein", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class VeinScenario implements UIScenario {
    private static void cast(ServerPlayer player, String source) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 10000;
        if (Casting.cast(player, Casting.compile(source), null, null) == null)
            throw new IllegalStateException("real test spell was refused: " + source);
    }

    private static void expect(boolean ok, String what) {
        if (!ok) throw new AssertionError(what + "; kept " + VeinVisuals.all().size() + " heads");
    }

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("logos", "vfx", "vein").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon")
                .clearArea(new BlockPos(0, 106, 6), 14)
                .fill(new BlockPos(-10, 100, -10), new BlockPos(10, 100, 20), Blocks.GRASS_BLOCK.defaultBlockState())
                // A platform a block high from z 6 on.
                .fill(new BlockPos(-10, 101, 6), new BlockPos(10, 101, 20), Blocks.STONE.defaultBlockState())
                .teleportPlayer(.5, 101, .5, 0, 25)
                .step("start from nothing", ctx -> VeinVisuals.clear())
                .ticks(2);

        s.server("a fire vein", sc -> cast(sc.player(), "(vein :range 14 (on-hit (damage target 2 fire)))"))
                .ticks(14)
                .check("one head on the ground, up on the platform", ctx -> {
                    List<LiveVein> all = VeinVisuals.all();
                    expect(all.size() == 1, "one head");
                    LiveVein v = all.getFirst();
                    expect(v.element() == Element.FIRE, "fire, got " + v.element());
                    expect(v.pos().z > 6 && Math.abs(v.pos().y - 102) < 1e-3, "on the platform past z 6, at " + v.pos());
                    return true;
                })
                .screenshot("vein_fire")
                .ticks(20)
                .check("it ran out and was dropped", ctx -> VeinVisuals.all().isEmpty());

        s.teleportPlayer(.5, 101, .5, 0, 25)
                .ticks(2)
                .server("a frost ring", sc -> cast(sc.player(), "(vein :motion ring :range 6 :speed 0.25 (on-hit (freeze target 2)))"))
                .teleportPlayer(.5, 108, -8, 0, 40)
                .ticks(8)
                .check("it spreads as many heads", ctx -> {
                    List<LiveVein> all = VeinVisuals.all();
                    expect(all.size() >= 8, "a head every way");
                    expect(all.stream().allMatch(v -> v.element() == Element.FROST), "all frost");
                    return true;
                })
                .screenshot("vein_ring")
                .ticks(30)
                .check("the ring ran out", ctx -> VeinVisuals.all().isEmpty());

        // A fire sweep half round its caster, from their left to their right.
        s.teleportPlayer(.5, 101, .5, 0, 25)
                .ticks(2)
                .server("a fire sweep", sc -> cast(sc.player(), "(vein :motion sweep :spread 180 :radius 4 :speed 0.4 (on-hit (damage target 2 fire)))"))
                .teleportPlayer(.5, 107, -6, 0, 28)
                .ticks(14)
                .check("one head on its arc", ctx -> {
                    List<LiveVein> all = VeinVisuals.all();
                    expect(all.size() == 1, "one head");
                    LiveVein v = all.getFirst();
                    double off = Math.hypot(v.pos().x - .5, v.pos().z - .5);
                    expect(Math.abs(off - 4) < .4, "4 from where it was cast, is " + off);
                    return true;
                })
                .screenshot("vein_sweep")
                .ticks(25);

        // A frost vein that climbs: up the step, over the platform, and up a wall three high at z 10.
        s.fill(new BlockPos(-10, 102, 10), new BlockPos(10, 104, 10), Blocks.STONE.defaultBlockState())
                .teleportPlayer(.5, 101, .5, 0, 25)
                .ticks(2)
                .server("a climbing frost vein", sc -> cast(sc.player(), "(vein :climb :range 16 (on-hit (freeze target 2)))"))
                .teleportPlayer(9, 106, 2, 50, 13)
                .waitUntil("it climbs the wall's face", ctx -> VeinVisuals.all().stream().anyMatch(v -> !v.ended()
                        && v.face() == Direction.NORTH && v.pos().y > 103 && Math.abs(v.pos().z - 10) < 1e-3))
                .screenshot("vein_climb")
                .waitUntil("it comes down the far side", ctx -> VeinVisuals.all().stream().anyMatch(v -> v.face() == Direction.SOUTH))
                .ticks(20)
                .check("it ran out", ctx -> VeinVisuals.all().isEmpty());
        s.teardown("clear", ctx -> VeinVisuals.clear());
    }
}
