package dev.logos.client.uitest;

import java.util.List;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.client.LogosFx;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** A real beam's end races out from the cast point to its full reach instead of appearing at once; wakes can be cleared for a fresh sample. */
@LDLRegisterClient(name = "logos_beam_reach", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class BeamReachScenario implements UIScenario {
    private static double early = -1;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "beam").guiScale(2); }

    private static void cast(net.minecraft.server.level.ServerPlayer player, String source) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 10000;
        if (Casting.cast(player, Casting.compile(source), null, null) == null)
            throw new IllegalStateException("real test spell was refused");
    }

    private static double longest() {
        List<Double> lengths = LogosFx.drawnBeamLengths();
        return lengths.stream().mapToDouble(Double::doubleValue).max().orElse(-1);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 104, 16), 20)
                .fill(new BlockPos(-14, 100, -8), new BlockPos(14, 100, 40), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -2, 0, 0).ticks(10);
        // Frozen ticks, stepped by hand: screenshot readback would otherwise let the beam age several ticks unseen.
        s.runCommand("tick freeze").ticks(2)
                .server("a long beam", sc -> cast(sc.player(), "(beam :radius 0.35 :range 32 :life 2 (on-hit (damage target 1 physical)))"))
                .runCommand("tick step 2").ticks(4)
                .step("sample its first ticks", ctx -> early = longest()).screenshot("beam_reach_early")
                .runCommand("tick step 14").ticks(16).screenshot("beam_reach_full")
                .check("the end starts short and races out to the full reach", ctx -> early >= 0 && early < 20 && longest() > 28)
                .runCommand("tick unfreeze").ticks(50).step("clear what is left", ctx -> LogosFx.clearRays())
                .check("clearRays leaves nothing showing", ctx -> LogosFx.liveRays() == 0);
    }
}
