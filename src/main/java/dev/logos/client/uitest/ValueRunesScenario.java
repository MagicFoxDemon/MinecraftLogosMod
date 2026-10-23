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

/** ⚖ round's choice keyword shows as a dropdown option beside clamp and mix, and compiles back to its word. */
@LDLRegisterClient(name = "logos_value_runes", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ValueRunesScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) { o.tags("logos").guiScale(2); }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("open a graph with round, clamp and mix", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO,
                        SpellPages.fitting("(spell :name \"取整\" :mana 20 (heal caster (round (clamp (mix 0 10 (random)) :max 8) :mode up)))")))
                .awaitModularUI().ticks(10)
                .step("the round node keeps its mode", ctx -> {
                    var view = ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
                    var graph = (dev.logos.graph.SpellGraph) view.getGraph();
                    var round = graph.getNodes().stream().filter(n -> n instanceof RuneNodes.Round).map(n -> (RuneNodes.Round) n).findFirst().orElseThrow();
                    ctx.check("mode option is up", "up".equals(round.option(RuneNode.choice("mode"), String.class)));
                    ctx.check("mode is not a wire port", round.in(RuneNode.kw("mode")) == null);
                    ctx.check("round has its descriptions", !round.doc().contains("尚待补充"));
                    var compiled = GraphCompiler.compile(graph);
                    ctx.check("graph compiles back to :mode up", compiled.ok() && compiled.source().contains(":mode up"));
                }).screenshot("value_runes")
                .closeScreen();
    }
}
