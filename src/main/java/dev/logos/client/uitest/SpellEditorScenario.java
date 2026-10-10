package dev.logos.client.uitest;

import java.util.ArrayList;
import java.util.List;

import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
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
                .checkTextContains("#source", "(projectile")
                .screenshot("fireball_graph")
                .click("#inscribe")
                .waitUntilServer("the page on the table keeps the drawing", sc ->
                        sc.level().getBlockEntity(table) instanceof InscriptionTableBlockEntity t && t.page().has(LogosMod.SPELL_GRAPH.get()))
                .step("pick the block-magic template", ctx -> selectTemplate(ctx, "harvest"))
                .click("#load_template")
                .ticks(10)
                .checkTextContains("#status", "成立")
                .screenshot("harvest_graph")
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
