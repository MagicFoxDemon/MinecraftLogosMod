package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphPreview;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphView;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.ClientHooks;
import dev.logos.graph.GraphCompiler;
import dev.logos.graph.RuneNode;
import dev.logos.graph.SpellGraph;
import dev.logos.item.SpellPages;
import net.minecraft.core.BlockPos;

/** Math inputs, localized choices, documentation and library names in the actual workbench. */
@LDLRegisterClient(name = "logos_math_runes", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class MathRuneScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) { o.tags("logos").guiScale(2); }

    private static GraphView view(TestContext ctx) {
        return ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
    }

    private static RuneNode rune(TestContext ctx, String name) {
        return view(ctx).getGraph().getNodes().stream().filter(n -> n instanceof RuneNode r && r.word().equals(name))
                .map(n -> (RuneNode) n).findFirst().orElseThrow();
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("open five arithmetic runes", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, SpellPages.fitting(
                        "(spell :name \"数与位置\" :mana 20 (echo (lerp (round (random 8 :min 2 :kind \"integer\") :mode \"up\") (clamp 12 :min 0 :max 10) 0.5)) (echo (lerp-pos here (above here 10) 0.5)))")))
                .awaitModularUI().ticks(10)
                .step("typed ports and saved mode strings", ctx -> {
                    ctx.check("integer choice restored", "integer".equals(rune(ctx, "random").in(RuneNode.kw("kind")).getEmbeddedValue().getValue()));
                    ctx.check("rounding choice restored", "up".equals(rune(ctx, "round").in(RuneNode.kw("mode")).getEmbeddedValue().getValue()));
                    for (String name : new String[]{"random", "round", "clamp", "lerp", "lerp-pos"}) {
                        ctx.check(name + " has complete help", !rune(ctx, name).doc().contains("尚待补充"));
                    }
                    ctx.check("graph compiles", GraphCompiler.compile((SpellGraph) view(ctx).getGraph()).ok());
                    view(ctx).addSelected(rune(ctx, "lerp-pos").model());
                }).ticks(2).screenshot("math_runes")
                .step("change mode choices", ctx -> {
                    rune(ctx, "random").in(RuneNode.kw("kind")).getEmbeddedValue().setValue("real");
                    rune(ctx, "round").in(RuneNode.kw("mode")).getEmbeddedValue().setValue("down");
                    ((SpellGraph) view(ctx).getGraph()).invalidate();
                    var compiled = GraphCompiler.compile((SpellGraph) view(ctx).getGraph());
                    ctx.check("updated choices compile", compiled.ok() && compiled.source().contains("\"real\"") && compiled.source().contains("\"down\""));
                })
                .click("#tab_library").ticks(3)
                .step("find both kinds of blend", ctx -> ctx.ui().select("#spell_library_search", TextField.class).findFirst().orElseThrow().setValue("lerp", true))
                .ticks(3).screenshot("math_library")
                .closeScreen();
    }
}
