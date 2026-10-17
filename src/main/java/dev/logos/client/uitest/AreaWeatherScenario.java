package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.runtime.AreaWeather;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Area weather as a client sees it: rain columns at a zone's edge from outside, a darkened rainy sky inside,
 * and nothing far away. A clear zone under the world's own rain is {@link AreaWeatherClearScenario}.
 */
@LDLRegisterClient(name = "logos_area_weather", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class AreaWeatherScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "weather").guiScale(2); }

    private static void cast(net.minecraft.server.level.ServerPlayer player, String source, Vec3 here) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 100000;
        var inst = Casting.cast(player, Casting.compile(source), null, here);
        if (inst == null || inst.dead) throw new IllegalStateException("real test spell failed: " + (inst == null ? "refused" : inst.endReason()));
    }

    private static float sky() {
        return AreaWeather.skyRain(Minecraft.getInstance().level, 0);
    }

    private static float drawn() {
        return AreaWeather.renderRain(Minecraft.getInstance().level, 0);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 104, 30), 40)
                .fill(new BlockPos(-20, 100, -10), new BlockPos(20, 100, 70), Blocks.GRASS_BLOCK.defaultBlockState())
                .teleportPlayer(.5, 101, -7.5, 0, 10).ticks(10);
        s.server("a rain zone", sc -> cast(sc.player(), "(spell :name \"雨\" :mana 5000 (weather rain :radius 8 :life 120))", new Vec3(.5, 101, 8.5)))
                .ticks(30).screenshot("area_weather_outside")
                .check("the renderer runs near the zone", ctx -> drawn() == 1)
                .check("the sky stays clear outside it", ctx -> sky() < 0.1)
                .teleportPlayer(.5, 101, 8.5, 0, -10).ticks(30).screenshot("area_weather_inside")
                .check("inside, the sky darkens as in rain", ctx -> sky() > 0.9)
                .teleportPlayer(.5, 101, 60.5, 0, 10).ticks(30).screenshot("area_weather_far")
                .check("far away, the renderer rests", ctx -> drawn() == 0)
                .check("far away, the sky is clear", ctx -> sky() < 0.1);
    }
}
