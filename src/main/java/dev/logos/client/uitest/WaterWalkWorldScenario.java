package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Real-client check of 水上行走: the player casts it on themselves over a pool, stands and walks on
 * the water with their own keys, sneaks down into it, floats back up when they let go, and is hurt
 * by a fall onto it.
 * Run: {@code gradlew runClient -PldTest=logos_water_walk_world}.
 */
@LDLRegisterClient(name = "logos_water_walk_world", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class WaterWalkWorldScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    /** The pool's surface: two blocks of water on a stone floor at ORIGIN. */
    private static final double SURFACE = ORIGIN.getY() + 2 + 8 / 9.0;
    private Vec3 walkedFrom;
    private float healthBefore;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos").guiScale(2);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 4), 10)
                .fill(ORIGIN.offset(-8, 0, -4), ORIGIN.offset(8, 0, 14), Blocks.STONE.defaultBlockState())
                .fill(ORIGIN.offset(-8, 1, -4), ORIGIN.offset(8, 2, 14), Blocks.WATER.defaultBlockState())
                .teleportPlayer(ORIGIN.getX() + 0.5, SURFACE + 0.5, ORIGIN.getZ() + 0.5, 0, 25)
                .server("cast water-walk on oneself", sc -> {
                    var p = sc.player();
                    PlayerMagic m = PlayerMagic.of(p);
                    m.baseMaxMana = m.mana = 1000;
                    if (Casting.cast(p, Casting.compile("(water-walk caster 60)"), null, p.position()) == null)
                        throw new AssertionError("the cast was refused");
                    if (!p.hasEffect(LogosMod.WATER_WALKING)) throw new AssertionError("no water walking after the cast");
                })
                .ticks(20)
                .step("standing on the water", ctx -> {
                    var p = Minecraft.getInstance().player;
                    System.out.printf("[logos] water-walk: player at %.3f, surface %.3f, on ground %s, in water %s%n", p.getY(), SURFACE, p.onGround(), p.isInWater());
                    check(p.hasEffect(LogosMod.WATER_WALKING), "the client does not know the effect");
                    check(p.onGround() && Math.abs(p.getY() - SURFACE) < 1e-3 && !p.isInWater(), "the player is not standing on the surface");
                    Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK);
                })
                .ticks(5)
                .screenshot("standing")
                .step("walk forward", ctx -> {
                    walkedFrom = Minecraft.getInstance().player.position();
                    Minecraft.getInstance().options.keyUp.setDown(true);
                })
                .ticks(20)
                .step("walked on the water", ctx -> {
                    Minecraft.getInstance().options.keyUp.setDown(false);
                    var p = Minecraft.getInstance().player;
                    double perTick = p.position().distanceTo(walkedFrom) / 20;
                    System.out.printf("[logos] water-walk: walks %.3f blocks/tick, at %.3f%n", perTick, p.getY());
                    check(perTick > 0.1, "the player barely moved: " + perTick + " blocks/tick");
                    check(Math.abs(p.getY() - SURFACE) < 1e-3 && !p.isInWater(), "the player did not stay on the surface");
                })
                .screenshot("walked")
                .step("sneak", ctx -> Minecraft.getInstance().options.keyShift.setDown(true))
                .ticks(20)
                .step("sank in", ctx -> {
                    Minecraft.getInstance().options.keyShift.setDown(false);
                    var p = Minecraft.getInstance().player;
                    System.out.printf("[logos] water-walk: sneaked down to %.3f%n", p.getY());
                    check(p.isInWater() && p.getY() < SURFACE - 0.5, "sneaking did not sink the player");
                })
                .ticks(40)
                .step("floated back up", ctx -> {
                    var p = Minecraft.getInstance().player;
                    System.out.printf("[logos] water-walk: floated back to %.3f%n", p.getY());
                    check(p.onGround() && Math.abs(p.getY() - SURFACE) < 1e-3 && !p.isInWater(), "the player did not come back up onto the surface");
                })
                .server("lift the player ten blocks up", sc -> {
                    var p = sc.player();
                    healthBefore = p.getHealth();
                    p.teleportTo(p.getX(), SURFACE + 10, p.getZ());
                })
                .ticks(40)
                .step("landed on the water and was hurt", ctx -> {
                    var p = Minecraft.getInstance().player;
                    System.out.printf("[logos] water-walk: landed at %.3f, health %.1f -> %.1f%n", p.getY(), healthBefore, p.getHealth());
                    check(Math.abs(p.getY() - SURFACE) < 1e-3 && !p.isInWater(), "the player did not land on the surface");
                    check(p.getHealth() < healthBefore, "a fall onto the water did not hurt");
                    Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
                });
    }
}
