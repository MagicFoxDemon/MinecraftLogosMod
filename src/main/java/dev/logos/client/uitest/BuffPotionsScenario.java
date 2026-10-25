package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.alchemy.BuffPotions;
import dev.logos.block.CultivationJarBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Real-client look at the first Logos potions: the five element herbs ripe and half grown in jars, then
 * an inventory of their plants and harvests, the 流魔 potions in the crystal flask and the element
 * potions in the diamond bottle, with a vanilla potion beside them for comparison.
 * Run: {@code gradlew runClient -PldTest=logos_buff_potions}.
 */
@LDLRegisterClient(name = "logos_buff_potions", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class BuffPotionsScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "alchemy", "items").guiScale(4);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(4, 4, 1), 8)
                .fill(ORIGIN.offset(-2, 0, -3), ORIGIN.offset(10, 0, 5), Blocks.SMOOTH_STONE.defaultBlockState())
                .server("plant the herbs, ripe in front and half grown behind", sc -> {
                    var level = sc.player().level();
                    LogosMod.JarHerb[] herbs = LogosMod.JarHerb.values();
                    for (int i = 0; i < herbs.length; i++) {
                        for (int row = 0; row < 2; row++) {
                            BlockPos pos = ORIGIN.offset(i * 2, 1, row * 2);
                            level.setBlock(pos, LogosMod.CULTIVATION_JAR.get().defaultBlockState(), 3);
                            if (!(level.getBlockEntity(pos) instanceof CultivationJarBlockEntity jar)) continue;
                            jar.plant(new ItemStack(herbs[i].plant.get()));
                            for (int g = 0; g < (row == 0 ? 3 : 2); g++) jar.grow();
                        }
                    }
                })
                .server("fly, so the camera stays where it is put", sc -> {
                    sc.player().getAbilities().flying = true;
                    sc.player().onUpdateAbilities();
                })
                .ticks(10)
                .teleportPlayer(ORIGIN.getX() + 4.5, ORIGIN.getY() + 2.2, ORIGIN.getZ() - 3.4, 0, 24)
                .ticks(30)
                .screenshot("herbs_in_jars")
                .setGameMode(GameType.SURVIVAL)
                .server("fill the inventory", sc -> {
                    var inv = sc.player().getInventory();
                    inv.clearContent();
                    int slot = 9;
                    for (LogosMod.JarHerb herb : LogosMod.JarHerb.values()) {
                        inv.setItem(slot++, new ItemStack(herb.plant.get()));
                        if (herb.harvest != herb.plant) inv.setItem(slot++, new ItemStack(herb.harvest.get()));
                    }
                    slot = 18;
                    for (var potion : java.util.List.of(BuffPotions.MANA_FLOW_POTION, BuffPotions.STRONG_MANA_FLOW_POTION, BuffPotions.GREATER_MANA_FLOW_POTION))
                        inv.setItem(slot++, PotionContents.createItemStack(Items.POTION, potion));
                    inv.setItem(slot++, PotionContents.createItemStack(Items.POTION, net.minecraft.world.item.alchemy.Potions.SWIFTNESS));
                    slot = 27;
                    for (BuffPotions.ElementBuff e : BuffPotions.ElementBuff.values()) {
                        inv.setItem(slot, PotionContents.createItemStack(Items.POTION, e.potion));
                        inv.setItem(slot - 27, PotionContents.createItemStack(Items.POTION, e.strong));
                        slot++;
                    }
                    inv.setItem(slot, PotionContents.createItemStack(Items.SPLASH_POTION, BuffPotions.ElementBuff.FIRE.potion));
                })
                .ticks(5)
                .step("open the inventory", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.setScreen(new InventoryScreen(mc.player));
                })
                .awaitScreen(InventoryScreen.class)
                .ticks(5)
                .screenshot("potions_and_herbs")
                .step("close", ctx -> Minecraft.getInstance().setScreen(null));
    }
}
