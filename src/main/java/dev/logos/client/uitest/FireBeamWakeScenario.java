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
 * The fire beam's whole life, for preview: a real cast reaching out, held, ending and leaving its smoke
 * until it is gone, in first person (the wand tip must not cover the view) and third person; then the
 * same stages from the side over the full length, by day and by night. Every stage group starts from a
 * fresh beam after {@link LogosFx#clearRays()}.
 */
@LDLRegisterClient(name = "logos_fire_beam_wake", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FireBeamWakeScenario implements UIScenario {
    private static final String BEAM = "(beam :radius 0.35 :range 24 :life 2 (on-hit (damage target 1 fire)))";
    private static int nextId = 27300;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "fire", "beam").guiScale(2); }

    private static void cast(ServerPlayer player) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 100000;
        if (Casting.cast(player, Casting.compile(BEAM), null, null) == null)
            throw new IllegalStateException("real test beam was refused");
    }

    private static void camera(CameraType type) { Minecraft.getInstance().options.setCameraType(type); }

    /** A beam reported by hand (no caster), along +Z at the side-view line. */
    private static void hold(int id) {
        LogosFx.handle(new FxPayload((byte) FxPayload.Kind.BEAM_HOLD.ordinal(), (byte) Element.FIRE.ordinal(),
                (byte) 0, .5f, 102.4f, -2, .5f, 102.4f, 22, .35f, id, -1, 0, -1));
    }

    private static void stop(int id) {
        LogosFx.handle(new FxPayload((byte) FxPayload.Kind.BEAM_END.ordinal(), (byte) Element.FIRE.ordinal(),
                (byte) 0, .5f, 102.4f, 22, 0, 0, 0, .35f, id, -1, 0, -1));
    }

    /** Holds a fresh hand-reported beam for {@code ticks} ticks, a screenshot at each {@code shots} tick. */
    private static void held(ScenarioBuilder s, String prefix, int ticks, int... shots) {
        int id = nextId++;
        s.step("clear", ctx -> LogosFx.clearRays());
        int shot = 0;
        for (int i = 0; i < ticks; i++) {
            s.step("hold", ctx -> hold(id)).ticks(1);
            if (shot < shots.length && shots[shot] == i + 1) s.screenshot(prefix + "_t" + shots[shot++]);
        }
        s.step("end", ctx -> stop(id));
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 106, 10), 20)
                .fill(new BlockPos(-20, 100, -10), new BlockPos(20, 100, 32), Blocks.SMOOTH_STONE.defaultBlockState())
                .fill(new BlockPos(-3, 101, 18), new BlockPos(4, 105, 18), Blocks.STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -3, 0, 0)
                .step("show the hand", ctx -> Minecraft.getInstance().options.hideGui = false)
                .server("hold a wand", sc -> sc.player().setItemInHand(InteractionHand.MAIN_HAND,
                        new ItemStack(LogosMod.WOOD_WANDS.getFirst().get())))
                .ticks(10);

        // First person, a real cast into the wall 21 blocks out: reaching, held, its wake, gone.
        s.step("clear", ctx -> LogosFx.clearRays())
                .server("cast", sc -> cast(sc.player()))
                .ticks(2).screenshot("fp_reach")
                .ticks(20).screenshot("fp_sustain")
                .ticks(28).screenshot("fp_wake_early")
                .check("the beam leaves a wake", ctx -> LogosFx.liveRays() > 0)
                .ticks(40).screenshot("fp_wake_mid")
                .ticks(50).screenshot("fp_wake_late")
                .ticks(50).screenshot("fp_gone")
                .check("the wake is gone", ctx -> LogosFx.liveRays() == 0);

        // Third person, a fresh cast.
        s.step("third person", ctx -> camera(CameraType.THIRD_PERSON_BACK)).ticks(2)
                .step("clear", ctx -> LogosFx.clearRays())
                .server("cast", sc -> cast(sc.player()))
                .ticks(3).screenshot("tp_reach")
                .ticks(20).screenshot("tp_sustain")
                .ticks(40).screenshot("tp_wake")
                .step("first person", ctx -> camera(CameraType.FIRST_PERSON)).ticks(60);

        // From the side over the whole line.
        s.teleportPlayer(-13.5, 102, 10, -90, 6).ticks(4);
        held(s, "side_day", 24, 2, 4, 20);
        s.ticks(4).screenshot("side_day_wake_t4")
                .ticks(26).screenshot("side_day_wake_t30")
                .ticks(40).screenshot("side_day_wake_t70")
                .ticks(50).screenshot("side_day_wake_t120")
                .ticks(15).screenshot("side_day_gone")
                .check("the side wake is gone", ctx -> LogosFx.liveRays() == 0);

        // Night: from the side, then first person.
        s.runCommand("time set midnight").ticks(4);
        held(s, "side_night", 20, 3, 18);
        s.ticks(4).screenshot("side_night_wake_t4")
                .ticks(36).screenshot("side_night_wake_t40")
                .ticks(100);
        s.teleportPlayer(.5, 101, -3, 0, 0).ticks(4)
                .step("clear", ctx -> LogosFx.clearRays())
                .server("night cast", sc -> cast(sc.player()))
                .ticks(20).screenshot("fp_night_sustain")
                .ticks(30).screenshot("fp_night_wake")
                .runCommand("time set noon");
        s.teardown("reset the camera", ctx -> camera(CameraType.FIRST_PERSON));
    }
}
