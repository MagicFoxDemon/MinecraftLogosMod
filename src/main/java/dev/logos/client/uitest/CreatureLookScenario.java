package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.ServerContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Projectiles wearing creatures (and, for comparison, a block and an item) drawn as those models. */
@LDLRegisterClient(name = "logos_creature_look", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class CreatureLookScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "vfx").guiScale(2);
    }

    private static void cast(ServerContext sc, String src) {
        var p = sc.player();
        PlayerMagic m = PlayerMagic.of(p);
        m.maxMana = m.mana = 10000;
        m.trace = false;
        Casting.cast(p, Casting.compile(src), null, null);
    }

    /** A slow body wearing {@code look}, 6 ahead, 1 up and {@code x} to the right, drifting to the right. */
    private static String body(double x, String look) {
        return "(projectile :speed 0.1 :life 20 :size 0.6 :look " + look + " :from (offset 6 1 " + x + ") :dir (toward (offset 6 1 " + x + ") (offset 6 1 " + (x + 4) + ")))";
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 5, 6), 8)
                .fill(ORIGIN.offset(-10, 0, -6), ORIGIN.offset(10, 0, 18), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY() + 2, ORIGIN.getZ() + 0.5, 0, 0)
                .ticks(10)
                .server("send four bodies", sc -> {
                    cast(sc, body(-5, "zombie"));
                    cast(sc, body(-2, "creeper"));
                    cast(sc, body(1, "&stone"));
                    cast(sc, body(4, "&mace"));
                })
                .ticks(20)
                .screenshot("creature_look");
    }
}
