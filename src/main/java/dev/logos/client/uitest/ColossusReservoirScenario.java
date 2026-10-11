package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.runtime.AlchemicalColossus;
import dev.logos.runtime.Colossi;
import dev.logos.runtime.Familiars;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Real-client check of the colossus's reservoir gauge: seen from behind, its three tubes full, then a
 * third full with the spent part blackened, then the same in a frenzy's scorched shell.
 * Run: {@code gradlew runClient -PldTest=logos_colossus_reservoir}.
 */
@LDLRegisterClient(name = "logos_colossus_reservoir", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ColossusReservoirScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    private AlchemicalColossus colossus;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "colossus").guiScale(2);
    }

    /** The client's copy of the colossus. */
    private AlchemicalColossus seen() {
        if (Minecraft.getInstance().level.getEntity(colossus.getId()) instanceof AlchemicalColossus c) return c;
        throw new AssertionError("no colossus on the client");
    }

    private void expectFill(float want) {
        float got = seen().fill();
        System.out.printf("[logos] reservoir shows %.2f%n", got);
        if (Math.abs(got - want) > 0.02f) throw new AssertionError("the reservoir should show " + want + ", shows " + got);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 0), 10)
                .fill(ORIGIN.offset(-8, 0, -8), ORIGIN.offset(8, 0, 8), Blocks.SMOOTH_STONE.defaultBlockState())
                // Behind it (it faces south), looking at its back.
                .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY() + 2.2, ORIGIN.getZ() - 2.6, 0, 8)
                .server("raise a colossus facing away", sc -> {
                    var p = sc.player();
                    Colossi.summon(p, p.level(), Vec3.atBottomCenterOf(ORIGIN.offset(0, 1, 1)), null);
                    for (var m : Familiars.of(p)) if (m instanceof AlchemicalColossus c) colossus = c;
                    colossus.setNoAi(true);
                    colossus.setYRot(0);
                    colossus.yBodyRot = 0;
                    colossus.yHeadRot = 0;
                })
                .ticks(70)
                .step("full", ctx -> expectFill(1f))
                .screenshot("reservoir_full")
                .server("a third left", sc -> colossus.setMana(AlchemicalColossus.DISPLAY_CAP / 3))
                .ticks(5)
                .step("a third", ctx -> expectFill(1f / 3))
                .screenshot("reservoir_third")
                .server("into a frenzy, with as much left", sc -> {
                    colossus.frenzy(null);
                    colossus.setMana(AlchemicalColossus.DISPLAY_CAP / 3);
                })
                .ticks(5)
                .step("a third, scorched", ctx -> {
                    if (!seen().isScorched()) throw new AssertionError("it should be in its scorched shell");
                    expectFill(1f / 3);
                })
                .screenshot("reservoir_third_scorched");
    }
}
