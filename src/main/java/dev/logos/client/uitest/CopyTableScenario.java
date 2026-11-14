package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Real-client look at the copy table's screen: empty, with only the original, and with a copy ready
 * (the golden thread lit). Run: {@code gradlew runClient -PldTest=logos_copy_table -PldTestHeadless}.
 */
@LDLRegisterClient(name = "logos_copy_table", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class CopyTableScenario implements UIScenario {
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
                .clearArea(AT.offset(0, 3, 0), 5)
                .fill(AT.offset(-4, -1, -4), AT.offset(4, -1, 4), Blocks.SMOOTH_STONE.defaultBlockState())
                .server("place the table", sc -> {
                    sc.player().level().setBlock(AT, LogosMod.COPY_TABLE.get().defaultBlockState(), 3);
                    table = (CopyTableBlockEntity) sc.player().level().getBlockEntity(AT);
                })
                // The client builds the screen from its own copy of the table, so let it arrive first.
                .ticks(5)
                .server("open it", sc -> BlockUIMenuType.openUI((ServerPlayer) sc.player(), AT))
                .ticks(10)
                .screenshot("copy_table_empty")
                .server("lay the original", sc -> table.inputs().setItem(CopyTableBlockEntity.ORIGINAL,
                        SpellPages.inscribed(PageTier.ECHO, "(spell :name \"抄\" :mana 10 (mark caster #c))", null)))
                .ticks(5)
                .screenshot("copy_table_original")
                .server("add empty pages", sc -> table.inputs().setItem(CopyTableBlockEntity.BLANK, new ItemStack(PageTier.CRYSTAL.blank(), 5)))
                .ticks(5)
                .screenshot("copy_table_ready");
    }
}
