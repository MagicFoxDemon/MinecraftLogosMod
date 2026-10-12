package dev.logos.client.uitest;

import java.util.List;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.block.AlchemyTableBlock;
import dev.logos.block.AlchemyTableBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Real-client check of the alchemy table: seen from the front, full of mana with an empty tank, then
 * half and half, then out of mana with a full tank; finally its screen.
 * Run: {@code gradlew runClient -PldTest=logos_alchemy_table}.
 */
@LDLRegisterClient(name = "logos_alchemy_table", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class AlchemyTableScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    /** The main half; the table faces south, toward the player. */
    private static final BlockPos MAIN = ORIGIN.above();
    private AlchemyTableBlockEntity table;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "alchemy").guiScale(2);
    }

    private void fill(double mana, int water) {
        table.setMana(mana);
        table.clearTank();
        if (water > 0) table.tank().set(0, FluidResource.of(Fluids.WATER), water);
    }

    /** 640 set, plus five amethyst shards burnt (25 each); 3250 mB poured. */
    private static void expect(ProgressBar bar, float value) {
        if (Math.abs(bar.getValue() - value) > 0.5f) throw new AssertionError("a gauge shows " + bar.getValue() + ", not " + value);
        float drawn = bar.bar.getSizeWidth() / bar.barContainer.getSizeWidth();
        if (Math.abs(drawn - value / bar.getMaxValue()) > 0.05f) throw new AssertionError("a gauge at " + value + " is drawn " + drawn + " full");
        if (bar.bar.getSizeHeight() < 1) throw new AssertionError("a gauge's fill has no height");
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 0), 10)
                .fill(ORIGIN.offset(-6, 0, -6), ORIGIN.offset(6, 0, 6), Blocks.SMOOTH_STONE.defaultBlockState())
                // In front of the table, a little to the side half's side, looking at the seam.
                .teleportPlayer(MAIN.getX() + 1.0, MAIN.getY() + 1.1, MAIN.getZ() + 3.2, 180, 22)
                .server("place a table facing south", sc -> {
                    var level = sc.player().level();
                    BlockState main = LogosMod.ALCHEMY_TABLE.get().defaultBlockState().setValue(AlchemyTableBlock.FACING, Direction.SOUTH);
                    level.setBlock(MAIN, main, 3);
                    level.setBlock(AlchemyTableBlock.otherHalf(MAIN, main), main.setValue(AlchemyTableBlock.PART, AlchemyTableBlock.Part.SIDE), 3);
                    table = (AlchemyTableBlockEntity) level.getBlockEntity(MAIN);
                    fill(AlchemyTableBlockEntity.MAX_MANA, 0);
                })
                .ticks(30)
                .screenshot("table_full_mana_dry")
                .server("half and half", sc -> fill(AlchemyTableBlockEntity.MAX_MANA / 2.0, AlchemyTableBlockEntity.TANK_CAPACITY / 2))
                .ticks(15)
                .screenshot("table_half")
                .server("no mana, full tank", sc -> fill(0, AlchemyTableBlockEntity.TANK_CAPACITY))
                .ticks(15)
                .screenshot("table_empty_mana_full_tank")
                .teleportPlayer(MAIN.getX() + 3.6, MAIN.getY() + 1.6, MAIN.getZ() - 2.4, 50, 30)
                .ticks(10)
                .screenshot("table_from_behind")
                .server("open its screen", sc -> {
                    fill(640, 3250);
                    table.items().set(0, ItemResource.of(Items.SUGAR), 12);
                    table.items().set(1, ItemResource.of(Items.WHEAT), 20);
                    table.items().set(AlchemyTableBlockEntity.RAW_SLOTS, ItemResource.of(LogosMod.STARTER_CULTURE.get()), 3);
                    table.fuel().set(0, ItemResource.of(Items.AMETHYST_SHARD), 5);
                    BlockUIMenuType.openUI((ServerPlayer) sc.player(), MAIN);
                })
                .ticks(15)
                .step("gauges show the pool and the tank", ctx -> {
                    List<ProgressBar> bars = List.of(ctx.el("#mana_gauge").as(ProgressBar.class), ctx.el("#fluid_gauge").as(ProgressBar.class));
                    for (ProgressBar b : bars) System.out.printf("[logos] gauge %.0f of %.0f, fill %.0fx%.0f of %.0fx%.0f px%n", b.getValue(), b.getMaxValue(), b.bar.getSizeWidth(), b.bar.getSizeHeight(), b.barContainer.getSizeWidth(), b.barContainer.getSizeHeight());
                    expect(bars.get(0), 765);
                    expect(bars.get(1), 3250);
                })
                .screenshot("table_screen")
                .click("#run_switch")
                .waitUntilServer("the switch stops the table", sc -> !table.enabled())
                .ticks(5)
                .checkTextContains("#run_state", "○")
                .click("#run_switch")
                .waitUntilServer("the switch starts it again", sc -> table.enabled());
    }
}
