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
import net.minecraft.client.CameraType;
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

/**
 * Real healing rays (inferred as life) from a held wand: into the open, into a wall and at a cow; the
 * stages of one ray, each sampled from a fresh cast in its own lane 40 blocks from the last so no older
 * ray shows; first and third person, the whole line from the side, night, a quick series, a life link,
 * and every ray gone in the end.
 */
@LDLRegisterClient(name = "logos_life_ray", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class LifeRayScenario implements UIScenario {
    private static final String OPEN = "(ray :range 16 (on-hit (heal target 2)))";
    private static final String LONG = "(ray :range 26 (on-hit (heal target 2)))";
    private static Cow target;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "ray", "life").guiScale(2); }

    private static void cast(ServerPlayer player, String source) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 10000;
        if (Casting.cast(player, Casting.compile(source), null, null) == null)
            throw new IllegalStateException("real test spell was refused");
    }

    private static boolean last(Vfx.RayEnd end) {
        LogosFx.FiredRay ray = LogosFx.lastRay();
        return ray != null && ray.element() == Element.LIFE && ray.end() == end;
    }

    /** Lane {@code i}: its own stretch of floor, 40 blocks along x from the last. */
    private static double lane(int i) { return .5 + 40 * i; }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .runCommand("gamerule doDaylightCycle false")
                .fill(new BlockPos(-12, 101, -6), new BlockPos(172, 112, 30), Blocks.AIR.defaultBlockState())
                .fill(new BlockPos(-12, 100, -6), new BlockPos(172, 100, 30), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(lane(0), 101, -2, 0, 0)
                .step("draw the held wand", ctx -> Minecraft.getInstance().options.hideGui = false)
                .server("hold a wand", sc -> sc.player().setItemInHand(InteractionHand.MAIN_HAND,
                        new ItemStack(LogosMod.WOOD_WANDS.getFirst().get())))
                .ticks(10);

        // Stages, first person, each from a fresh cast in a fresh lane.
        s.server("launch sample", sc -> cast(sc.player(), OPEN)).ticks(1).screenshot("life_ray_1_launch")
                .check("a heal ray is inferred as life and ends in the open", ctx -> last(Vfx.RayEnd.OPEN))
                .check("the life ray is drawn by its element", ctx -> LogosFx.liveRays() > 0);
        s.teleportPlayer(lane(1), 101, -2, 0, 0).ticks(2)
                .server("peak sample", sc -> cast(sc.player(), OPEN)).ticks(4).screenshot("life_ray_2_peak");
        s.teleportPlayer(lane(2), 101, -2, 0, 0).ticks(2)
                .server("afterglow sample", sc -> cast(sc.player(), OPEN)).ticks(16).screenshot("life_ray_3_afterglow");
        s.teleportPlayer(lane(3), 101, -2, 0, 0).ticks(2)
                .server("fading sample", sc -> cast(sc.player(), OPEN)).ticks(75).screenshot("life_ray_4_fading");

        // A wall, then a cow: the bloom where the ray lands.
        s.fill(new BlockPos(-2, 101, 9), new BlockPos(3, 105, 9), Blocks.STONE.defaultBlockState())
                .teleportPlayer(lane(0), 101, -2, 0, 0).ticks(2)
                .server("ray into a wall", sc -> cast(sc.player(), OPEN)).ticks(3).screenshot("life_ray_block_peak")
                .check("a blocked heal ray is life and ends on the block", ctx -> last(Vfx.RayEnd.BLOCK))
                .ticks(8).screenshot("life_ray_block_bloom");
        s.teleportPlayer(lane(1), 101, -2, 0, 0).ticks(2)
                .server("ray at a cow", sc -> {
                    target = EntityType.COW.create(sc.level(), EntitySpawnReason.COMMAND);
                    if (target == null) throw new IllegalStateException("cow creation failed");
                    target.setPos(lane(1), 101, 6);
                    target.setNoAi(true);
                    sc.level().addFreshEntity(target);
                }).ticks(2)
                .server("heal the cow", sc -> cast(sc.player(), OPEN)).ticks(4).screenshot("life_ray_creature")
                .check("a heal ray stopped by a creature is life and ends on it", ctx -> last(Vfx.RayEnd.CREATURE));

        // The whole line from the side: cast, then step aside to watch.
        s.teleportPlayer(lane(2), 101, -2, 0, 0).ticks(2)
                .server("long ray for the side view", sc -> cast(sc.player(), LONG))
                .teleportPlayer(lane(2) - 11, 103, 10, -90, 10).ticks(3).screenshot("life_ray_side_peak")
                .ticks(14).screenshot("life_ray_side_afterglow");
        // Third person from behind: cast along the floor ahead, then look on from beside the caster.
        s.teleportPlayer(lane(3), 101, -2, 0, 14)
                .step("third person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK)).ticks(3)
                .server("third-person ray", sc -> cast(sc.player(), OPEN))
                // Swing the camera round the caster so the line is seen at an angle rather than end-on.
                .teleportPlayer(lane(3), 101, -2, 55, 12).ticks(3).screenshot("life_ray_third_person_peak")
                .ticks(12).screenshot("life_ray_third_person_afterglow")
                .step("first person again", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON));
        // Night, into the wall.
        s.runCommand("time set midnight").teleportPlayer(lane(0), 101, -2, 0, 0).ticks(3)
                .server("night ray", sc -> cast(sc.player(), OPEN)).ticks(4).screenshot("life_ray_night_peak")
                .ticks(14).screenshot("life_ray_night_afterglow")
                .runCommand("time set noon");
        // A quick series of three, and a life link (drawn as an ordinary line).
        s.teleportPlayer(lane(4), 101, -2, 0, 0).ticks(3)
                .server("series 1", sc -> cast(sc.player(), OPEN)).ticks(3)
                .server("series 2", sc -> cast(sc.player(), OPEN)).ticks(3)
                .server("series 3", sc -> cast(sc.player(), OPEN)).ticks(2).screenshot("life_ray_series")
                .step("a life link", ctx -> LogosFx.handle(new FxPayload((byte) FxPayload.Kind.BEAM.ordinal(),
                        (byte) Element.LIFE.ordinal(), (byte) Vfx.RayEnd.LINK.ordinal(), (float) lane(4) - 4, 101.6f, 4,
                        (float) lane(4) + 4, 101.6f, 7, 1, 0, -1, 0, -1)))
                .ticks(4).screenshot("life_ray_link")
                .check("a life link reaches the client as a link", ctx -> last(Vfx.RayEnd.LINK));
        // Gone: one last fresh cast, then wait it out.
        s.teleportPlayer(lane(3), 101, -2, 0, 0).ticks(2)
                .server("last ray", sc -> cast(sc.player(), OPEN)).ticks(2)
                .check("the last ray is showing", ctx -> LogosFx.liveRays() > 0)
                .ticks(70).ticks(70).screenshot("life_ray_5_gone")
                .check("every life ray has fully faded and been dropped", ctx -> LogosFx.liveRays() == 0);
        s.teardown("clean up", ctx -> {
            Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
            if (target != null) { target.discard(); target = null; }
        });
    }
}
