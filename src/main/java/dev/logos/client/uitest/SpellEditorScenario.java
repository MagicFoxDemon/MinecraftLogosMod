package dev.logos.client.uitest;

import java.util.ArrayList;
import java.util.List;

import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphPreview;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.block.InscriptionTableBlockEntity;
import dev.logos.client.ClientHooks;
import dev.logos.client.SpellWheelScreen;
import dev.logos.item.SpellPages;
import dev.logos.lang.Examples;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * Real-client UI test: the workbench opened from an inscription table (live compile, write the
 * page through a C2S round trip, switch templates), the wand's page slots and the spell wheel.
 * Screenshots land in {@code build/ldlib2-uitest/screenshots}. Run: {@code gradlew runClient -PldTest=group:logos}.
 */
@LDLRegisterClient(name = "logos_spell_editor", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class SpellEditorScenario implements UIScenario {
    /** Chosen next to wherever the test player stands (set by the first server step). */
    private static BlockPos table = BlockPos.ZERO;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos").guiScale(2);
    }

    @SuppressWarnings("unchecked")
    private static void selectTemplate(TestContext ctx, String id) {
        ctx.ui().select("#templates", Selector.class).findFirst().ifPresent(sel -> ((Selector<Object>) sel).setSelected(id, true));
    }

    /** The map must stay in the canvas corner after loading another graph, without the tool panels. */
    private static void checkNavigation(TestContext ctx) {
        GraphPreview map = ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow();
        var view = map.graphView;
        ctx.check("variable library is not mounted", view.blackboard.getParent() == null);
        ctx.check("property inspector is not mounted", view.inspector.getParent() == null);
        ctx.check("navigation has no panel wrapper", map.getParent() == view.canvas);
        ctx.check("navigation is a small square", Math.abs(map.getSizeWidth() - 110) < 1
                && Math.abs(map.getSizeHeight() - 110) < 1);
        ctx.check("navigation is anchored at the bottom right", Math.abs(map.getPositionX() + map.getSizeWidth()
                - view.canvas.getPositionX() - view.canvas.getSizeWidth() + 8) < 1
                && Math.abs(map.getPositionY() + map.getSizeHeight()
                - view.canvas.getPositionY() - view.canvas.getSizeHeight() + 8) < 1);
        UIElement background = ctx.ui().select("#spell_editor_background", UIElement.class).findFirst().orElseThrow();
        var window = Minecraft.getInstance().getWindow();
        ctx.check("parchment covers the current window", Math.abs(background.getPositionX()) < 1
                && Math.abs(background.getPositionY()) < 1
                && Math.abs(background.getSizeWidth() - window.getGuiScaledWidth()) < 1
                && Math.abs(background.getSizeHeight() - window.getGuiScaledHeight()) < 1);
    }

    @Override
    public void define(ScenarioBuilder s) {
        ItemStack wand = new ItemStack(LogosMod.MASTER_WAND.get());
        List<ItemStack> pages = new ArrayList<>();
        for (String src : Examples.ALL.values()) pages.add(SpellPages.fitting(src));
        wand.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(pages.subList(0, 9)));
        s.server("place a table with a fireball page next to the player", sc -> {
                    table = sc.player().blockPosition().offset(0, 0, 2);
                    sc.level().setBlockAndUpdate(table, LogosMod.INSCRIPTION_TABLE.get().defaultBlockState());
                    if (sc.level().getBlockEntity(table) instanceof InscriptionTableBlockEntity t) {
                        t.setPage(SpellPages.inscribed(Examples.ALL.get("fireball"), null));
                    }
                    // a bare table turned sideways, to check facing and the empty drawing board
                    sc.level().setBlockAndUpdate(table.offset(2, 0, 1), LogosMod.INSCRIPTION_TABLE.get().defaultBlockState()
                            .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, net.minecraft.core.Direction.WEST));
                })
                .waitUntil("the client sees the page on the table", ctx ->
                        ctx.level().getBlockEntity(table) instanceof InscriptionTableBlockEntity t && !t.page().isEmpty())
                .step("look at the tables", ctx -> { ctx.player().setYRot(-20); ctx.player().setXRot(35); })
                .ticks(20)
                .screenshot("table_in_world")
                .ticks(5)
                .step("open the workbench on the table's page", ctx -> ClientHooks.openTableEditor(table,
                        ctx.level().getBlockEntity(table) instanceof InscriptionTableBlockEntity t ? t.page().copy() : ItemStack.EMPTY))
                .awaitModularUI()
                .ticks(10)
                .checkTextContains("#status", "成立")
                .checkTextContains("#overview", "意图")
                .step("check the standalone navigation", SpellEditorScenario::checkNavigation)
                .screenshot("fireball_graph")
                .step("click the navigation away from its center", ctx -> {
                    GraphPreview map = ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow();
                    ctx.put("before_map_x", map.graphView.graphView.getOffsetX());
                    ctx.put("before_map_y", map.graphView.graphView.getOffsetY());
                    float x = map.getContentX() + map.getContentWidth() * 0.8f;
                    float y = map.getContentY() + map.getContentHeight() * 0.8f;
                    ctx.input().mouseDown(x, y, 0);
                })
                .ticks(2)
                .step("check navigation pans and drag it", ctx -> {
                    GraphPreview map = ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow();
                    float beforeX = ctx.get("before_map_x");
                    float beforeY = ctx.get("before_map_y");
                    ctx.check("clicking the map pans the canvas", Math.abs(map.graphView.graphView.getOffsetX() - beforeX) > 1
                            || Math.abs(map.graphView.graphView.getOffsetY() - beforeY) > 1);
                    ctx.put("before_map_x", map.graphView.graphView.getOffsetX());
                    ctx.put("before_map_y", map.graphView.graphView.getOffsetY());
                    ctx.input().dragTo(map.getContentX() + map.getContentWidth() * 0.2f,
                            map.getContentY() + map.getContentHeight() * 0.2f, 0);
                })
                .ticks(2)
                .step("check navigation drag and frame the graph again", ctx -> {
                    GraphPreview map = ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow();
                    ctx.input().mouseUp(map.getContentX() + map.getContentWidth() * 0.2f,
                            map.getContentY() + map.getContentHeight() * 0.2f, 0);
                    float beforeX = ctx.get("before_map_x");
                    float beforeY = ctx.get("before_map_y");
                    ctx.check("dragging the map pans the canvas", Math.abs(map.graphView.graphView.getOffsetX() - beforeX) > 1
                            || Math.abs(map.graphView.graphView.getOffsetY() - beforeY) > 1);
                    checkNavigation(ctx);
                    map.graphView.fitGraphChildren();
                })
                .click("#inscribe")
                .waitUntilServer("the page on the table keeps the drawing", sc ->
                        sc.level().getBlockEntity(table) instanceof InscriptionTableBlockEntity t && t.page().has(LogosMod.SPELL_GRAPH.get()))
                .step("pick the block-magic template", ctx -> selectTemplate(ctx, "harvest"))
                .click("#load_template")
                .ticks(10)
                .checkTextContains("#status", "成立")
                .step("check navigation after switching templates", SpellEditorScenario::checkNavigation)
                .screenshot("harvest_graph")
                .step("resize the open editor to a taller window", ctx -> {
                    var window = Minecraft.getInstance().getWindow();
                    ctx.put("original_width", window.getScreenWidth());
                    ctx.put("original_height", window.getScreenHeight());
                    window.setWindowed(1024, 768);
                })
                .ticks(10)
                .step("check live resize to 4 by 3", ctx -> {
                    ctx.check("window changed to 1024 by 768", Minecraft.getInstance().getWindow().getScreenWidth() == 1024
                            && Minecraft.getInstance().getWindow().getScreenHeight() == 768);
                    checkNavigation(ctx);
                })
                .screenshot("parchment_1024x768")
                .step("resize the open editor to an ultrawide window", ctx -> Minecraft.getInstance().getWindow().setWindowed(1920, 720))
                .ticks(10)
                .step("check live resize to ultrawide", ctx -> {
                    ctx.check("window changed to 1920 by 720", Minecraft.getInstance().getWindow().getScreenWidth() == 1920
                            && Minecraft.getInstance().getWindow().getScreenHeight() == 720);
                    checkNavigation(ctx);
                })
                .screenshot("parchment_1920x720")
                .step("restore the original window size", ctx -> Minecraft.getInstance().getWindow()
                        .setWindowed(ctx.get("original_width"), ctx.get("original_height")))
                .ticks(10)
                .step("pick the proof-chain template", ctx -> selectTemplate(ctx, "sever"))
                .click("#load_template")
                .ticks(10)
                .checkTextContains("#status", "已证明")
                .screenshot("sever_graph")
                .closeScreen()
                .setHeldItem(wand)
                .ticks(5)
                .server("open the wand's page slots", sc -> HeldItemUIMenuType.openUI(sc.player(), InteractionHand.MAIN_HAND))
                .awaitModularUI()
                .ticks(5)
                .screenshot("wand_slots")
                .closeScreen()
                .ticks(3)
                .step("open the spell wheel", ctx -> SpellWheelScreen.tryOpen())
                .ticks(5)
                .hoverAt(480, 100)
                .ticks(2)
                .screenshot("spell_wheel")
                .closeScreen();
    }
}
