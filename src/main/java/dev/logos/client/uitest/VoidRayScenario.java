package dev.logos.client.uitest;

import java.util.List;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * The void ray from real casts: into the open, into a wall and at a cow, and a drain link; first person
 * from the held wand, from behind, and from the side over its whole length; its stages (each from a fresh
 * cast), by night, and a volley; and that every ray is dropped once nothing of it is left.
 */
@LDLRegisterClient(name = "logos_void_ray", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class VoidRayScenario implements UIScenario {
    private static final String RAY = "(ray :range 30 (on-hit (damage target 1 void)))";
    /** Free mana left by every cast piles up into glare; it is not part of the ray. */
    private static final String CALM = "kill @e[type=logos:free_mana]";
    private static Cow target;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "void", "ray").guiScale(2); }

    private static void cast(ServerPlayer player, String source) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 10000;
        if (Casting.cast(player, Casting.compile(source), null, null) == null)
            throw new IllegalStateException("real test spell was refused");
    }

    private static boolean last(Vfx.RayEnd end) {
        LogosFx.FiredRay ray = LogosFx.lastRay();
        return ray != null && ray.element() == Element.VOID && ray.end() == end;
    }

    /** Drops the rays still showing, so the next stage sample starts from a fresh cast only. */
    private static void clearRays() {
        try {
            var field = LogosFx.class.getDeclaredField("RAYS");
            field.setAccessible(true);
            ((List<?>) field.get(null)).clear();
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static void camera(CameraType type) {
        Minecraft.getInstance().options.setCameraType(type);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 108, 12), 20)
                .fill(new BlockPos(-14, 100, -8), new BlockPos(14, 100, 30), Blocks.SMOOTH_STONE.defaultBlockState())
                .fill(new BlockPos(-2, 101, 16), new BlockPos(2, 105, 16), Blocks.STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -2, 0, 0)
                .step("draw the held wand", ctx -> Minecraft.getInstance().options.hideGui = false)
                .server("hold a wand", sc -> sc.player().setItemInHand(InteractionHand.MAIN_HAND,
                        new ItemStack(LogosMod.WOOD_WANDS.getFirst().get())))
                .ticks(10);

        // Stages in first person, each from a fresh cast into the wall.
        s.server("launch sample", sc -> cast(sc.player(), RAY)).ticks(1).screenshot("void_ray_fp_launch")
                .check("the wall stops the ray", ctx -> last(Vfx.RayEnd.BLOCK))
                .check("the void ray is drawn by its element", ctx -> LogosFx.liveRays() > 0)
                .step("clear", ctx -> clearRays()).runCommand(CALM)
                .server("peak sample", sc -> cast(sc.player(), RAY)).ticks(3).screenshot("void_ray_fp_peak")
                .step("clear", ctx -> clearRays()).runCommand(CALM)
                .server("narrowing sample", sc -> cast(sc.player(), RAY)).ticks(12).screenshot("void_ray_fp_narrowing")
                .step("clear", ctx -> clearRays()).runCommand(CALM)
                .server("late sample", sc -> cast(sc.player(), RAY)).ticks(60).screenshot("void_ray_fp_late")
                .check("the line still shows late", ctx -> LogosFx.liveRays() > 0)
                .step("clear", ctx -> clearRays()).runCommand(CALM);

        // The same ray from behind, and from the side (cast first, then step aside to watch).
        s.teleportPlayer(.5, 101, -2, 0, -5).step("from behind", ctx -> camera(CameraType.THIRD_PERSON_BACK))
                .server("third-person sample", sc -> cast(sc.player(), RAY)).ticks(3).screenshot("void_ray_back_peak")
                .step("first person", ctx -> camera(CameraType.FIRST_PERSON)).teleportPlayer(.5, 101, -2, 0, 0)
                .step("clear", ctx -> clearRays()).runCommand(CALM)
                .server("side peak sample", sc -> cast(sc.player(), RAY)).ticks(1)
                .teleportPlayer(-9, 102, 7, -90, 8).ticks(2).screenshot("void_ray_side_peak")
                .teleportPlayer(.5, 101, -2, 0, 0).step("clear", ctx -> clearRays()).runCommand(CALM).ticks(2)
                .server("side narrowing sample", sc -> cast(sc.player(), RAY)).ticks(1)
                .teleportPlayer(-9, 102, 7, -90, 8).ticks(11).screenshot("void_ray_side_narrowing")
                .teleportPlayer(.5, 101, -2, 0, 0).step("clear", ctx -> clearRays()).runCommand(CALM).ticks(2)
                .server("side late sample", sc -> cast(sc.player(), RAY)).ticks(1)
                .teleportPlayer(-9, 102, 7, -90, 8).ticks(45).screenshot("void_ray_side_late")
                .teleportPlayer(.5, 101, -2, 0, 0).step("clear", ctx -> clearRays()).runCommand(CALM);

        // Into the open: no wall, the full 30 blocks fray out in the air.
        s.fill(new BlockPos(-2, 101, 16), new BlockPos(2, 105, 16), Blocks.AIR.defaultBlockState()).ticks(2)
                .server("open sample", sc -> cast(sc.player(), RAY)).ticks(3).screenshot("void_ray_open_fp")
                .check("an open ray ends in the open", ctx -> last(Vfx.RayEnd.OPEN))
                .step("clear", ctx -> clearRays()).runCommand(CALM)
                .server("open side sample", sc -> cast(sc.player(), RAY)).ticks(1)
                .teleportPlayer(-15, 104, 13, -90, 10).ticks(3).screenshot("void_ray_open_side")
                .teleportPlayer(.5, 101, -2, 0, 0).step("clear", ctx -> clearRays()).runCommand(CALM).ticks(2);

        // At a cow, then a drain link to it.
        s.server("ray at a cow", sc -> {
                    target = EntityType.COW.create(sc.level(), EntitySpawnReason.COMMAND);
                    if (target == null) throw new IllegalStateException("cow creation failed");
                    target.setPos(.5, 101, 7);
                    target.setNoAi(true);
                    sc.level().addFreshEntity(target);
                }).ticks(3)
                .server("cast at the cow", sc -> cast(sc.player(), RAY)).ticks(3).screenshot("void_ray_creature_fp")
                .check("a ray stopped by the cow ends on it", ctx -> last(Vfx.RayEnd.CREATURE))
                .server("the cow is hurt", sc -> {
                    if (target == null || target.getHealth() >= target.getMaxHealth())
                        throw new IllegalStateException("ray damage did not apply");
                })
                .step("clear", ctx -> clearRays()).runCommand(CALM)
                .server("side creature sample", sc -> cast(sc.player(), RAY)).ticks(1)
                .teleportPlayer(-7, 102, 3, -90, 8).ticks(3).screenshot("void_ray_creature_side")
                .teleportPlayer(.5, 101, -2, 0, 0).step("clear", ctx -> clearRays()).runCommand(CALM).ticks(2)
                .server("drain the cow", sc -> cast(sc.player(), "(drain (nearest :radius 12) 1)")).ticks(3)
                .screenshot("void_ray_link_fp")
                .check("drain draws a void link", ctx -> last(Vfx.RayEnd.LINK))
                .step("clear", ctx -> clearRays()).runCommand(CALM)
                .server("side drain sample", sc -> cast(sc.player(), "(drain (nearest :radius 12) 1)")).ticks(1)
                .teleportPlayer(-7, 102, 3, -90, 8).ticks(3).screenshot("void_ray_link_side")
                .teleportPlayer(.5, 101, -2, 0, 0).step("clear", ctx -> clearRays()).runCommand(CALM)
                .server("remove the cow", sc -> { if (target != null) { target.discard(); target = null; } }).ticks(2);

        // By night into the wall, then a volley sweeping across.
        s.fill(new BlockPos(-6, 101, 16), new BlockPos(6, 105, 16), Blocks.STONE.defaultBlockState())
                .runCommand("time set midnight").ticks(3)
                .server("night peak sample", sc -> cast(sc.player(), RAY)).ticks(3).screenshot("void_ray_night_peak")
                .step("clear", ctx -> clearRays()).runCommand(CALM)
                .server("night narrowing sample", sc -> cast(sc.player(), RAY)).ticks(12).screenshot("void_ray_night_narrowing")
                .step("clear", ctx -> clearRays()).runCommand(CALM)
                .runCommand("time set noon")
                .teleportPlayer(.5, 101, -2, -14, -5).server("volley 1", sc -> cast(sc.player(), RAY)).ticks(2)
                .teleportPlayer(.5, 101, -2, -5, 3).server("volley 2", sc -> cast(sc.player(), RAY)).ticks(2)
                .teleportPlayer(.5, 101, -2, 5, -2).server("volley 3", sc -> cast(sc.player(), RAY)).ticks(2)
                .teleportPlayer(.5, 101, -2, 14, 5).server("volley 4", sc -> cast(sc.player(), RAY)).ticks(1)
                .teleportPlayer(.5, 101, -2, 0, 0).ticks(2).screenshot("void_ray_volley").runCommand(CALM)
                .check("a volley keeps every ray", ctx -> LogosFx.liveRays() >= 3)
                // The line lasts 111 ticks; every volley ray is older than that here.
                .ticks(115).screenshot("void_ray_volley_gone")
                .check("nothing is left once the lines are gone", ctx -> LogosFx.liveRays() == 0);
        s.teardown("restore", ctx -> {
            camera(CameraType.FIRST_PERSON);
            if (target != null) { target.discard(); target = null; }
        });
    }
}
