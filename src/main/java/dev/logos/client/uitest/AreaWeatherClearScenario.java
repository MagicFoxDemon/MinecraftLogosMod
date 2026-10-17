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

/** A clear zone under the world's own rain: no rain falls in it and the sky over it stays bright. */
@LDLRegisterClient(name = "logos_area_weather_clear", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class AreaWeatherClearScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "weather").guiScale(2); }

    private static float worldRain() {
        return Minecraft.getInstance().level.getRainLevel(1);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 104, 0), 30)
                .fill(new BlockPos(-20, 100, -20), new BlockPos(20, 100, 20), Blocks.GRASS_BLOCK.defaultBlockState())
                .teleportPlayer(.5, 101, .5, 0, -10).ticks(10);
        s.server("a clear zone", sc -> {
                    PlayerMagic magic = PlayerMagic.of(sc.player());
                    magic.maxMana = magic.mana = 100000;
                    var inst = Casting.cast(sc.player(), Casting.compile("(spell :name \"晴\" :mana 5000 (weather clear :radius 16 :life 120))"), null, new Vec3(.5, 101, .5));
                    if (inst == null || inst.dead) throw new IllegalStateException("real test spell failed: " + (inst == null ? "refused" : inst.endReason()));
                })
                .runCommand("weather rain").ticks(80).screenshot("area_weather_clear_in_rain")
                .check("the world is raining", ctx -> worldRain() > 0.5)
                .check("the sky over the clear zone stays bright", ctx -> AreaWeather.skyRain(Minecraft.getInstance().level, worldRain()) < 0.3)
                .runCommand("weather clear");
    }
}
