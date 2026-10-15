package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.LogosMod;
import dev.logos.client.LogosFx;
import dev.logos.lang.Element;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.Vfx;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Real lightning rays from a held wand: into a wall, into a cow and into the open, in first person, from
 * behind and from the side (the player is moved aside right after casting), by day and by night, fired
 * in quick succession, and every stage of a ray (launch, peak, afterglow, fading, gone), each from a
 * fresh cast. Game time is frozen and stepped with {@code /tick step}, so every capture shows the ray at
 * exactly the age it names. Each case has its own lane stacked 40 blocks above the last, so a ray still
 * fading in one lane never shows in the next shot.
 */
@LDLRegisterClient(name = "logos_lightning_ray", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class LightningRayScenario implements UIScenario {
    private static final String SPELL = "(ray :range 30 (on-hit (damage target 1 lightning)))";
    /** Lane floors (the player stands one above): wall, cow, open, wall, open (side views), wall (third person). */
    private static final int WALL = 101, COW = 141, OPEN = 181, WALL2 = 221, SIDE = 261, BACK = 301;
    private static Cow target;
    private static long castAt;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "lightning", "ray").guiScale(2); }

    private static void cast(ServerPlayer player) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 10000;
        if (Casting.cast(player, Casting.compile(SPELL), null, null) == null)
            throw new IllegalStateException("real test spell was refused");
    }

    private static boolean last(Vfx.RayEnd end) {
        LogosFx.FiredRay ray = LogosFx.lastRay();
        return ray != null && ray.element() == Element.LIGHTNING && ray.end() == end;
    }

    private static long now() { return Minecraft.getInstance().level.getGameTime(); }

    /** Stands in a lane facing down it (+Z) and fires; the ray's age is counted from here. */
    private static void fire(ScenarioBuilder s, int lane, String name) {
        s.teleportPlayer(.5, lane, .5, 0, 0).ticks(3)
                .server(name, sc -> {
                    if (lane == COW) placeCow(sc.level());
                    cast(sc.player());
                })
                .ticks(2).step("note the cast", ctx -> castAt = now());
    }

    /** Fires down a lane, then at once moves the player to the lane's side, looking across it. */
    private static void fireSide(ScenarioBuilder s, int lane, double z, double away, String name) {
        s.teleportPlayer(.5, lane, .5, 0, 0).ticks(3)
                .server(name, sc -> {
                    if (lane == COW) placeCow(sc.level());
                    cast(sc.player());
                    sc.player().connection.teleport(-away + .5, lane, z, -90, 3);
                })
                .ticks(2).step("note the cast", ctx -> castAt = now());
    }

    /** Lets game time run until the ray is {@code ticks} old (time is frozen otherwise), then captures. */
    private static ScenarioBuilder shoot(ScenarioBuilder s, int ticks, String shot) {
        return s.step("step to " + shot, ctx -> Minecraft.getInstance().player.connection.sendCommand(
                        "tick step " + Math.max(1, ticks - (now() - castAt))))
                .waitUntil(shot + ": ray is " + ticks + " ticks old", ctx -> now() - castAt >= ticks)
                .ticks(2)
                .step("age of " + shot, ctx -> System.out.println("[lightning ray] " + shot + " at age " + (now() - castAt)))
                .screenshot(shot);
    }

    private static void placeCow(ServerLevel level) {
        if (target == null || !target.isAlive()) {
            target = EntityType.COW.create(level, EntitySpawnReason.COMMAND);
            if (target == null) throw new IllegalStateException("cow creation failed");
            target.setNoAi(true);
            level.addFreshEntity(target);
        }
        target.setHealth(target.getMaxHealth());
        target.setDeltaMovement(0, 0, 0);
        target.snapTo(.5, COW, 8.5, 180, 0);
    }

    private static void lane(ScenarioBuilder s, int y) {
        s.clearArea(new BlockPos(0, y + 3, 14), 18)
                .fill(new BlockPos(-3, y - 1, -3), new BlockPos(3, y - 1, 32), Blocks.SMOOTH_STONE.defaultBlockState())
                // Somewhere to stand for the side views.
                .fill(new BlockPos(-13, y - 1, 2), new BlockPos(-6, y - 1, 16), Blocks.SMOOTH_STONE.defaultBlockState());
        if (y == WALL || y == WALL2 || y == BACK || y == COW)
            s.fill(new BlockPos(-3, y, 12), new BlockPos(3, y + 4, 12), Blocks.STONE.defaultBlockState());
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.settleMs(0).setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear");
        for (int y : new int[] {WALL, COW, OPEN, WALL2, SIDE, BACK}) lane(s, y);
        s.setHeldItem(new ItemStack(LogosMod.WOOD_WANDS.getFirst().get()))
                .step("draw the held wand", ctx -> Minecraft.getInstance().options.hideGui = false)
                .teleportPlayer(.5, WALL, .5, 0, 0).ticks(20)
                .runCommand("tick freeze").ticks(3);

        // First person, one fresh cast per lane.
        fire(s, WALL, "ray into a wall");
        shoot(s, 1, "01_wall_launch")
                .check("a ray against a wall ends on the block", ctx -> last(Vfx.RayEnd.BLOCK))
                .check("the ray is drawn by the lightning visual", ctx -> LogosFx.liveRays() > 0);
        fire(s, COW, "ray at a cow");
        shoot(s, 3, "02_cow_peak")
                .check("a ray at a cow ends on the creature", ctx -> last(Vfx.RayEnd.CREATURE))
                .server("the cow was hurt", sc -> {
                    if (target == null || target.getHealth() >= target.getMaxHealth())
                        throw new IllegalStateException("ray damage did not apply");
                });
        fire(s, OPEN, "ray into the open");
        shoot(s, 3, "03_open_peak")
                .check("a ray into the open ends in the open", ctx -> last(Vfx.RayEnd.OPEN));
        fire(s, WALL2, "second wall");
        shoot(s, 3, "04_wall_peak");
        fireSide(s, SIDE, 15.5, 13, "open ray seen from the side");
        shoot(s, 2, "05_side_open_peak");
        s.step("third person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK));
        fire(s, BACK, "ray from behind the caster");
        shoot(s, 3, "06_third_person_peak")
                .step("first person again", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON));

        // The first wall lane is clear again: the afterglow, then a fresh ray fading out and gone.
        s.runCommand("tick step 120").ticks(5);
        fire(s, WALL, "fresh ray for the afterglow");
        shoot(s, 13, "07_wall_afterglow");
        fire(s, WALL2, "fresh ray for the fade");
        shoot(s, 68, "08_wall_fading");
        shoot(s, 134, "09_wall_gone")
                .check("an old enough ray is dropped", ctx -> LogosFx.liveRays() == 0);
        fireSide(s, COW, 5, 7, "cow ray seen from the side");
        shoot(s, 2, "10_side_cow_peak");
        fireSide(s, OPEN, 15.5, 13, "open ray re-striking, from the side");
        shoot(s, 24, "11_side_open_restrike");

        // Night.
        s.runCommand("time set midnight");
        fire(s, BACK, "night ray into a wall");
        shoot(s, 3, "12_night_wall_peak");
        fireSide(s, SIDE, 15.5, 13, "night afterglow from the side");
        shoot(s, 24, "13_night_side_afterglow");

        // Several rays in quick succession.
        s.runCommand("time set noon");
        for (int i = 0; i < 4; i++) {
            float yaw = -9 + i * 6;
            s.teleportPlayer(.5, WALL, .5, yaw, 0).ticks(2).server("rapid ray " + i, sc -> cast(sc.player()))
                    .ticks(2).step("note the cast", ctx -> castAt = now()).runCommand("tick step 2")
                    .waitUntil("two ticks pass", ctx -> now() - castAt >= 2);
        }
        s.ticks(2).screenshot("14_rapid_fire")
                .check("rapid rays are all still showing", ctx -> LogosFx.liveRays() >= 4);

        s.runCommand("tick step 140").waitUntil("every ray has faded", ctx -> LogosFx.liveRays() == 0)
                .check("nothing is left once the afterglow is over", ctx -> LogosFx.liveRays() == 0)
                .screenshot("15_all_gone")
                .runCommand("tick unfreeze");
        s.teardown("restore", ctx -> {
            Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
            if (target != null) { target.discard(); target = null; }
        });
        s.teardownServer("unfreeze", sc -> sc.level().getServer().tickRateManager().setFrozen(false));
    }
}
