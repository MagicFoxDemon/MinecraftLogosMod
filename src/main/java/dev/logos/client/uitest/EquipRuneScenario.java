package dev.logos.client.uitest;

import org.joml.Vector2f;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphPreview;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.client.ClientHooks;
import dev.logos.graph.EnumValues;
import dev.logos.graph.GraphBuilder;
import dev.logos.graph.GraphCompiler;
import dev.logos.graph.RuneNode;
import dev.logos.graph.RuneNodes;
import dev.logos.item.SpellPages;
import dev.logos.lang.Category;
import dev.logos.lang.Compiler;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueOutput;

/** The equip rune uses the existing manuscript style and accepts a saved item catalogue connection. */
@LDLRegisterClient(name = "logos_equip_rune", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class EquipRuneScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) { o.tags("logos").guiScale(2); }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("open an item-backed equip graph", ctx -> {
                    String source = "(spell :name \"授装\" :mana 40 (equip (nearest :radius 6) &minecraft:iron_sword))";
                    var graph = GraphBuilder.build(Compiler.compile(source));
                    var equip = graph.getNodes().stream().filter(n -> n instanceof RuneNodes.Equip).map(n -> (RuneNodes.Equip) n).findFirst().orElseThrow();
                    var card = new EnumValues.Item();
                    card.selected("minecraft:stone");
                    graph.graphModel.createNodeModel(card, new Vector2f(500, 280));
                    graph.graphModel.createWire(equip.in("item"), card.out(RuneNode.OUT));
                    var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, Platform.getFrozenRegistry());
                    graph.graphModel.serialize(output);
                    var page = SpellPages.fitting(source);
                    page.set(LogosMod.SPELL_GRAPH.get(), output.buildResult());
                    ClientHooks.openTableEditor(BlockPos.ZERO, page);
                }).awaitModularUI().ticks(10)
                .step("saved item wire is restored", ctx -> {
                    var view = ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
                    var graph = (dev.logos.graph.SpellGraph) view.getGraph();
                    var equip = graph.getNodes().stream().filter(n -> n instanceof RuneNodes.Equip).map(n -> (RuneNodes.Equip) n).findFirst().orElseThrow();
                    ctx.check("sulfur/change family", equip.category() == Category.EFFECT);
                    ctx.check("item input is connected", equip.in("item").isConnected());
                    ctx.check("rune has material and target descriptions", !equip.doc().contains("尚待补充"));
                    var compiled = GraphCompiler.compile(graph);
                    ctx.check("catalogue item overrides inline ID", compiled.ok() && compiled.source().contains("&minecraft:stone"));
                }).screenshot("equip_item_card")
                .click("#tab_library").ticks(3)
                .step("find equip in the rune library", ctx -> ctx.ui().select("#spell_library_search", TextField.class).findFirst().orElseThrow().setValue("equip", true))
                .ticks(3)
                .step("equip is searchable", ctx -> ctx.check("one matching rune", ctx.ui().select(".spell-library-entry").filter(UIElement::isDisplayed).count() == 1))
                .screenshot("equip_library")
                .closeScreen();
    }
}
