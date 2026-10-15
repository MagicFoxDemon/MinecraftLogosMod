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
import dev.logos.runtime.Vfx;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Real physical rays at every stage: launch, peak, the stuttering afterline and nothing left; into the open,
 * a wall and a cow; from the caster's own eyes and from the side along their whole length; by day and night;
 * fired in quick succession; and a physical link. Every stage is a fresh cast.
 */
@LDLRegisterClient(name = "logos_physical_ray", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class PhysicalRayScenario implements UIScenario {
    private static final String RAY = "(ray :range %d (on-hit (damage target 1 physical)))";
    /** From the side the ray leaves a fixed point; the caster is turned to face +Z on the server for the cast only. */
    private static final String SIDE_RAY = "(ray :from here :range %d (on-hit (damage target 1 physical)))";
    private static final Vec3 SIDE_FROM = new Vec3(.5, 102.4, -2);
    /** Past the last sample, everything one ray drew is gone. */
    private static final int CLEAR = 122;
    private static Cow cow;

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("logos", "vfx", "ray").guiScale(2).scenarioTimeoutMs(900_000);
    }

    private static void cast(ServerPlayer player, String source, Vec3 here) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 10000;
        float yaw = player.getYRot(), pitch = player.getXRot();
        // A side cast aims down +Z whichever way the watching camera faces; the client keeps its own view.
        if (here != null) { player.setYRot(0); player.setXRot(0); }
        try {
            if (Casting.cast(player, Casting.compile(source), null, here) == null)
                throw new IllegalStateException("real test spell was refused");
        } finally {
            player.setYRot(yaw); player.setXRot(pitch);
        }
    }

    private static boolean last(Vfx.RayEnd end) {
        LogosFx.FiredRay ray = LogosFx.lastRay();
        return ray != null && ray.element() == Element.PHYSICAL && ray.end() == end;
    }

    /** One first-person cast, sampled {@code age} ticks later, then left to clear. */
    private static void eyes(ScenarioBuilder s, String shot, int range, int age, Vfx.RayEnd end) {
        s.server("cast for " + shot, sc -> cast(sc.player(), RAY.formatted(range), null))
                .ticks(age).screenshot(shot)
                .check(shot + " ends " + end, ctx -> last(end))
                .ticks(CLEAR - age);
    }

    /** One cast seen from the side, sampled {@code age} ticks later, then left to clear. */
    private static void side(ScenarioBuilder s, String shot, int range, int age, Vfx.RayEnd end) {
        s.server("cast for " + shot, sc -> cast(sc.player(), SIDE_RAY.formatted(range), SIDE_FROM))
                .ticks(age).screenshot(shot)
                .check(shot + " ends " + end, ctx -> last(end))
                .ticks(CLEAR - age);
    }

    /** A full life sampled from one cast: lit, the afterline, then nothing drawn and nothing kept. */
    private static void life(ScenarioBuilder s, String prefix, boolean fromSide) {
        s.server("cast for " + prefix + " life", sc -> {
                    if (fromSide) cast(sc.player(), SIDE_RAY.formatted(30), SIDE_FROM);
                    else cast(sc.player(), RAY.formatted(30), null);
                })
                .ticks(2).check(prefix + ": the ray is live after it fires", ctx -> LogosFx.liveRays() > 0)
                .ticks(30).screenshot(prefix + "_afterline")
                .ticks(31).screenshot(prefix + "_afterline_late")
                .ticks(CLEAR - 63).screenshot(prefix + "_gone")
                .check(prefix + ": nothing is left once it has played out", ctx -> LogosFx.liveRays() == 0);
    }

    private static void wall(ScenarioBuilder s, boolean up) {
        s.fill(new BlockPos(-2, 101, 16), new BlockPos(2, 104, 16), (up ? Blocks.STONE : Blocks.AIR).defaultBlockState());
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 106, 16), 26)
                .fill(new BlockPos(-18, 100, -8), new BlockPos(18, 100, 40), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -2, 0, 0)
                .server("hold a wand", sc -> sc.player().setItemInHand(InteractionHand.MAIN_HAND,
                        new ItemStack(LogosMod.WOOD_WANDS.getFirst().get())))
                .step("draw the held wand", ctx -> Minecraft.getInstance().options.hideGui = false)
                .ticks(10);

        // First person, into the open.
        eyes(s, "fp_open_launch", 30, 1, Vfx.RayEnd.OPEN);
        eyes(s, "fp_open_peak", 30, 3, Vfx.RayEnd.OPEN);
        life(s, "fp_open", false);
        // First person, into a wall and into a cow.
        wall(s, true);
        eyes(s, "fp_wall_impact", 30, 3, Vfx.RayEnd.BLOCK);
        eyes(s, "fp_wall_burst", 30, 7, Vfx.RayEnd.BLOCK);
        wall(s, false);
        s.server("a cow down the line", sc -> {
            cow = EntityType.COW.create(sc.level(), EntitySpawnReason.COMMAND);
            if (cow == null) throw new IllegalStateException("cow creation failed");
            cow.setPos(.5, 101, 10);
            cow.setNoAi(true);
            cow.setInvulnerable(true);
            sc.level().addFreshEntity(cow);
        }).ticks(5);
        eyes(s, "fp_cow_impact", 30, 3, Vfx.RayEnd.CREATURE);
        s.server("cow aside", sc -> cow.setPos(-16.5, 101, -6)).ticks(3);
        // The whole length from the side.
        s.teleportPlayer(-13.5, 101, 11, -90, 0).ticks(10);
        side(s, "side_open_launch", 30, 1, Vfx.RayEnd.OPEN);
        side(s, "side_open_peak", 30, 3, Vfx.RayEnd.OPEN);
        side(s, "side_open_rings", 30, 6, Vfx.RayEnd.OPEN);
        life(s, "side_open", true);
        wall(s, true);
        side(s, "side_wall_impact", 30, 4, Vfx.RayEnd.BLOCK);
        wall(s, false);
        s.server("cow back on the line", sc -> cow.setPos(.5, 101, 10)).ticks(3);
        side(s, "side_cow_impact", 30, 4, Vfx.RayEnd.CREATURE);
        s.server("cow aside again", sc -> cow.setPos(-16.5, 101, -6)).ticks(3);
        s.step("a physical link", ctx -> LogosFx.handle(new FxPayload((byte) FxPayload.Kind.BEAM.ordinal(),
                        (byte) Element.PHYSICAL.ordinal(), (byte) Vfx.RayEnd.LINK.ordinal(),
                        .5f, 101.8f, 2, .5f, 101.8f, 22, 1, 0, -1, 0, -1)))
                .ticks(3).screenshot("side_link")
                .check("a physical link is drawn by the physical ray", ctx -> last(Vfx.RayEnd.LINK) && LogosFx.liveRays() > 0)
                .ticks(CLEAR - 3);
        s.server("four rays in quick succession", sc -> cast(sc.player(), SIDE_RAY.formatted(30), SIDE_FROM))
                .ticks(2).server("second", sc -> cast(sc.player(), SIDE_RAY.formatted(30), SIDE_FROM.add(0, .9, 0)))
                .ticks(2).server("third", sc -> cast(sc.player(), SIDE_RAY.formatted(30), SIDE_FROM.add(0, 1.8, 0)))
                .ticks(2).server("fourth", sc -> cast(sc.player(), SIDE_RAY.formatted(30), SIDE_FROM.add(0, 2.7, 0)))
                .ticks(2).screenshot("side_rapid")
                .check("every ray of a burst is live", ctx -> LogosFx.liveRays() >= 4)
                .ticks(CLEAR);
        // Night.
        s.runCommand("time set midnight").ticks(3);
        side(s, "side_night_peak", 30, 3, Vfx.RayEnd.OPEN);
        s.server("cast for side_night_afterline", sc -> cast(sc.player(), SIDE_RAY.formatted(30), SIDE_FROM))
                .ticks(32).screenshot("side_night_afterline").ticks(CLEAR - 32);
        s.teleportPlayer(.5, 101, -2, 0, 0).ticks(10);
        eyes(s, "fp_night_peak", 30, 3, Vfx.RayEnd.OPEN);
        s.runCommand("time set noon").ticks(3);
        // Longest range.
        eyes(s, "fp_far_peak", 64, 3, Vfx.RayEnd.OPEN);
        s.check("every ray has played out", ctx -> LogosFx.liveRays() == 0);

        s.teardown("remove the cow", ctx -> {
            if (cow != null) { cow.discard(); cow = null; }
        });
    }
}
