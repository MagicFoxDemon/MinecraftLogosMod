package dev.logos.client.uitest;

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
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Real-client look at the mana field: a spreader with fuel, a collector on an alchemy table, the air
 * of the chunk filled; the motes in it by day and at dusk, and the client told the field's level.
 * Run: {@code gradlew runClient -PldTest=logos_mana_field}.
 */
@LDLRegisterClient(name = "logos_mana_field", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ManaFieldScenario implements UIScenario {
    /** The middle of a chunk, so the scene sits in one chunk's air. */
    private static final BlockPos ORIGIN = new BlockPos(8, 100, 8);
    private static final BlockPos SPREADER = ORIGIN.offset(-2, 1, 0), TABLE = ORIGIN.offset(1, 1, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "mana_field").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 0), 10)
                .fill(ORIGIN.offset(-7, 0, -7), ORIGIN.offset(7, 0, 7), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY() + 2.2, ORIGIN.getZ() + 6.5, 180, 15)
                .server("place a spreader, and a collector on a table", sc -> {
                    ServerLevel level = (ServerLevel) sc.player().level();
                    level.setBlock(SPREADER, LogosMod.MANA_SPREADER.get().defaultBlockState().setValue(ManaSpreaderBlock.FACING, Direction.SOUTH), 3);
                    ((ManaSpreaderBlockEntity) level.getBlockEntity(SPREADER)).fuel().set(0, ItemResource.of(LogosMod.MANA_SHARD.get()), 4);
                    BlockState table = LogosMod.ALCHEMY_TABLE.get().defaultBlockState().setValue(AlchemyTableBlock.FACING, Direction.SOUTH);
                    level.setBlock(TABLE, table, 3);
                    level.setBlock(AlchemyTableBlock.otherHalf(TABLE, table), table.setValue(AlchemyTableBlock.PART, AlchemyTableBlock.Part.SIDE), 3);
                    level.setBlock(TABLE.above(), LogosMod.MANA_COLLECTOR.get().defaultBlockState().setValue(ManaCollectorBlock.FACING, Direction.DOWN), 3);
                    // A block of caput mortuum in front, to see it among them.
                    level.setBlock(ORIGIN.offset(-1, 1, 3), LogosMod.CAPUT_MORTUUM_BLOCK.get().defaultBlockState(), 3);
                    // Fill the air at once rather than wait ten seconds for the spreader.
                    for (int i = 0; i < 60; i++) {
                        ManaField.pour(level, SPREADER, ManaField.CAP);
                        ManaField.work(level);
                    }
                })
                .ticks(60)
                .check("the client knows the air is full", ctx -> ManaField.clientLevel(Minecraft.getInstance().level, ORIGIN) == ManaField.LEVELS)
                .check("the spreader and the collector are lit", ctx -> {
                    var level = Minecraft.getInstance().level;
                    return level.getBlockState(SPREADER).getValue(ManaSpreaderBlock.ACTIVE) && level.getBlockState(TABLE.above()).getValue(ManaSpreaderBlock.ACTIVE);
                })
                .screenshot("field_noon")
                .teleportPlayer(ORIGIN.getX() - 0.5, ORIGIN.getY() + 1.8, ORIGIN.getZ() + 2.8, 200, 20)
                .ticks(20)
                .screenshot("field_close")
                .runCommand("time set 13000")
                .ticks(60)
                .screenshot("field_dusk");
    }
}
