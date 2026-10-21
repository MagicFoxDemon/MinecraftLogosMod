package dev.logos.client.uitest;

import java.util.concurrent.CompletableFuture;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.alchemy.Process;
import dev.logos.alchemy.ProcessCard;
import dev.logos.block.AlchemyTableBlock;
import dev.logos.block.AlchemyTableBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Real-client look at sublimation, in Chinese: a lit beacon beside the table, the aludel subliming solar
 * amalgam into solar blossoms, and a settling tray turning firmament liquor into its stone; the bench,
 * the overview and the table in the world with the beacon's beam.
 * Run: {@code gradlew runClient -PldTest=logos_alchemy_sublimation}.
 */
@LDLRegisterClient(name = "logos_alchemy_sublimation", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class AlchemySublimationScenario implements UIScenario {
    private static final BlockPos MAIN = new BlockPos(0, 101, 0), BEACON = MAIN.north();
    private AlchemyTableBlockEntity table;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "alchemy").guiScale(2);
    }

    private static boolean reloaded(TestContext ctx) {
        CompletableFuture<Void> future = ctx.get("language_reload");
        return future.isDone() && !future.isCompletedExceptionally();
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("switch to Chinese", ctx -> {
                    var mc = Minecraft.getInstance();
                    ctx.put("original_language", mc.getLanguageManager().getSelected());
                    mc.getLanguageManager().setSelected("zh_cn");
                    ctx.put("language_reload", mc.reloadResourcePacks());
                })
                .waitUntil("Chinese resources loaded", AlchemySublimationScenario::reloaded)
                .setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .clearArea(MAIN.offset(0, 5, 0), 8)
                .fill(MAIN.offset(-4, -1, -4), MAIN.offset(4, -1, 4), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(MAIN.getX() + 1.0, MAIN.getY() + 1.5, MAIN.getZ() + 5, 180, 15)
                .server("a table beside a beacon on an iron pyramid", sc -> {
                    var level = sc.player().level();
                    for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) level.setBlock(BEACON.offset(x, -1, z), Blocks.IRON_BLOCK.defaultBlockState(), 3);
                    level.setBlock(BEACON, Blocks.BEACON.defaultBlockState(), 3);
                    BlockState main = LogosMod.ALCHEMY_TABLE.get().defaultBlockState().setValue(AlchemyTableBlock.FACING, Direction.SOUTH);
                    level.setBlock(MAIN, main, 3);
                    level.setBlock(AlchemyTableBlock.otherHalf(MAIN, main), main.setValue(AlchemyTableBlock.PART, AlchemyTableBlock.Part.SIDE), 3);
                    table = (AlchemyTableBlockEntity) level.getBlockEntity(MAIN);
                    table.setMana(400);
                })
                .waitUntilServer("the beacon is lit", sc -> table.hasStation(Process.SUBLIME))
                .server("lay a sublimation card and a settling card", sc -> {
                    ProcessCard sublime = table.addCard(Process.SUBLIME, 0, 20);
                    ProcessCard settle = table.addCard(Process.SETTLE, 220, 30);
                    try (Transaction tx = Transaction.openRoot()) {
                        sublime.inputs().insert(ItemResource.of(LogosMod.SOLAR_AMALGAM.get()), 4, tx);
                        settle.inputs().insert(ItemResource.of(LogosMod.FIRMAMENT_LIQUID.get()), 4, tx);
                        tx.commit();
                    }
                })
                .waitUntilServer("solar blossoms have sublimed", sc -> table.cards().get(0).outputs().getAmountAsInt(0) > 0)
                .awaitClientBlockEntity(MAIN)
                .ticks(10)
                .screenshot("sublimation_in_world")
                .server("open it", sc -> BlockUIMenuType.openUI((ServerPlayer) sc.player(), MAIN))
                .awaitModularUI()
                .ticks(10)
                .checkVisible("#card0")
                .checkVisible("#card1")
                .click("#tab_storage")
                .ticks(10)
                .screenshot("sublimation_bench")
                .click("#zoom_toggle")
                .ticks(10)
                .screenshot("sublimation_overview")
                .closeScreen()
                .step("restore language", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.getLanguageManager().setSelected(ctx.get("original_language"));
                    ctx.put("language_reload", mc.reloadResourcePacks());
                })
                .waitUntil("original language restored", AlchemySublimationScenario::reloaded);
    }
}
