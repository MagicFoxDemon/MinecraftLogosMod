package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.LogosMod;
import dev.logos.client.LogosFx;
import dev.logos.lang.Element;
import dev.logos.net.FxPayload;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * The frost beam through its whole life with frozen, hand-stepped ticks: reaching out, holding, the wake it
 * leaves and that wake gone again; from a real cast in first and third person, and from the side (the same
 * beam sent through the public packets), by day and by night. Every stage is a fresh beam after
 * {@code LogosFx.clearRays()}.
 */
@LDLRegisterClient(name = "logos_frost_beam_preview", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FrostBeamPreviewScenario implements UIScenario {
    private static final String BEAM = "(beam :radius 0.35 :range 24 :life 2 (on-hit (damage target 1 frost)))";
    private static final int SIDE = 27600;
    private static int stage;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "beam", "frost").guiScale(2); }

    private static void cast(ServerPlayer player) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 10000;
        if (Casting.cast(player, Casting.compile(BEAM), null, null) == null)
            throw new IllegalStateException("real test spell was refused");
    }

    /** A fresh real cast, stepped {@code ticks} ticks on, then a screenshot. */
    private static void real(ScenarioBuilder s, String name, int ticks) {
        // Let anything still running end, then sample a clean beam.
        pass(s.runCommand("tick step 60"), 62)
                .step("clear before " + name, ctx -> LogosFx.clearRays())
                .server("cast " + name, sc -> cast(sc.player()))
                .runCommand("tick step " + ticks);
        pass(s, ticks + 3).screenshot(name);
    }

    /** Waits {@code ticks} client ticks in pieces, each well inside a step's time limit. */
    private static ScenarioBuilder pass(ScenarioBuilder s, int ticks) {
        for (int left = ticks; left > 0; left -= 50) s.ticks(Math.min(50, left));
        return s;
    }


    private static void hold(float x0, float z0, float x1, float z1) {
        LogosFx.handle(new FxPayload((byte) FxPayload.Kind.BEAM_HOLD.ordinal(), (byte) Element.FROST.ordinal(),
                (byte) 0, x0, 102.2f, z0, x1, 102.2f, z1, .35f, SIDE + stage, -1, 0, -1));
    }

    private static void stop() {
        LogosFx.handle(new FxPayload((byte) FxPayload.Kind.BEAM_END.ordinal(), (byte) Element.FROST.ordinal(),
                (byte) 0, 0, 102.2f, 0, 0, 0, 0, .35f, SIDE + stage, -1, 0, -1));
    }

    /** The beam from the side: held for {@code holdTicks}, then (if {@code wakeTicks >= 0}) ended and its wake stepped on. */
    private static void side(ScenarioBuilder s, String name, int holdTicks, int wakeTicks) {
        s.runCommand("tick step 40").ticks(42)
                .step("clear before " + name, ctx -> { LogosFx.clearRays(); stage++; hold(-1, -6, -1, 13.6f); });
        for (int left = holdTicks; left > 0; left -= 6) {
            int n = Math.min(6, left);
            s.runCommand("tick step " + n).ticks(n + 2).step("renew " + name, ctx -> hold(-1, -6, -1, 13.6f));
        }
        if (wakeTicks >= 0) {
            s.step("end " + name, ctx -> stop());
            if (wakeTicks > 0) pass(s.runCommand("tick step " + wakeTicks), wakeTicks + 3);
        }
        s.ticks(2).screenshot(name);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 104, 10), 18)
                .fill(new BlockPos(-14, 100, -10), new BlockPos(14, 100, 30), Blocks.SMOOTH_STONE.defaultBlockState())
                .fill(new BlockPos(-4, 101, 14), new BlockPos(4, 106, 14), Blocks.PACKED_ICE.defaultBlockState())
                .teleportPlayer(.5, 101, -2, 0, 0)
                .server("hand over a wand", sc -> sc.player().setItemInHand(InteractionHand.MAIN_HAND,
                        new ItemStack(LogosMod.WOOD_WANDS.getFirst().get())))
                .ticks(8).runCommand("tick freeze").ticks(2).screenshot("fp_0_before");

        // First person, day: reach, hold, wake early / mid / late, gone.
        real(s, "fp_1_reach", 2);
        real(s, "fp_2_hold", 14);
        s.check("the frost beam is held", ctx -> !LogosFx.drawnBeamLengths().isEmpty());
        real(s, "fp_3_wake_early", 44);
        s.check("the frost beam leaves a wake", ctx -> LogosFx.liveRays() > 0);
        real(s, "fp_4_wake_mid", 90);
        real(s, "fp_5_wake_late", 140);
        s.runCommand("tick step 40").ticks(42).screenshot("fp_6_gone")
                .check("the wake is gone again", ctx -> LogosFx.liveRays() == 0);

        // Third person.
        s.step("third person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK)).ticks(2);
        real(s, "tp_1_hold", 14);
        real(s, "tp_2_wake", 50);
        s.step("first person again", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON));

        // From the side, the full length.
        s.teleportPlayer(-10.5, 101, 4, -90, 4).ticks(3);
        side(s, "side_1_reach", 2, -1);
        side(s, "side_2_hold", 20, -1);
        side(s, "side_3_wake_early", 20, 6);
        side(s, "side_4_wake_mid", 20, 50);
        side(s, "side_5_wake_late", 20, 100);
        side(s, "side_6_gone", 20, 135);
        s.check("the side wake is gone again", ctx -> LogosFx.liveRays() == 0);

        // Night.
        s.runCommand("time set midnight").ticks(2);
        side(s, "night_1_hold", 20, -1);
        side(s, "night_2_wake", 20, 30);
        s.teleportPlayer(.5, 101, -2, 0, 0).ticks(3);
        real(s, "night_3_fp_hold", 14);
        real(s, "night_4_fp_wake", 60);

        s.runCommand("tick unfreeze").runCommand("time set noon");
        s.teardown("first person, ticks running", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON));
    }
}
