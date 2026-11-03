package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphPreview;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.ClientHooks;
import dev.logos.graph.GraphCompiler;
import dev.logos.graph.RuneNodes;
import dev.logos.item.SpellPages;
import net.minecraft.core.BlockPos;

/** 化身 sits among the sulfur (change) runes with its creature and time, and compiles back. */
@LDLRegisterClient(name = "logos_incarnate", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class IncarnateScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) { o.tags("logos").guiScale(2); }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("open an incarnating page", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, SpellPages.fitting(
                        "(spell :name \"化身\" :mana 50 (incarnate minecraft:wolf 60))")))
                .awaitModularUI().ticks(10)
                .step("the node is there and compiles back", ctx -> {
                    var view = ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
                    var graph = (dev.logos.graph.SpellGraph) view.getGraph();
                    ctx.check("incarnate node with its description", graph.getNodes().stream()
                            .anyMatch(n -> n instanceof RuneNodes.Incarnate e && e.category() == dev.logos.lang.Category.EFFECT && !e.doc().contains("尚待补充")));
                    var compiled = GraphCompiler.compile(graph);
                    ctx.check("graph compiles back", compiled.ok() && compiled.source().contains("(incarnate wolf 60"));
                }).screenshot("incarnate")
                .closeScreen();
    }
}
