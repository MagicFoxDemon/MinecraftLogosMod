package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.runtime.AlchemicalColossus;
import dev.logos.runtime.Colossi;
import dev.logos.runtime.Familiars;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Real-client check of riding the colossus: its master asks to be carried, is lifted from the
 * ground onto its left shoulder, and sits there (seen from behind, in third person); then takes the
 * controls and drives it forward, running at a pace its feet keep up with.
 * Run: {@code gradlew runClient -PldTest=logos_colossus_ride}.
 */
@LDLRegisterClient(name = "logos_colossus_ride", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ColossusRideScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    private AlchemicalColossus colossus;
    private Vec3 drivenFrom;
    private int stepsBefore;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "colossus").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 4), 10)
                .fill(ORIGIN.offset(-10, 0, -8), ORIGIN.offset(10, 0, 14), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 0.5, 0, 10)
                .server("raise a colossus beside the player", sc -> {
                    var p = sc.player();
                    Colossi.summon(p, p.level(), Vec3.atBottomCenterOf(ORIGIN.offset(0, 1, 3)), null);
                    for (var m : Familiars.of(p)) if (m instanceof AlchemicalColossus c) colossus = c;
                })
                .ticks(70)
                .server("ask to be carried", sc -> colossus.interact(sc.player(), InteractionHand.MAIN_HAND, colossus.position()))
                .ticks(50)
                .step("watch from behind", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                .ticks(5)
                .screenshot("being_lifted")
                .ticks(60)
                .screenshot("on_its_shoulder")
                .server("take the controls", sc -> Colossi.control(sc.player(), Colossi.TOGGLE_DRIVE))
                .step("hold forward", ctx -> Minecraft.getInstance().options.keyUp.setDown(true))
                .ticks(30)
                .step("mark where it is", ctx -> {
                    drivenFrom = Minecraft.getInstance().player.getVehicle().position();
                    stepsBefore = ((AlchemicalColossus) Minecraft.getInstance().player.getVehicle()).footfalls();
                })
                .ticks(20)
                .step("check its pace", ctx -> {
                    double perTick = Minecraft.getInstance().player.getVehicle().position().distanceTo(drivenFrom) / 20;
                    // Its feet travel 1.4 model units a tick in the run cycle; the cycle is played faster
                    // to match, within reason.
                    double rate = perTick / (1.395 / 16);
                    System.out.printf("[logos] driven colossus: %.3f blocks/tick, run cycle at %.2fx%n", perTick, rate);
                    if (rate < 1.0 || rate > 1.8) throw new AssertionError("driven pace " + perTick + " blocks/tick is off its run cycle");
                    // A run cycle (two steps) takes about 17 ticks at this pace: 20 ticks should bring two or three footfalls.
                    int steps = ((AlchemicalColossus) Minecraft.getInstance().player.getVehicle()).footfalls() - stepsBefore;
                    System.out.printf("[logos] driven colossus: %d footfalls in 20 ticks%n", steps);
                    if (steps < 2 || steps > 4) throw new AssertionError(steps + " footfalls in 20 ticks of running");
                })
                .screenshot("driving")
                .step("let go", ctx -> Minecraft.getInstance().options.keyUp.setDown(false))
                .step("first person again", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON));
    }
}
