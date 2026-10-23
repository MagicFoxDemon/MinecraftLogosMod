package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphPreview;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.ClientHooks;
import dev.logos.graph.GraphCompiler;
import dev.logos.graph.RuneNode;
import dev.logos.graph.RuneNodes;
import dev.logos.graph.SpecialNodes;
import dev.logos.item.SpellPages;
import net.minecraft.core.BlockPos;

/** ♄ switch draws one numbered branch port per branch plus on-other; ⚖ choose draws its value type and typed options. */
@LDLRegisterClient(name = "logos_switch_runes", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class SwitchRunesScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) { o.tags("logos").guiScale(2); }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("open a graph with switch and choose", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, SpellPages.fitting(
                        "(spell :name \"择路\" :mana 50 (switch (random 2 :whole) (do) (blink (choose i here (above here 2) :wrap))))")))
                .awaitModularUI().ticks(10)
                .step("branch ports and the value type survive", ctx -> {
                    var view = ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
                    var graph = (dev.logos.graph.SpellGraph) view.getGraph();
                    var sw = graph.getNodes().stream().filter(n -> n instanceof RuneNodes.Switch).map(n -> (RuneNodes.Switch) n).findFirst().orElseThrow();
                    ctx.check("two numbered branch ports", sw.out("branch0") != null && sw.out("branch1") != null && sw.out("branch2") == null);
                    ctx.check("on-other port", sw.out(RuneNode.event("on-other")) != null);
                    var choose = graph.getNodes().stream().filter(n -> n instanceof RuneNodes.Choose).map(n -> (RuneNodes.Choose) n).findFirst().orElseThrow();
                    ctx.check("choose holds places", choose.option(RuneNode.KIND, SpecialNodes.RememberKind.class) == SpecialNodes.RememberKind.POS);
                    var compiled = GraphCompiler.compile(graph);
                    ctx.check("graph compiles back", compiled.ok() && compiled.source().contains("(choose i"));
                }).screenshot("switch_runes")
                .closeScreen();
    }
}
