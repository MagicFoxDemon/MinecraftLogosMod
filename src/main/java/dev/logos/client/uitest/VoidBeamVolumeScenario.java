package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.client.LogosFx;
import dev.logos.lang.Element;
import dev.logos.net.FxPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Oblique, near-axis and vertical views expose flattened geometry and degenerate beam bases. */
@LDLRegisterClient(name = "logos_void_beam_volume", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class VoidBeamVolumeScenario implements UIScenario {
    private boolean previousHideGui;
    @Override public void configure(ScenarioOptions o) { o.tags("logos", "vfx", "void", "beam").guiScale(2); }

    private static void show(boolean vertical) {
        LogosFx.handle(new FxPayload((byte)FxPayload.Kind.BEAM_HOLD.ordinal(), (byte)Element.VOID.ordinal(),
                (byte)0, -3, 102.4f, 4, vertical ? -3 : 4, vertical ? 108 : 102.4f, 4, .5f, 27920, -1, 0, -1));
    }

    private static void stop() {
        LogosFx.handle(new FxPayload((byte)FxPayload.Kind.BEAM_END.ordinal(), (byte)Element.VOID.ordinal(),
                (byte)0, 4, 102.4f, 4, 0, 0, 0, .5f, 27920, -1, 0, -1));
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set midnight").runCommand("weather clear")
                .step("hide GUI during the angle sweep", ctx -> {
                    previousHideGui = Minecraft.getInstance().options.hideGui;
                    Minecraft.getInstance().options.hideGui = true;
                })
                .clearArea(new BlockPos(0, 105, 3), 16)
                .fill(new BlockPos(-14, 100, -12), new BlockPos(14, 100, 18), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -3, 0, 0).ticks(5)
                .step("side view of the thinner beam and endpoint coverage", ctx -> show(false)).ticks(6)
                .step("sustain orbiting dark patches", ctx -> show(false)).ticks(6).screenshot("void_beam_compact_night")
                .step("another patch animation phase", ctx -> show(false)).ticks(6).screenshot("void_beam_dark_patches")
                .step("stop side view", ctx -> stop()).ticks(24)
                .teleportPlayer(-6, 101, -2, -45, 0).ticks(5)
                .step("oblique void layers", ctx -> show(false)).ticks(6)
                .step("renew oblique void layers", ctx -> show(false)).ticks(6).screenshot("void_beam_oblique")
                .step("stop oblique void layers", ctx -> stop()).ticks(24)
                .teleportPlayer(-7, 101, 4.7, -80, 0).ticks(5)
                .step("near axis void layers", ctx -> show(false)).ticks(6)
                .step("renew near axis void layers", ctx -> show(false)).ticks(6).screenshot("void_beam_near_axis")
                .step("stop near axis void layers", ctx -> stop()).ticks(24)
                .teleportPlayer(.5, 102, -3, 26, -12).ticks(5)
                .step("vertical void layers", ctx -> show(true)).ticks(6)
                .step("renew vertical void layers", ctx -> show(true)).ticks(6).screenshot("void_beam_vertical")
                .step("stop vertical void layers", ctx -> stop()).ticks(24);
        s.teardown("restore GUI visibility", ctx -> Minecraft.getInstance().options.hideGui = previousHideGui);
    }
}
