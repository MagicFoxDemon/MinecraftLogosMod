package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** The sky over each kind of area weather: the four side by side from afar, then each from under it, looking up. */
@LDLRegisterClient(name = "logos_area_weather_sky", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class AreaWeatherSkyScenario implements UIScenario {
    private static final String[] KINDS = {"rain", "thunder", "snow", "clear"};

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "weather").guiScale(2); }

    private static double x(int i) {
        return -33.5 + 22 * i;
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 104, 30), 48)
                .fill(new BlockPos(-48, 100, -16), new BlockPos(48, 100, 64), Blocks.GRASS_BLOCK.defaultBlockState())
                .teleportPlayer(.5, 101, -14, 0, -22).ticks(10);
        s.server("four zones", sc -> {
            PlayerMagic magic = PlayerMagic.of(sc.player());
            magic.maxMana = magic.mana = 100000;
            for (int i = 0; i < KINDS.length; i++) {
                var inst = Casting.cast(sc.player(), Casting.compile("(spell :mana 5000 (weather " + KINDS[i] + " :radius 8 :life 60))"), null,
                        new Vec3(x(i), 101, 40.5));
                if (inst == null || inst.dead) throw new IllegalStateException("real test spell failed: " + (inst == null ? "refused" : inst.endReason()));
            }
        }).ticks(40).screenshot("sky_all");
        for (int i = 0; i < KINDS.length; i++) {
            s.teleportPlayer(x(i), 101, 40.5, 0, -88).ticks(15).screenshot("sky_" + KINDS[i] + "_below");
        }
    }
}
