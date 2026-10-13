package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** The seven first-tier wood wands: held, seen from the front, and their icons in the inventory. */
@LDLRegisterClient(name = "logos_wood_wands", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class WoodWandScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "wand").guiScale(3);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 0), 8)
                .fill(ORIGIN.offset(-6, 0, -6), ORIGIN.offset(6, 0, 6), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(0.5, 101, 0.5, 0, 10)
                .server("hand out the wood wands", sc -> {
                    var wands = LogosMod.WOOD_WANDS;
                    for (int i = 0; i < wands.size(); i++) sc.player().getInventory().setItem(i, new ItemStack(wands.get(i).get()));
                    sc.player().getInventory().setSelectedSlot(0);
                    sc.player().setItemInHand(InteractionHand.OFF_HAND, new ItemStack(LogosMod.WOOD_WANDS.get(6).get()));
                })
                .ticks(20)
                .step("look from the front", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT))
                .ticks(10)
                .screenshot("wood_wands_third_person")
                .step("back to first person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                .step("open inventory", ctx -> Minecraft.getInstance().setScreen(new InventoryScreen(Minecraft.getInstance().player)))
                .awaitScreen(InventoryScreen.class)
                .ticks(3)
                .screenshot("wood_wands_inventory");
    }
}
