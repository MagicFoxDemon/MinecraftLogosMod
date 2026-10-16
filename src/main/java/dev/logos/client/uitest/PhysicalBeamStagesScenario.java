package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.LogosMod;
import dev.logos.client.LogosFx;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * A real physical beam at every stage, each a fresh cast after {@link LogosFx#clearRays()}: its first ticks
 * (the cross flash with the beam on tick 0, the end racing out) with frozen ticks stepped by hand, held into the
 * open, a wall and a cow, then the afterline it leaves and nothing left; from the caster's own eyes and from the
 * side along its whole length, by day and night.
 */
@LDLRegisterClient(name = "logos_physical_beam_stages", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class PhysicalBeamStagesScenario implements UIScenario {
    private static final String BEAM = "(beam :radius 0.35 :range 24 :life 2 (on-hit (damage target 1 physical)))";
    /** From the side the beam leaves a fixed point down +Z (the caster is turned for the cast only), so the camera can stand aside. */
    private static final String SIDE_BEAM = "(beam :from here :dir aim :radius 0.35 :range 24 :life 2 (on-hit (damage target 1 physical)))";
    private static final Vec3 SIDE_FROM = new Vec3(.5, 101.9, -2);
    /** A two-second beam is over by here (with a margin for the end packet). */
    private static final int BEAM_LIFE = 44;
    /** Past this after the beam ends its afterline is gone. */
    private static final int WAKE_CLEAR = 130;
    private static boolean sideView;
    private static Cow cow;

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("logos", "vfx", "beam", "physical").guiScale(2).scenarioTimeoutMs(900_000);
    }

    private static void cast(ServerPlayer player) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 10000;
        float yaw = player.getYRot(), pitch = player.getXRot();
        // A side cast aims down +Z whichever way the watching camera faces; the client keeps its own view.
        if (sideView) { player.setYRot(0); player.setXRot(0); }
        try {
            if (Casting.cast(player, Casting.compile(sideView ? SIDE_BEAM : BEAM), null, sideView ? SIDE_FROM : null) == null)
                throw new IllegalStateException("real test spell was refused");
        } finally {
            player.setYRot(yaw); player.setXRot(pitch);
        }
    }

    /** Frozen, a fresh cast, {@code step} ticks stepped by hand, a shot, then the beam runs out and its wake is cleared. */
    private static void at(ScenarioBuilder s, String shot, int step) {
        s.step("clear for " + shot, ctx -> LogosFx.clearRays())
                .runCommand("tick freeze").ticks(2)
                .server("cast for " + shot, sc -> cast(sc.player()))
                .runCommand("tick step " + step).ticks(step + 3).screenshot(shot)
                .runCommand("tick unfreeze").ticks(BEAM_LIFE)
                .step("clear after " + shot, ctx -> LogosFx.clearRays()).ticks(2);
    }

    /** One cast held to its end, then the afterline it leaves sampled until nothing is left. */
    private static void life(ScenarioBuilder s, String prefix) {
        s.step("clear for " + prefix, ctx -> LogosFx.clearRays())
                .server("cast for " + prefix, sc -> cast(sc.player()))
                .ticks(BEAM_LIFE + 2).screenshot(prefix + "_wake_early")
                .check(prefix + ": the beam leaves a wake", ctx -> LogosFx.liveRays() > 0)
                .ticks(40).screenshot(prefix + "_wake_mid")
                .ticks(40).screenshot(prefix + "_wake_late")
                .ticks(WAKE_CLEAR - 80).screenshot(prefix + "_gone")
                .check(prefix + ": nothing is left once it has played out", ctx -> LogosFx.liveRays() == 0);
    }

    private static void wall(ScenarioBuilder s, boolean up) {
        s.fill(new BlockPos(-2, 101, 14), new BlockPos(2, 104, 14), (up ? Blocks.STONE : Blocks.AIR).defaultBlockState());
    }

    private static void side(ScenarioBuilder s, boolean on) {
        s.step(on ? "watch from the side" : "back to the caster's eyes", ctx -> sideView = on);
        if (on) s.teleportPlayer(-15.5, 101, 9, -90, 6).ticks(10);
        else s.teleportPlayer(.5, 101, -2, 0, 0).ticks(10);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .step("start in first person", ctx -> sideView = false)
                .clearArea(new BlockPos(0, 106, 12), 26)
                .fill(new BlockPos(-20, 100, -10), new BlockPos(18, 100, 34), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -2, 0, 0)
                .server("hold a wand", sc -> sc.player().setItemInHand(InteractionHand.MAIN_HAND,
                        new ItemStack(LogosMod.WOOD_WANDS.getFirst().get())))
                .step("draw the held wand", ctx -> Minecraft.getInstance().options.hideGui = false)
                .ticks(10);

        // First person: the first ticks, then held.
        at(s, "fp_t0", 1);
        at(s, "fp_t1", 2);
        at(s, "fp_t2", 3);
        at(s, "fp_t5", 6);
        at(s, "fp_held", 22);
        wall(s, true);
        at(s, "fp_wall_bite", 5);
        at(s, "fp_wall_held", 24);
        wall(s, false);
        s.server("a cow down the line", sc -> {
            cow = EntityType.COW.create(sc.level(), EntitySpawnReason.COMMAND);
            if (cow == null) throw new IllegalStateException("cow creation failed");
            cow.setPos(.5, 101.4, 9);
            cow.setNoAi(true);
            cow.setNoGravity(true);
            cow.setInvulnerable(true);
            // The beam's real damage would otherwise kill it before the side shots.
            var health = cow.getAttribute(Attributes.MAX_HEALTH);
            if (health != null) health.setBaseValue(1000);
            cow.setHealth(1000);
            sc.level().addFreshEntity(cow);
        }).ticks(5);
        at(s, "fp_cow_held", 16);
        s.server("cow aside", sc -> cow.teleportTo(-18.5, 101, -8)).ticks(3);
        life(s, "fp");

        // From the side, the whole length.
        side(s, true);
        at(s, "side_t0", 1);
        at(s, "side_t1", 2);
        at(s, "side_t2", 3);
        at(s, "side_t4", 5);
        at(s, "side_held", 20);
        at(s, "side_pulse", 25);
        wall(s, true);
        at(s, "side_wall_bite", 5);
        at(s, "side_wall_held", 26);
        wall(s, false);
        s.server("cow back on the line", sc -> cow.teleportTo(.5, 101.4, 9)).ticks(5);
        at(s, "side_cow_held", 18);
        s.server("cow aside again", sc -> cow.teleportTo(-18.5, 101, -8)).ticks(3);
        life(s, "side");

        // Night.
        s.runCommand("time set midnight").ticks(3);
        at(s, "side_night_t0", 1);
        at(s, "side_night_held", 22);
        life(s, "side_night");
        side(s, false);
        at(s, "fp_night_t0", 1);
        at(s, "fp_night_held", 22);
        s.runCommand("time set noon").ticks(3);

        s.teardown("remove the cow", ctx -> {
            sideView = false;
            var server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null) server.execute(() -> { if (cow != null) { cow.discard(); cow = null; } });
        });
    }
}
