package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.block.CopyTableBlockEntity;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/**
 * Real-client look at the copy table's model beside the inscription table it is styled after:
 * empty, then with an original on the stand and empty pages on the blotter, from the front, both
 * front corners and the back. Run: {@code gradlew runClient -PldTest=logos_copy_table_look -PldTestHeadless}.
 */
@LDLRegisterClient(name = "logos_copy_table_look", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class CopyTableLookScenario implements UIScenario {
    private static final BlockPos AT = new BlockPos(0, 101, 2);
    private CopyTableBlockEntity table;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(AT.offset(0, 3, 0), 6)
                .fill(AT.offset(-5, -1, -5), AT.offset(5, -1, 5), Blocks.SMOOTH_STONE.defaultBlockState())
                .server("place the copy table and the inscription table beside it", sc -> {
                    var level = sc.player().level();
                    level.setBlock(AT, LogosMod.COPY_TABLE.get().defaultBlockState(), 3);
                    level.setBlock(AT.west(2), LogosMod.INSCRIPTION_TABLE.get().defaultBlockState()
                            .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH), 3);
                    table = (CopyTableBlockEntity) level.getBlockEntity(AT);
                    sc.player().getAbilities().flying = true;
                    sc.player().onUpdateAbilities();
                })
                .ticks(10)
                .teleportPlayer(2.3, 101.38, 0.4, 40.6f, 24.3f)
                .ticks(20)
                .screenshot("copy_table_empty")
                .server("lay an original and empty pages", sc -> {
                    table.inputs().setItem(CopyTableBlockEntity.ORIGINAL,
                            SpellPages.inscribed(PageTier.ECHO, "(spell :name \"抄\" :mana 10 (mark caster #c))", null));
                    table.inputs().setItem(CopyTableBlockEntity.BLANK, new ItemStack(PageTier.CRYSTAL.blank(), 5));
                })
                .ticks(10)
                .screenshot("copy_table_front_left")
                .teleportPlayer(-1.2, 101.58, 0.3, -37.7f, 27.5f)
                .ticks(15)
                .screenshot("copy_table_front_right")
                .teleportPlayer(1.8, 101.38, 4.8, 150.5f, 25.3f)
                .ticks(15)
                .screenshot("copy_table_back")
                .teleportPlayer(-0.5, 101.68, -1.2, 0f, 23.4f)
                .ticks(15)
                .screenshot("beside_inscription_table");
    }
}
