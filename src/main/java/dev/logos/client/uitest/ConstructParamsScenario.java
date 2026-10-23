package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphPreview;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.ClientHooks;
import dev.logos.graph.RuneNode;
import dev.logos.graph.RuneNodes;
import dev.logos.item.SpellPages;
import net.minecraft.core.BlockPos;

/** A construct node shows only the sizes its shape uses: a shield its radius, a box its three sides. */
@LDLRegisterClient(name = "logos_construct_params", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ConstructParamsScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) { o.tags("logos").guiScale(2); }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("open a shield and a box", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, SpellPages.fitting(
                        "(spell :name \"炼成\" :mana 200 (construct :shape disk :radius 2 :sigil) (construct :shape box :width 2 :height 6 :depth 4 :at (ahead 6)))")))
                .awaitModularUI().ticks(10)
                .step("each shows its own sizes", ctx -> {
                    var view = ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
                    var graph = (dev.logos.graph.SpellGraph) view.getGraph();
                    var nodes = graph.getNodes().stream().filter(n -> n instanceof RuneNodes.Construct).map(n -> (RuneNodes.Construct) n).toList();
                    ctx.check("two constructs", nodes.size() == 2);
                    ctx.check("the shield hides width, height and depth", nodes.stream().anyMatch(n -> RuneNode.hidden(n.in(RuneNode.kw("width")))
                            && RuneNode.hidden(n.in(RuneNode.kw("depth"))) && !RuneNode.hidden(n.in(RuneNode.kw("radius")))));
                    ctx.check("the box hides radius only", nodes.stream().anyMatch(n -> RuneNode.hidden(n.in(RuneNode.kw("radius")))
                            && !RuneNode.hidden(n.in(RuneNode.kw("depth")))));
                }).screenshot("construct_params")
                .closeScreen();
    }
}
