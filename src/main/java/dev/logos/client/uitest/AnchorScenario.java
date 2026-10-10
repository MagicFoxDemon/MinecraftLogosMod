package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.client.ClientHooks;
import dev.logos.runtime.Anchors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Real-client check of anchors: the naming screen, the markers floating over set anchors while a
 * nail is in hand, and the anchor list. Run: {@code gradlew runClient -PldTest=logos_anchors}.
 */
@LDLRegisterClient(name = "logos_anchors", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class AnchorScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "anchors").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 5, 6), 8)
                .fill(ORIGIN.offset(-10, 0, -6), ORIGIN.offset(10, 0, 18), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() - 3.5, 0, 20)
                .server("set three anchors and hand a nail", sc -> {
                    var p = sc.player();
                    var level = p.level();
                    Anchors.set(p, "家", level, Vec3.atBottomCenterOf(ORIGIN.offset(-3, 1, 4)));
                    Anchors.set(p, "矿洞", level, Vec3.atBottomCenterOf(ORIGIN.offset(0, 1, 7)));
                    Anchors.set(p, "二楼", level, Vec3.atBottomCenterOf(ORIGIN.offset(4, 1, 5)));
                    p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.ANCHOR_NAIL.get(), 8));
                })
                .ticks(10)
                .screenshot("anchor_markers")
                .step("open the naming screen", ctx -> ClientHooks.openAnchorName(ORIGIN.offset(1, 0, 2), Direction.UP))
                .ticks(5)
                .screenshot("anchor_name_screen")
                .step("open the anchor list", ctx -> ClientHooks.openAnchorList())
                .ticks(5)
                .screenshot("anchor_list_screen");
    }
}
