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
import dev.logos.item.SpellPages;
import net.minecraft.core.BlockPos;

/** ☌ spot shows its shape and where dropdowns and its ground switch, and samples inside a kept field. */
@LDLRegisterClient(name = "logos_spot_rune", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class SpotRuneScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) { o.tags("logos").guiScale(2); }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("open a graph with two spots", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, SpellPages.fitting(
                        "(spell :name \"择位\" :mana 100 (let $f (field :life 4) (delay 1 (lightning (spot :in $f :where surface)) (blink (spot :radius 6 :shape disk :ground)))))")))
                .awaitModularUI().ticks(10)
                .step("spots keep their options and the field wire", ctx -> {
                    var view = ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
                    var graph = (dev.logos.graph.SpellGraph) view.getGraph();
                    var spots = graph.getNodes().stream().filter(n -> n instanceof RuneNodes.Spot).map(n -> (RuneNodes.Spot) n).toList();
                    ctx.check("two spot nodes", spots.size() == 2);
                    ctx.check("one samples the field surface", spots.stream().anyMatch(n -> "surface".equals(n.option(RuneNode.choice("where"), String.class)) && n.in(RuneNode.kw("in")).isConnected()));
                    ctx.check("one is a grounded disk", spots.stream().anyMatch(n -> "disk".equals(n.option(RuneNode.choice("shape"), String.class)) && Boolean.TRUE.equals(n.option(RuneNode.flag("ground"), Boolean.class))));
                    var compiled = GraphCompiler.compile(graph);
                    ctx.check("graph compiles back", compiled.ok() && compiled.source().contains(":where surface") && compiled.source().contains(":ground"));
                }).screenshot("spot_rune")
                .closeScreen();
    }
}
