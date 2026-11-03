package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.runtime.Incarnation;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Real-client check of a form's traits, where the player's own ticks move them: a spider walks up
 * a wall, a strider stands on lava, an enderman in water is hurt ({@link Sunburn}: a zombie under the
 * noon sun catches fire). Run: {@code gradlew runClient -PldTest=logos_incarnate_traits}.
 */
@LDLRegisterClient(name = "logos_incarnate_traits", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class IncarnateTraitsScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    private double startY;
    private float health;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "incarnate").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .runCommand("difficulty normal")
                .clearArea(ORIGIN.offset(0, 6, 4), 10)
                .fill(ORIGIN.offset(-10, 0, -8), ORIGIN.offset(10, 0, 14), Blocks.SMOOTH_STONE.defaultBlockState())
                // A wall two blocks ahead, eight high; a lava pool to the left; a water pool to the right.
                .fill(ORIGIN.offset(-2, 1, 2), ORIGIN.offset(2, 8, 2), Blocks.STONE.defaultBlockState())
                .fill(ORIGIN.offset(-6, 0, -2), ORIGIN.offset(-4, 0, 0), Blocks.LAVA.defaultBlockState())
                .fill(ORIGIN.offset(4, 0, -2), ORIGIN.offset(6, 0, 0), Blocks.WATER.defaultBlockState())
                .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 0.5, 0, 0)
                .server("become a spider", sc -> {
                    sc.player().getAbilities().instabuild = true; // no kills needed, still survival
                    if (!Incarnation.start(sc.player(), EntityType.SPIDER, 60)) throw new AssertionError("no spider");
                    sc.player().getAbilities().instabuild = false;
                })
                .ticks(5)
                .step("walk into the wall", ctx -> {
                    startY = Minecraft.getInstance().player.getY();
                    Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK);
                    Minecraft.getInstance().options.keyUp.setDown(true);
                })
                .ticks(30)
                .screenshot("spider_climbing")
                .step("it climbed", ctx -> {
                    Minecraft.getInstance().options.keyUp.setDown(false);
                    double rose = Minecraft.getInstance().player.getY() - startY;
                    System.out.printf("[logos] incarnate traits: spider rose %.2f blocks%n", rose);
                    if (rose < 2) throw new AssertionError("the spider did not climb: " + rose);
                })
                .server("become a strider over the lava", sc -> {
                    var p = sc.player();
                    p.getAbilities().instabuild = true;
                    p.teleportTo(ORIGIN.getX() - 4.5, ORIGIN.getY() + 1.5, ORIGIN.getZ() - 0.5);
                    if (!Incarnation.start(p, EntityType.STRIDER, 60)) throw new AssertionError("no strider");
                    p.getAbilities().instabuild = false;
                })
                .ticks(20)
                .step("it stands on the lava", ctx -> {
                    var p = Minecraft.getInstance().player;
                    System.out.printf("[logos] incarnate traits: strider at y %.3f, in lava %s%n", p.getY(), p.isInLava());
                    if (p.isInLava() || p.getY() < ORIGIN.getY() + 0.8) throw new AssertionError("the strider sank: y " + p.getY());
                })
                .screenshot("strider_on_lava")
                .server("become an enderman in the water", sc -> {
                    var p = sc.player();
                    p.getAbilities().instabuild = true;
                    p.teleportTo(ORIGIN.getX() + 5.5, ORIGIN.getY() + 0.1, ORIGIN.getZ() - 0.5);
                    if (!Incarnation.start(p, EntityType.ENDERMAN, 60)) throw new AssertionError("no enderman");
                    p.getAbilities().instabuild = false;
                    health = p.getHealth();
                })
                .ticks(30)
                .server("water hurts it", sc -> {
                    System.out.printf("[logos] incarnate traits: enderman health %.1f -> %.1f%n", health, sc.player().getHealth());
                    if (sc.player().getHealth() >= health) throw new AssertionError("the water did not hurt the enderman");
                })
                .step("reset", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON));
    }

    /**
     * A zombie under the noon sun catches fire within twelve seconds (each tick in full light is about a
     * one-in-twenty-five chance, as for the creature). Its own scenario: the tool stalls near client tick 410.
     * Run: {@code gradlew runClient -PldTest=logos_incarnate_sunburn}.
     */
    @LDLRegisterClient(name = "logos_incarnate_sunburn", group = "logos", registry = UIScenario.REGISTRY,
            environment = RegistrationEnvironment.DEV_ONLY)
    public static class Sunburn implements UIScenario {
        @Override
        public void configure(ScenarioOptions o) {
            o.tags("logos", "incarnate").guiScale(2);
        }

        @Override
        public void define(ScenarioBuilder s) {
            s.setGameMode(GameType.SURVIVAL)
                    .runCommand("time set noon")
                    .runCommand("weather clear")
                    .clearArea(ORIGIN.offset(0, 6, 4), 10)
                    .fill(ORIGIN.offset(-10, 0, -8), ORIGIN.offset(10, 0, 14), Blocks.SMOOTH_STONE.defaultBlockState())
                    .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 0.5, 0, 0)
                    .server("become a zombie in the sun", sc -> {
                        var p = sc.player();
                        p.getAbilities().instabuild = true;
                        if (!Incarnation.start(p, EntityType.ZOMBIE, 60)) throw new AssertionError("no zombie");
                        p.getAbilities().instabuild = false;
                    })
                    .step("look from behind", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                    // Each wait must finish within the tool's five seconds.
                    .ticks(80)
                    .ticks(80)
                    .ticks(80)
                    .server("the sun set it alight", sc -> {
                        System.out.printf("[logos] incarnate traits: zombie on fire %s%n", sc.player().isOnFire());
                        if (!sc.player().isOnFire()) throw new AssertionError("twelve seconds of noon sun did not set the zombie alight");
                    })
                    .screenshot("zombie_burning")
                    .step("reset", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON));
        }
    }
}
