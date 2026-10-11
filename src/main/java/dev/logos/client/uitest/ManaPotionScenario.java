package dev.logos.client.uitest;

import java.math.BigInteger;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import net.minecraft.client.gui.screens.inventory.BrewingStandScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;

/** Preview the real mana potion item model inside the vanilla brewing stand screen. */
@LDLRegisterClient(name = "logos_mana_potion", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ManaPotionScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "mana_potion").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .fill(ORIGIN.offset(-3, 0, -3), ORIGIN.offset(3, 0, 3), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(0.5, 101, -1.5, 0, 20)
                .server("open the vanilla stand with three mana potion strengths", sc -> {
                    var player = sc.player();
                    var level = player.level();
                    level.setBlockAndUpdate(ORIGIN.above(), Blocks.BREWING_STAND.defaultBlockState());
                    BrewingStandBlockEntity stand = (BrewingStandBlockEntity) level.getBlockEntity(ORIGIN.above());
                    for (int slot = 0; slot < 3; slot++) {
                        ItemStack potion = new ItemStack(LogosMod.MANA_POTION.get());
                        potion.set(LogosMod.MANA_POTION_SHARDS.get(), BigInteger.valueOf(slot + 1));
                        stand.setItem(slot, potion);
                    }
                    player.getInventory().setItem(0, new ItemStack(LogosMod.TAINTED_MANA_SHARD.get(), 64));
                    player.getInventory().setItem(1, new ItemStack(Items.BLAZE_POWDER));
                    player.openMenu(stand);
                })
                .awaitScreen(BrewingStandScreen.class)
                .ticks(10)
                .step("hover the first potion", ctx -> ctx.input().moveTo((ctx.screen().width - 176) / 2f + 64, (ctx.screen().height - 166) / 2f + 59))
                .ticks(3)
                .screenshot("mana_potion_40")
                .step("hover the third potion", ctx -> ctx.input().moveTo((ctx.screen().width - 176) / 2f + 110, (ctx.screen().height - 166) / 2f + 59))
                .ticks(3)
                .screenshot("mana_potion_120");
    }
}
