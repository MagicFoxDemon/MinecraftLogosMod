package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.block.ManaStorageTankBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Real-client look at mana storage tanks: the front row a quarter, half, three quarters and wholly full
 * (the fog standing higher), the back row condensing a shard, a crystal and a gemstone part grown; then
 * the screen of a tank growing a crystal.
 * Run: {@code gradlew runClient -PldTest=logos_mana_storage_tank}.
 */
@LDLRegisterClient(name = "logos_mana_storage_tank", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ManaStorageTankScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    /** The tank whose screen is opened: growing a crystal. */
    private static final BlockPos OPENED = ORIGIN.offset(2, 1, 2);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "mana").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(3, 4, 1), 7)
                .fill(ORIGIN.offset(-2, 0, -3), ORIGIN.offset(8, 0, 6), Blocks.SMOOTH_STONE.defaultBlockState())
                .server("set out the tanks", sc -> {
                    var level = sc.player().level();
                    double[] fills = {0.25, 0.5, 0.75, 1};
                    for (int i = 0; i < fills.length; i++) {
                        BlockPos pos = ORIGIN.offset(i * 2, 1, 0);
                        level.setBlock(pos, LogosMod.MANA_STORAGE_TANK.get().defaultBlockState(), 3);
                        if (level.getBlockEntity(pos) instanceof ManaStorageTankBlockEntity tank) tank.setMana(fills[i] * ManaStorageTankBlockEntity.MAX_MANA);
                    }
                    // Seed, seconds grown.
                    Object[][] growing = {{Items.AMETHYST_SHARD, 12}, {LogosMod.MANA_SHARD.get(), 18}, {LogosMod.MANA_CRYSTAL.get(), 180}};
                    for (int i = 0; i < growing.length; i++) {
                        BlockPos pos = ORIGIN.offset(i * 2, 1, 2);
                        level.setBlock(pos, LogosMod.MANA_STORAGE_TANK.get().defaultBlockState(), 3);
                        if (!(level.getBlockEntity(pos) instanceof ManaStorageTankBlockEntity tank)) continue;
                        tank.setMode(ManaStorageTankBlockEntity.Mode.CONDENSE);
                        tank.seed().set(0, ItemResource.of((Item) growing[i][0]), 1);
                        for (int sec = 0; sec < (int) growing[i][1]; sec++) {
                            tank.setMana(ManaStorageTankBlockEntity.MAX_MANA);
                            tank.work(level, pos);
                        }
                        tank.setMana(1500 + i * 1000);
                    }
                })
                .server("fly, so the camera stays where it is put", sc -> {
                    sc.player().getAbilities().flying = true;
                    sc.player().onUpdateAbilities();
                })
                .ticks(10)
                .teleportPlayer(ORIGIN.getX() + 3.5, ORIGIN.getY() + 1.9, ORIGIN.getZ() - 2.6, 0, 18)
                .ticks(40)
                .screenshot("filling_from_the_north")
                .teleportPlayer(ORIGIN.getX() + 2.5, ORIGIN.getY() + 1.9, ORIGIN.getZ() + 5.2, 180, 22)
                .ticks(20)
                .screenshot("condensing_from_the_south")
                .runCommand("time set midnight")
                .ticks(10)
                .screenshot("condensing_at_night")
                .server("open the tank growing a crystal", sc -> BlockUIMenuType.openUI((ServerPlayer) sc.player(), OPENED))
                .awaitModularUI()
                .ticks(25)
                .checkExists("#tank_seed")
                .checkTextContains("#tank_mana", "/ 5000")
                .screenshot("tank_screen")
                .closeScreen();
    }
}
