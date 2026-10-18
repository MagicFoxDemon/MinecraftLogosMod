package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.block.AlchemyTableBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Real-client look at where the alchemy table is drawn against the two blocks it occupies, in all
 * four facings: each stands on gold (its main half) and diamond (its side half), seen from straight
 * above and from the front, so a model drawn off its blocks shows the floor it should cover.
 * Run: {@code gradlew runClient -PldTest=logos_alchemy_table_footprint}.
 */
@LDLRegisterClient(name = "logos_alchemy_table_footprint", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class AlchemyTableFootprintScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "alchemy").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(4, 8, 4), 14)
                .fill(ORIGIN.offset(-4, 0, -4), ORIGIN.offset(12, 0, 12), Blocks.SMOOTH_STONE.defaultBlockState())
                .server("place a table in each facing on gold and diamond", sc -> {
                    var level = sc.player().level();
                    Direction[] facings = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
                    for (int i = 0; i < facings.length; i++) {
                        BlockPos main = ORIGIN.offset(i % 2 * 7, 1, i / 2 * 7);
                        BlockState state = LogosMod.ALCHEMY_TABLE.get().defaultBlockState().setValue(AlchemyTableBlock.FACING, facings[i]);
                        BlockPos side = AlchemyTableBlock.otherHalf(main, state);
                        level.setBlock(main.below(), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                        level.setBlock(side.below(), Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
                        level.setBlock(main, state, 3);
                        level.setBlock(side, state.setValue(AlchemyTableBlock.PART, AlchemyTableBlock.Part.SIDE), 3);
                    }
                })
                .server("fly, so the camera stays where it is put", sc -> {
                    sc.player().getAbilities().flying = true;
                    sc.player().onUpdateAbilities();
                })
                .ticks(10)
                .teleportPlayer(ORIGIN.getX() + 4.0, ORIGIN.getY() + 13, ORIGIN.getZ() + 4.0, 0, 90)
                .ticks(30)
                .screenshot("footprints_from_above")
                // The south-facing table (third), from in front at eye level.
                .teleportPlayer(ORIGIN.getX() + 1.0, ORIGIN.getY() + 2.6, ORIGIN.getZ() + 7 + 4.5, 180, 25)
                .ticks(15)
                .screenshot("south_table_from_front");
    }
}
