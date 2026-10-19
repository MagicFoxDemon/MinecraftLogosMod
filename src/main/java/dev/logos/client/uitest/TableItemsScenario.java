package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** The inscription, copy and alchemy tables shown as their blocks in the hotbar, the hand, the inventory and on the ground. */
@LDLRegisterClient(name = "logos_table_items", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class TableItemsScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "items").guiScale(3);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 4, 3), 8)
                .fill(ORIGIN.offset(-6, 0, -6), ORIGIN.offset(6, 0, 10), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(0.5, 101, -1.5, 0, 35)
                .server("hand out the tables and drop one of each", sc -> {
                    var p = sc.player();
                    p.getInventory().clearContent();
                    p.getInventory().setItem(0, new ItemStack(LogosMod.ALCHEMY_TABLE_ITEM.get()));
                    p.getInventory().setItem(1, new ItemStack(LogosMod.INSCRIPTION_TABLE_ITEM.get()));
                    p.getInventory().setItem(2, new ItemStack(LogosMod.COPY_TABLE_ITEM.get()));
                    p.getInventory().setSelectedSlot(0);
                    var level = p.level();
                    ItemStack[] drops = {new ItemStack(LogosMod.ALCHEMY_TABLE_ITEM.get()),
                            new ItemStack(LogosMod.INSCRIPTION_TABLE_ITEM.get()), new ItemStack(LogosMod.COPY_TABLE_ITEM.get())};
                    for (int i = 0; i < drops.length; i++) {
                        ItemEntity e = new ItemEntity(level, -0.7 + i * 1.2, 101, 1.8, drops[i], 0, 0, 0);
                        e.setPickUpDelay(32767);
                        level.addFreshEntity(e);
                    }
                })
                .ticks(30)
                .screenshot("table_items_hand_and_ground")
                .step("open inventory", ctx -> Minecraft.getInstance().setScreen(new InventoryScreen(Minecraft.getInstance().player)))
                .awaitScreen(InventoryScreen.class)
                .ticks(3)
                .screenshot("table_items_inventory")
                .step("close inventory", ctx -> Minecraft.getInstance().setScreen(null));
    }
}
