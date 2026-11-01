package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerScreen;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.runtime.Possession;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Real-client check of a possessed creature's inventory: inside a villager carrying wheat and
 * holding a hoe, the inventory key opens its equipment and pockets above the player's own.
 * Run: {@code gradlew runClient -PldTest=logos_possess_inventory}.
 */
@LDLRegisterClient(name = "logos_possess_inventory", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class PossessInventoryScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "possess").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon")
                .clearArea(ORIGIN.offset(0, 6, 4), 8)
                .fill(ORIGIN.offset(-8, 0, -8), ORIGIN.offset(8, 0, 10), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 0.5, 0, 10)
                .server("go into a villager with things on it", sc -> {
                    var p = sc.player();
                    var villager = EntityType.VILLAGER.create(p.level(), EntitySpawnReason.TRIGGERED);
                    villager.snapTo(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 3.5, 0, 0);
                    villager.getInventory().setItem(0, new ItemStack(Items.WHEAT, 12));
                    villager.getInventory().setItem(1, new ItemStack(Items.CARROT, 5));
                    villager.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_HOE));
                    p.level().addFreshEntity(villager);
                    if (!Possession.start(p, villager, 60)) throw new AssertionError("the possession did not start");
                })
                .ticks(10)
                .step("press the inventory key", ctx -> KeyMapping.click(Minecraft.getInstance().options.keyInventory.getKey()))
                .ticks(10)
                .step("its inventory is open", ctx -> {
                    var mc = Minecraft.getInstance();
                    if (!(mc.screen instanceof ModularUIContainerScreen screen) || screen.getMenu().getType() != LogosMod.POSSESSED_INVENTORY.get()) {
                        throw new AssertionError("the creature's inventory did not open: " + mc.screen);
                    }
                    var slots = screen.getMenu().slots;
                    System.out.printf("[logos] possessed inventory: %d slots%n", slots.size());
                    // Six equipment slots, eight pockets, the player's thirty-six.
                    if (slots.size() != 6 + 8 + 36) throw new AssertionError(slots.size() + " slots");
                    if (!slots.get(4).getItem().is(Items.IRON_HOE)) throw new AssertionError("the hoe is not in the main hand slot: " + slots.get(4).getItem());
                    if (!slots.get(6).getItem().is(Items.WHEAT)) throw new AssertionError("the wheat is not in the first pocket: " + slots.get(6).getItem());
                })
                .screenshot("inventory")
                .closeScreen();
    }
}
