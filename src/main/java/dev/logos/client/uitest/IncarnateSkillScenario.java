package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.runtime.Incarnation;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Real-client check of a form's skill: a player who has become a blaze presses the use button,
 * holding a block (a blaze has no hands, so it does not place it), and three fireballs fly where
 * they look. Run: {@code gradlew runClient -PldTest=logos_incarnate_skill}.
 */
@LDLRegisterClient(name = "logos_incarnate_skill", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class IncarnateSkillScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "incarnate").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 4), 10)
                .fill(ORIGIN.offset(-10, 0, -8), ORIGIN.offset(10, 0, 14), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 0.5, 0, -5)
                .server("become a blaze holding stone", sc -> {
                    sc.player().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE));
                    if (!Incarnation.start(sc.player(), EntityType.BLAZE, 30)) throw new AssertionError("no blaze");
                })
                .ticks(10)
                .step("the use button is the skill", ctx -> {
                    if (!Incarnation.skillOnUse(Minecraft.getInstance().player)) throw new AssertionError("the client does not see the blaze's skill");
                    Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK);
                    KeyMapping.click(Minecraft.getInstance().options.keyUse.getKey());
                })
                .ticks(4)
                .step("fireballs fly", ctx -> {
                    var p = Minecraft.getInstance().player;
                    int balls = p.level().getEntitiesOfClass(SmallFireball.class, p.getBoundingBox().inflate(8)).size();
                    System.out.printf("[logos] incarnate skill: %d fireballs%n", balls);
                    if (balls != 3) throw new AssertionError("expected three fireballs, saw " + balls);
                })
                .screenshot("blaze_fireballs")
                .step("reset", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON));
    }
}
