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
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Real-client look at the alchemy table as one page, in Chinese: all four apparatus on the bench,
 * the crock piped through glass into the alembic, the settling tray piped through brass into the
 * product crate, with the storage ledger open and then folded away.
 * Run: {@code gradlew runClient -PldTest=logos_alchemy_apparatus}.
 */
@LDLRegisterClient(name = "logos_alchemy_apparatus", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class AlchemyApparatusScenario implements UIScenario {
    private static final BlockPos MAIN = new BlockPos(0, 101, 0);
    private AlchemyTableBlockEntity table;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "alchemy").guiScale(2);
    }

    private static float width(TestContext ctx, String id) {
        return ctx.el(id).as(com.lowdragmc.lowdraglib2.gui.ui.UIElement.class).getSizeWidth();
    }

    private static boolean reloaded(TestContext ctx) {
        CompletableFuture<Void> future = ctx.get("language_reload");
        return future.isDone() && !future.isCompletedExceptionally();
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("read the page in Chinese", ctx -> {
                    var mc = Minecraft.getInstance();
                    ctx.put("original_language", mc.getLanguageManager().getSelected());
                    mc.getLanguageManager().setSelected("zh_cn");
                    ctx.put("language_reload", mc.reloadResourcePacks());
                })
                .waitUntil("Chinese resources loaded", AlchemyApparatusScenario::reloaded)
                .setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .clearArea(MAIN.offset(0, 5, 0), 8)
                .fill(MAIN.offset(-4, -1, -4), MAIN.offset(4, -1, 4), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(MAIN.getX() + 1.0, MAIN.getY() + 0.5, MAIN.getZ() + 3, 180, 20)
                .server("lay out all four apparatus at work", sc -> {
                    var level = sc.player().level();
                    BlockState main = LogosMod.ALCHEMY_TABLE.get().defaultBlockState().setValue(AlchemyTableBlock.FACING, Direction.SOUTH);
                    level.setBlock(MAIN, main, 3);
                    level.setBlock(AlchemyTableBlock.otherHalf(MAIN, main), main.setValue(AlchemyTableBlock.PART, AlchemyTableBlock.Part.SIDE), 3);
                    table = (AlchemyTableBlockEntity) level.getBlockEntity(MAIN);
                    table.setMana(400);
                    ProcessCard ferment = table.addCard(Process.FERMENT, 0, 6);
                    table.addCard(Process.DISTILL, 200, 30);
                    table.addCard(Process.DISSOLVE, 0, 200);
                    ProcessCard settle = table.addCard(Process.SETTLE, 200, 220);
                    try (Transaction tx = Transaction.openRoot()) {
                        ferment.inputs().insert(ItemResource.of(Items.SUGAR), 4, tx);
                        ferment.inputs().insert(ItemResource.of(Items.RED_MUSHROOM), 4, tx);
                        ferment.inputs().insert(ItemResource.of(Items.WHEAT), 4, tx);
                        settle.inputs().insert(ItemResource.of(PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS)), 1, tx);
                        settle.inputs().insert(ItemResource.of(PotionContents.createItemStack(Items.POTION, Potions.POISON)), 1, tx);
                        table.rawArea().insert(ItemResource.of(Items.GLOWSTONE_DUST), 12, tx);
                        table.rawArea().insert(ItemResource.of(Items.REDSTONE), 20, tx);
                        tx.commit();
                    }
                })
                .waitUntilServer("the crock and the tray have made something", sc ->
                        !table.cards().get(0).made().isEmpty() && table.cards().get(3).made().size() == 2)
                .server("pipe the crock into the alembic and the tray into the crate", sc -> {
                    table.cards().get(0).made().forEach(p -> table.cards().get(0).setRoute(p, 1));
                    table.cards().get(3).made().forEach(p -> table.cards().get(3).setRoute(p, ProcessCard.PRODUCTS));
                })
                .awaitClientBlockEntity(MAIN)
                .ticks(5)
                .server("open it", sc -> BlockUIMenuType.openUI((ServerPlayer) sc.player(), MAIN))
                .awaitModularUI()
                .ticks(10)
                .checkVisible("#storage_page")
                .checkVisible("#card3")
                .screenshot("apparatus_with_ledger")
                .click("#tab_storage")
                .ticks(10)
                .checkHidden("#storage_page")
                .screenshot("apparatus_whole_bench")
                // The overview: half the board, each card a small apparatus with its materials and products.
                .click("#zoom_toggle")
                .ticks(10)
                .step("the board and its cards are drawn small", ctx -> {
                    ctx.check("the board is half size", Math.abs(width(ctx, "#canvas") - 300) < 1);
                    ctx.check("cards are compact", Math.abs(width(ctx, "#card0") - 72) < 1);
                    ctx.check("the tray's two powders can still be piped from", ctx.exists("#card3_mini_potion_powder") && ctx.exists("#card3_mini_potion_powder_2"));
                })
                .screenshot("apparatus_overview")
                .serverGet("dissolve card position", "before", sc -> table.cards().get(2).x() + "," + table.cards().get(2).y())
                .drag("#card2_title", "#canvas_view")
                .waitUntil("moving a card in the overview moves it on the table", ctx -> {
                    var c = ctx.clientBlockEntity(MAIN, AlchemyTableBlockEntity.class).cards().get(2);
                    return !(c.x() + "," + c.y()).equals(ctx.get("before"));
                })
                .checkServer("in whole-board places", sc -> table.cards().get(2).x() > 150)
                .doubleClick("#card1")
                .ticks(10)
                .step("double-clicking a card comes back close", ctx ->
                        ctx.check("the board is whole again", Math.abs(width(ctx, "#canvas") - dev.logos.block.AlchemyTableUI.CANVAS_W) < 1))
                .closeScreen()
                .step("restore language", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.getLanguageManager().setSelected(ctx.get("original_language"));
                    ctx.put("language_reload", mc.reloadResourcePacks());
                })
                .waitUntil("original language restored", AlchemyApparatusScenario::reloaded);
    }
}
