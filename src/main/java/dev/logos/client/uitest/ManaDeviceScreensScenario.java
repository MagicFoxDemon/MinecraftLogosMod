package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.block.AlchemyTableBlock;
import dev.logos.block.ManaCollectorBlock;
import dev.logos.block.ManaSpreaderBlock;
import dev.logos.block.ManaSpreaderBlockEntity;
import dev.logos.runtime.ManaField;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Real-client look at the screens of the mana spreader and the mana collector: the spreader's map of
 * the chunks around it while the field is still spreading out, a chunk's tooltip, and the collector's
 * intake gauge while it draws on the field.
 * Run: {@code gradlew runClient -PldTest=logos_mana_device_screens}.
 */
@LDLRegisterClient(name = "logos_mana_device_screens", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ManaDeviceScreensScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(8, 100, 8);
    private static final BlockPos SPREADER = ORIGIN.offset(-2, 1, 0), TABLE = ORIGIN.offset(1, 1, 0), COLLECTOR = TABLE.above();

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "mana_field").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .clearArea(ORIGIN.offset(0, 6, 0), 10)
                .fill(ORIGIN.offset(-7, 0, -7), ORIGIN.offset(7, 0, 7), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY() + 2.2, ORIGIN.getZ() + 5.5, 180, 20)
                .server("a spreader with fuel, a collector on a table, and a field still spreading out", sc -> {
                    ServerLevel level = (ServerLevel) sc.player().level();
                    level.setBlock(SPREADER, LogosMod.MANA_SPREADER.get().defaultBlockState().setValue(ManaSpreaderBlock.FACING, Direction.SOUTH), 3);
                    ((ManaSpreaderBlockEntity) level.getBlockEntity(SPREADER)).fuel().set(0, ItemResource.of(LogosMod.MANA_SHARD.get()), 8);
                    BlockState table = LogosMod.ALCHEMY_TABLE.get().defaultBlockState().setValue(AlchemyTableBlock.FACING, Direction.SOUTH);
                    level.setBlock(TABLE, table, 3);
                    level.setBlock(AlchemyTableBlock.otherHalf(TABLE, table), table.setValue(AlchemyTableBlock.PART, AlchemyTableBlock.Part.SIDE), 3);
                    level.setBlock(COLLECTOR, LogosMod.MANA_COLLECTOR.get().defaultBlockState().setValue(ManaCollectorBlock.FACING, Direction.DOWN), 3);
                    // A full chunk, and only a few seconds of spreading: its neighbours are still filling.
                    for (int i = 0; i < 4; i++) {
                        ManaField.pour(level, SPREADER, ManaField.CAP);
                        ManaField.work(level);
                    }
                    ManaField.pour(level, SPREADER, ManaField.CAP);
                })
                .ticks(45)
                .server("open the spreader", sc -> BlockUIMenuType.openUI((ServerPlayer) sc.player(), SPREADER))
                .awaitModularUI()
                .ticks(25)
                .checkExists("#chunk_map")
                .checkExists("#fuel")
                .check("the map was sent the chunks around", ctx -> ctx.ui().select("#chunk_12", UIElement.class).findFirst()
                        .map(e -> java.util.Arrays.stream(e.getStyle().tooltips().tooltips()).anyMatch(t -> t.getString().contains("200"))).orElse(false))
                .check("a chunk beside it is spreading or filling", ctx -> ctx.ui().select("#chunk_13", UIElement.class).findFirst()
                        .map(e -> e.getStyle().tooltips().tooltips().length >= 2).orElse(false))
                .screenshot("spreader_screen")
                .hover("#chunk_13")
                .ticks(5)
                .screenshot("spreader_chunk_tooltip")
                .closeScreen()
                .ticks(5)
                .server("open the collector", sc -> BlockUIMenuType.openUI((ServerPlayer) sc.player(), COLLECTOR))
                .awaitModularUI()
                .ticks(25)
                .checkExists("#intake_gauge")
                .checkTextContains("#collector_rate", "0.13")
                .checkTextContains("#collector_field", "/ 200")
                .screenshot("collector_screen")
                .ticks(7)
                .screenshot("collector_screen_later")
                .closeScreen();
    }
}
