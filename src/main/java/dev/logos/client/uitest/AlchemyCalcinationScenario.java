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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Real-client look at the calcining furnace, in Chinese: a blast furnace beside the table, a
 * calcination card burning raw iron next to an alembic for comparison, on the whole bench and in
 * the overview.
 * Run: {@code gradlew runClient -PldTest=logos_alchemy_calcination}.
 */
@LDLRegisterClient(name = "logos_alchemy_calcination", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class AlchemyCalcinationScenario implements UIScenario {
    private static final BlockPos MAIN = new BlockPos(0, 101, 0);
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
                .waitUntil("Chinese resources loaded", AlchemyCalcinationScenario::reloaded)
                .setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .clearArea(MAIN.offset(0, 5, 0), 8)
                .fill(MAIN.offset(-4, -1, -4), MAIN.offset(4, -1, 4), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(MAIN.getX() + 1.0, MAIN.getY() + 0.5, MAIN.getZ() + 3, 180, 20)
                .server("a table with a blast furnace beside it, calcining raw iron", sc -> {
                    var level = sc.player().level();
                    BlockState main = LogosMod.ALCHEMY_TABLE.get().defaultBlockState().setValue(AlchemyTableBlock.FACING, Direction.SOUTH);
                    level.setBlock(MAIN, main, 3);
                    level.setBlock(AlchemyTableBlock.otherHalf(MAIN, main), main.setValue(AlchemyTableBlock.PART, AlchemyTableBlock.Part.SIDE), 3);
                    level.setBlock(MAIN.north(), Blocks.BLAST_FURNACE.defaultBlockState(), 3);
                    table = (AlchemyTableBlockEntity) level.getBlockEntity(MAIN);
                    table.setMana(400);
                    ProcessCard calcine = table.addCard(Process.CALCINE, 0, 20);
                    table.addCard(Process.DISTILL, 200, 30);
                    try (Transaction tx = Transaction.openRoot()) {
                        calcine.inputs().insert(ItemResource.of(Items.RAW_IRON), 16, tx);
                        tx.commit();
                    }
                })
                .waitUntilServer("the furnace has made iron", sc -> table.cards().get(0).outputs().getAmountAsInt(0) > 0)
                .awaitClientBlockEntity(MAIN)
                .ticks(5)
                .server("open it", sc -> BlockUIMenuType.openUI((ServerPlayer) sc.player(), MAIN))
                .awaitModularUI()
                .ticks(10)
                .checkVisible("#card0")
                .click("#tab_storage")
                .ticks(10)
                .screenshot("calcination_bench")
                .click("#zoom_toggle")
                .ticks(10)
                .screenshot("calcination_overview")
                .closeScreen()
                .step("restore language", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.getLanguageManager().setSelected(ctx.get("original_language"));
                    ctx.put("language_reload", mc.reloadResourcePacks());
                })
                .waitUntil("original language restored", AlchemyCalcinationScenario::reloaded);
    }
}
