package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphPreview;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphView;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.ClientHooks;
import dev.logos.graph.LogosNode;
import dev.logos.item.SpellPages;
import dev.logos.lang.Examples;
import net.minecraft.core.BlockPos;

/** Selection help follows the canvas, stays separate from the overview, and never changes the spell. */
@LDLRegisterClient(name = "logos_rune_description", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class RuneDescriptionScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) { o.tags("logos").guiScale(2); }

    private static GraphView view(TestContext ctx) {
        return ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
    }

    private static Label help(TestContext ctx) {
        return ctx.ui().select("#selected_rune_description", Label.class).findFirst().orElseThrow();
    }

    private static LogosNode rune(TestContext ctx, String word) {
        return view(ctx).getGraph().getNodes().stream().filter(n -> n instanceof LogosNode r && r.word().equals(word))
                .map(n -> (LogosNode) n).findFirst().orElseThrow();
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("open workbench", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, SpellPages.fitting(Examples.ALL.get("fireball"))))
                .awaitModularUI().ticks(10)
                .step("nothing selected", ctx -> ctx.check("help initially hidden", !help(ctx).isDisplayed()))
                .step("select projectile", ctx -> view(ctx).addSelected(rune(ctx, "projectile").model()))
                .ticks(2)
                .step("show existing description", ctx -> {
                    var node = rune(ctx, "projectile");
                    ctx.check("help visible", help(ctx).isDisplayed());
                    ctx.check("localized title and complete documentation", help(ctx).getValue().getString().equals(node.title() + "\n\n" + node.doc()));
                    ctx.check("overview preserved", ctx.ui().select("#overview", Label.class).findFirst().orElseThrow().isDisplayed());
                }).screenshot("selected_rune_description")
                .step("add another rune to selection", ctx -> view(ctx).addSelected(rune(ctx, "damage").model()))
                .ticks(2)
                .step("multiple selection has no ambiguous help", ctx -> ctx.check("help hidden for multiple selection", !help(ctx).isDisplayed()))
                .step("clear selection", ctx -> view(ctx).clearAllSelected()).ticks(2)
                .step("blank canvas clears help", ctx -> ctx.check("old description cleared", !help(ctx).isDisplayed() && help(ctx).getValue().getString().isEmpty()))
                .closeScreen();
    }
}
