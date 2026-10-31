package dev.logos.client.uitest;

import static dev.logos.client.uitest.FormScenes.AT;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.PillarBursts;
import dev.logos.runtime.PillarBurst;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * The golden pillar burst (FORM_VFX.md) from afar, on mixed ground (stone, grass, dirt, coarse dirt, cobblestone):
 * one shot each as it rises, peaks, holds, narrows, is a line and is gone. Each stage replays a fresh pillar before
 * sampling, so the time a screenshot takes cannot skip a short stage.
 */
@LDLRegisterClient(name = "logos_pillar_burst", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class PillarBurstScenario implements UIScenario {
    private boolean previousHideGui;

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("logos", "vfx", "form", "pillar").defaultTimeoutMs(60000).scenarioTimeoutMs(240000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("difficulty peaceful")
                .step("viewport", ctx -> previousHideGui = FormScenes.viewport())
                .server("mixed ground", ctx -> FormScenes.arena(ctx.server().overworld()))
                .server("look up from afar", ctx -> FormScenes.teleport(ctx.player(), new Vec3(.5, 100, 34.5), 180, -30))
                .ticks(20);
        stage(s, "pillar_rise", 3);
        stage(s, "pillar_peak", 9);
        stage(s, "pillar_hold", 28);
        stage(s, "pillar_fade", 56);
        stage(s, "pillar_line", 65);
        stage(s, "pillar_gone", 80);
        s.teardown("restore viewport", ctx -> restore(previousHideGui));
    }

    /** Clears what is playing, replays one, and takes a screenshot {@code ticks} later. */
    static void stage(ScenarioBuilder s, String name, int ticks) {
        s.step("replay " + name, ctx -> { PillarBursts.clear(); PillarBursts.spawn(AT, 1); })
                .ticks(ticks).screenshot(name);
    }

    static void restore(boolean hideGui) {
        PillarBursts.clear();
        FormScenes.restore(hideGui);
    }

    /** Close up, by night, and once through the server's packet: the debris, dust and shockwaves in detail. */
    @LDLRegisterClient(name = "logos_pillar_burst_close", group = "logos", registry = UIScenario.REGISTRY,
            environment = RegistrationEnvironment.DEV_ONLY)
    public static class Close implements UIScenario {
        private boolean previousHideGui;

        @Override
        public void configure(ScenarioOptions options) {
            options.tags("logos", "vfx", "form", "pillar").defaultTimeoutMs(60000).scenarioTimeoutMs(240000);
        }

        @Override
        public void define(ScenarioBuilder s) {
            s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("difficulty peaceful")
                    .step("viewport", ctx -> previousHideGui = FormScenes.viewport())
                    .server("mixed ground", ctx -> FormScenes.arena(ctx.server().overworld()))
                    .server("level view up close", ctx -> FormScenes.teleport(ctx.player(), new Vec3(.5, 100, 13.5), 180, 8))
                    .ticks(20);
            stage(s, "pillar_close_burst", 6);
            stage(s, "pillar_close_debris", 16);
            stage(s, "pillar_close_settle", 40);
            s.step("clear", ctx -> PillarBursts.clear())
                    .server("through the server", ctx -> PillarBurst.play(ctx.server().overworld(), AT, 1))
                    .waitUntil("client got the pillar", ctx -> PillarBursts.count() > 0).ticks(12)
                    .screenshot("pillar_close_network")
                    .runCommand("time set night")
                    .server("from afar by night", ctx -> FormScenes.teleport(ctx.player(), new Vec3(.5, 100, 34.5), 180, -30))
                    .ticks(10);
            stage(s, "pillar_night", 14);
            s.server("inside the pillar", ctx -> FormScenes.teleport(ctx.player(), new Vec3(1.2, 100, 1.2), 180, -10))
                    .ticks(5);
            stage(s, "pillar_inside", 14);
            s.teardown("restore viewport", ctx -> restore(previousHideGui));
        }
    }
}
