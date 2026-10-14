package dev.logos.client.uitest;

import java.util.Map;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.client.LogosFx;
import dev.logos.LogosMod;
import dev.logos.lang.Element;
import dev.logos.net.FxPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** A longer shot separates the two-frame launch, propagating pressure front, sustained contact and an empty-air endpoint. */
@LDLRegisterClient(name = "logos_physical_beam_preview", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public class PhysicalBeamPreviewScenario implements UIScenario {
    private static boolean holding;
    private boolean previousHideGui;
    private double born;
    @Override public void configure(ScenarioOptions o) { o.tags("logos", "vfx", "physical", "beam").guiScale(2); }

    private static void show() {
        holding = true;
        packet();
    }

    /** Keep server-like per-tick updates running even during screenshot and assertion waits. */
    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) { if (holding) packet(); }

    private static void packet() {
        LogosFx.handle(new FxPayload((byte)FxPayload.Kind.BEAM_HOLD.ordinal(), (byte)Element.PHYSICAL.ordinal(),
                (byte)0, -9, 102, 7, 11, 102, 7, .5f, 28040, -1, 0, -1));
    }

    private static void stop() {
        holding = false;
        LogosFx.handle(new FxPayload((byte)FxPayload.Kind.BEAM_END.ordinal(), (byte)Element.PHYSICAL.ordinal(),
                (byte)0, 11, 102, 7, 0, 0, 0, .5f, 28040, -1, 0, -1));
    }

    private static Object field(String name) {
        try {
            var f = LogosFx.class.getDeclaredField("BEAMS"); f.setAccessible(true);
            Object beam = ((Map<?, ?>)f.get(null)).get(28040);
            var p = beam.getClass().getDeclaredField("physical"); p.setAccessible(true);
            Object visual = p.get(beam);
            var value = visual.getClass().getDeclaredField(name); value.setAccessible(true);
            return value.get(visual);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set midnight").runCommand("weather clear")
                .step("hide GUI", ctx -> {
                    previousHideGui = Minecraft.getInstance().options.hideGui;
                    Minecraft.getInstance().options.hideGui = true;
                })
                .clearArea(new BlockPos(0, 105, 7), 24)
                .fill(new BlockPos(-20, 100, -16), new BlockPos(20, 100, 26), Blocks.SMOOTH_STONE.defaultBlockState())
                .fill(new BlockPos(11, 101, 5), new BlockPos(11, 105, 9), Blocks.STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -10, 0, 0).ticks(5)
                .step("warm up the shot", ctx -> show()).ticks(5)
                .step("end warmup", ctx -> stop()).ticks(32)
                .step("launch cross flash", ctx -> show()).screenshot("physical_beam_launch")
                .ticks(1).step("renew the pressure front", ctx -> show()).ticks(2)
                .screenshot("physical_beam_pressure_front")
                .step("remember the first launch", ctx -> born = (double)field("started"))
                .step("sustain the first contact", ctx -> show()).ticks(6)
                .screenshot("physical_beam_impact")
                .check("contact is present at a blocked endpoint", ctx -> field("lastImpact") != null);
        for (int i = 0; i < 10; i++) s.step("hold physical beam " + i, ctx -> show()).ticks(6);
        s.check("hold packets keep the original launch clock", ctx -> born == (double)field("started"))
                .step("sustain the dust wake", ctx -> show()).ticks(4).screenshot("physical_beam_sustained")
                .fill(new BlockPos(11, 101, 5), new BlockPos(11, 105, 9), Blocks.AIR.defaultBlockState())
                .step("continue into empty air", ctx -> show()).ticks(6)
                .step("renew the empty-air shot", ctx -> show()).ticks(6)
                .check("maximum range is not mistaken for an impact", ctx -> field("lastImpact") == null)
                .screenshot("physical_beam_no_false_impact")
                .step("release the beam", ctx -> stop()).ticks(32)
                .screenshot("physical_beam_after_release");
        s.teardown("restore GUI and stop the shot", ctx -> {
            holding = false;
            Minecraft.getInstance().options.hideGui = previousHideGui;
        });
    }
}
