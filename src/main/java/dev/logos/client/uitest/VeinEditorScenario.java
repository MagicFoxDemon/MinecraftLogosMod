package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphPreview;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphView;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.node.NodeOptionsInspector;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.node.PortElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ICustomNodeModel;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.ClientHooks;
import dev.logos.graph.RuneNode;
import dev.logos.graph.RuneNodes;
import dev.logos.item.SpellPages;
import dev.logos.lang.Motion;
import net.minecraft.core.BlockPos;

/**
 * The vein node in the real editor: as its motion changes, the inputs and the counter-clockwise box it has no use for
 * leave the node and come back, and the box keeps its tick meanwhile.
 */
@LDLRegisterClient(name = "logos_vein_editor", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class VeinEditorScenario implements UIScenario {
    private static GraphView view(TestContext ctx) {
        return ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
    }

    private static RuneNodes.Vein vein(TestContext ctx) {
        return view(ctx).getGraph().getNodes().stream().filter(n -> n instanceof RuneNodes.Vein).map(n -> (RuneNodes.Vein) n).findFirst().orElseThrow();
    }

    /** Whether the node shows its counter-clockwise box, its only option row. */
    private static boolean ccwShown(TestContext ctx) {
        return ctx.ui().select(".__node-option-container__", NodeOptionsInspector.class)
                .filter(o -> o.nodeModel instanceof ICustomNodeModel model && model.getNode() instanceof RuneNodes.Vein)
                .findFirst().orElseThrow().getChildren().getFirst().isDisplayed();
    }

    /** Whether the node shows a row for that keyword. */
    private static boolean shown(TestContext ctx, String keyword) {
        var port = vein(ctx).in(RuneNode.kw(keyword));
        return ctx.ui().select(".__port__", PortElement.class).anyMatch(e -> e.getModel() == port && e.isDisplayed());
    }

    private static void pick(TestContext ctx, Motion motion) {
        var node = vein(ctx);
        node.in(RuneNode.kw("motion")).getEmbeddedValue().setValue(motion);
        node.model().defineNode();
    }

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("logos", "vein").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("open editor", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO,
                        SpellPages.fitting("(spell :name \"弧扫\" :mana 100 (vein :motion sweep :spread 120 :radius 5 :ccw))")))
                .awaitModularUI().ticks(10)
                .step("fit", ctx -> view(ctx).fitGraphChildren()).ticks(4)
                .check("a sweep shows counter-clockwise, its angle and radius", ctx -> ccwShown(ctx)
                        && shown(ctx, "spread") && shown(ctx, "radius") && !shown(ctx, "range"))
                .screenshot("vein_editor_sweep")
                .step("crawl straight", ctx -> pick(ctx, Motion.FORWARD)).ticks(4)
                .check("a line shows only its range", ctx -> !ccwShown(ctx)
                        && shown(ctx, "range") && !shown(ctx, "spread") && !shown(ctx, "radius"))
                .screenshot("vein_editor_forward")
                .step("ring", ctx -> pick(ctx, Motion.RING)).ticks(4)
                .check("a ring shows its range and angle", ctx -> !ccwShown(ctx)
                        && shown(ctx, "range") && shown(ctx, "spread") && !shown(ctx, "radius"))
                .step("sweep again", ctx -> pick(ctx, Motion.SWEEP)).ticks(4)
                .check("sweeping again shows them, still ticked", ctx -> ccwShown(ctx) && shown(ctx, "spread") && shown(ctx, "radius")
                        && !shown(ctx, "range") && Boolean.TRUE.equals(vein(ctx).option(RuneNode.flag("ccw"), Boolean.class)))
                .screenshot("vein_editor_sweep_again")
                .closeScreen();
    }
}
