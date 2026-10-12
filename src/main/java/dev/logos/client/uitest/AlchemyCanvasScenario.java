package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Real-client check of the alchemy table's canvas, driven as a player would: drag a fermentation
 * card from the palette onto the canvas, designate sugar, a mushroom and wheat from storage, route
 * the starter culture to the products and watch it be made; drag the card by its title; lay a
 * second card and take it off again.
 * Run: {@code gradlew runClient -PldTest=logos_alchemy_canvas}.
 */
@LDLRegisterClient(name = "logos_alchemy_canvas", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class AlchemyCanvasScenario implements UIScenario {
    private static final BlockPos MAIN = new BlockPos(0, 101, 0);
    private AlchemyTableBlockEntity table;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "alchemy").guiScale(2);
    }

    /** Picks {@code item} in card 0's "designate from storage" list, as clicking it would. */
    @SuppressWarnings("unchecked")
    private static void designate(TestContext ctx, Item item) {
        Selector<Item> s = ctx.el("#card0_designate").as(Selector.class);
        if (!s.getCandidates().contains(item)) {
            var t = ctx.clientBlockEntity(MAIN, AlchemyTableBlockEntity.class);
            StringBuilder held = new StringBuilder();
            for (int i = 0; i < t.items().size(); i++) held.append(t.items().getResource(i).getItem()).append(" x").append(t.items().getAmountAsInt(i)).append(", ");
            throw new AssertionError("the designate list lacks " + item + ": " + s.getCandidates() + "; client table holds " + held + " cards " + t.cards().size());
        }
        s.setValue(item, true);
    }

    private static int stored(AlchemyTableBlockEntity t, Item item) {
        int n = 0;
        for (int i = 0; i < t.productArea().size(); i++) if (t.productArea().getResource(i).is(item)) n += t.productArea().getAmountAsInt(i);
        return n;
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .clearArea(MAIN.offset(0, 5, 0), 8)
                .fill(MAIN.offset(-4, -1, -4), MAIN.offset(4, -1, 4), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(MAIN.getX() + 1.0, MAIN.getY() + 0.5, MAIN.getZ() + 3, 180, 20)
                .server("place a table with materials", sc -> {
                    var level = sc.player().level();
                    BlockState main = LogosMod.ALCHEMY_TABLE.get().defaultBlockState().setValue(AlchemyTableBlock.FACING, Direction.SOUTH);
                    level.setBlock(MAIN, main, 3);
                    level.setBlock(AlchemyTableBlock.otherHalf(MAIN, main), main.setValue(AlchemyTableBlock.PART, AlchemyTableBlock.Part.SIDE), 3);
                    table = (AlchemyTableBlockEntity) level.getBlockEntity(MAIN);
                    table.setMana(100);
                    try (Transaction tx = Transaction.openRoot()) {
                        for (Item item : new Item[] {Items.SUGAR, Items.RED_MUSHROOM, Items.WHEAT}) table.rawArea().insert(ItemResource.of(item), 2, tx);
                        tx.commit();
                    }
                })
                .awaitClientBlockEntity(MAIN)
                .ticks(5)
                .server("open it", sc -> BlockUIMenuType.openUI((ServerPlayer) sc.player(), MAIN))
                .awaitModularUI()
                .ticks(5)
                .checkHidden("#card0")
                .drag("#palette_ferment", "#canvas")
                .waitUntilServer("a fermentation card is laid", sc -> table.cards().size() == 1 && table.cards().get(0).process == Process.FERMENT)
                .ticks(5)
                .checkVisible("#card0")
                .checkHidden("#card1")
                // Nothing designated yet: the card says so (it looks once a second, so wait for it).
                .waitUntil("the idle card says why", ctx -> ctx.el("#card0_status").isVisible())
                .screenshot("canvas_idle_card")
                .step("designate sugar, a mushroom and wheat", ctx -> designate(ctx, Items.SUGAR))
                .waitUntilServer("sugar designated", sc -> table.cards().get(0).designated(0) == Items.SUGAR)
                .ticks(3)
                .step("designate a mushroom", ctx -> designate(ctx, Items.RED_MUSHROOM))
                .waitUntilServer("mushroom designated", sc -> table.cards().get(0).designated(1) == Items.RED_MUSHROOM)
                .ticks(3)
                .step("designate wheat", ctx -> designate(ctx, Items.WHEAT))
                .waitUntilServer("wheat designated", sc -> table.cards().get(0).designated(2) == Items.WHEAT)
                .ticks(5)
                .screenshot("canvas_designated")
                .step("route starter culture to the products", ctx -> {
                    @SuppressWarnings("unchecked")
                    Selector<Integer> route = ctx.el("#card0_route_starter_culture").as(Selector.class);
                    route.setValue(ProcessCard.PRODUCTS, true);
                })
                .waitUntilServer("the route is set", sc -> table.cards().get(0).route(LogosMod.STARTER_CULTURE.get()) == ProcessCard.PRODUCTS)
                .waitUntilServer("starter culture reaches the products", sc -> stored(table, LogosMod.STARTER_CULTURE.get()) >= 3)
                .ticks(5)
                .screenshot("canvas_made")
                .serverGet("card position", "before", sc -> table.cards().get(0).x() + "," + table.cards().get(0).y())
                .drag("#card0_title", "#canvas")
                .waitUntil("dragging the title moves the card", ctx -> {
                    var c = ctx.clientBlockEntity(MAIN, AlchemyTableBlockEntity.class).cards().get(0);
                    return !(c.x() + "," + c.y()).equals(ctx.get("before"));
                })
                .drag("#palette_distill", "#canvas")
                .waitUntilServer("a second card is laid", sc -> table.cards().size() == 2 && table.cards().get(1).process == Process.DISTILL)
                .ticks(5)
                .checkVisible("#card1")
                .screenshot("canvas_two_cards")
                .click("#card1_remove")
                .waitUntilServer("the second card is taken off", sc -> table.cards().size() == 1)
                .ticks(5)
                .checkHidden("#card1")
                .step("the screen has every slot it should", ctx -> {
                    if (!ctx.exists("#card0_in8") || !ctx.exists("#card3_out8")) throw new AssertionError("card slots are missing");
                    ItemStack held = ctx.player().containerMenu.getCarried();
                    if (!held.isEmpty()) throw new AssertionError("the cursor holds " + held);
                });
    }
}
