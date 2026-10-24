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

/** turn, disperse and dir-of take this form; pos-of and move take a kept field: the new form operations in the editor. */
@LDLRegisterClient(name = "logos_form_ops", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FormOpsScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) { o.tags("logos").guiScale(2); }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("open a graph that steers its bodies", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, SpellPages.fitting(
                        "(spell :name \"转向\" :mana 100 (let $b (beam :life 6) (delay 1 (turn $b (dir-of caster)) (move $b (pos-of caster)) (disperse $b))))")))
                .awaitModularUI().ticks(10)
                .step("the new nodes are there and compile back", ctx -> {
                    var view = ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
                    var graph = (dev.logos.graph.SpellGraph) view.getGraph();
                    ctx.check("turn node", graph.getNodes().stream().anyMatch(n -> n instanceof RuneNodes.Turn turn && !turn.doc().contains("尚待补充")));
                    ctx.check("disperse node", graph.getNodes().stream().anyMatch(n -> n instanceof RuneNodes.Disperse));
                    ctx.check("dir-of node", graph.getNodes().stream().anyMatch(n -> n instanceof RuneNodes.DirOf));
                    var compiled = GraphCompiler.compile(graph);
                    ctx.check("graph compiles back", compiled.ok() && compiled.source().contains("(dir-of caster)") && compiled.source().contains("(disperse $y"));
                }).screenshot("form_ops")
                .closeScreen();
    }
}
