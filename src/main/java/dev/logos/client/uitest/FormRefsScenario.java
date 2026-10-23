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

/** 🜔 A field's 此形 output feeds life-of after it in the flow; inside its events the 此形 context node does. */
@LDLRegisterClient(name = "logos_form_refs", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FormRefsScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) { o.tags("logos").guiScale(2); }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("open a graph that keeps a field", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, SpellPages.fitting(
                        "(spell :name \"此形\" :mana 100 (let $f (field :life 4 (on-tick (heal caster (life-of form)))) (delay 1 (heal caster (life-of $f)))))")))
                .awaitModularUI().ticks(10)
                .step("the field's own output and this form are wired", ctx -> {
                    var view = ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
                    var graph = (dev.logos.graph.SpellGraph) view.getGraph();
                    var field = graph.getNodes().stream().filter(n -> n instanceof RuneNodes.Field).map(n -> (RuneNodes.Field) n).findFirst().orElseThrow();
                    ctx.check("field output is wired", field.out(RuneNode.YIELD) != null && field.out(RuneNode.YIELD).isConnected());
                    ctx.check("this form context node is present", graph.getNodes().stream().anyMatch(n -> n instanceof SpecialNodes.Form));
                    ctx.check("no remember node", graph.getNodes().stream().noneMatch(n -> n instanceof SpecialNodes.Remember));
                    var compiled = GraphCompiler.compile(graph);
                    ctx.check("graph compiles back", compiled.ok() && compiled.source().contains("(let $y") && compiled.source().contains("(life-of form)"));
                }).screenshot("form_refs")
                .closeScreen();
    }
}
