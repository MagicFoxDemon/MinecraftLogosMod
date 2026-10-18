package dev.logos.client.uitest;

import org.joml.Vector2f;

import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphPreview;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphView;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.itemlibrary.GraphNodeCreationData;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.itemlibrary.NodeModelLibraryItem;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ICustomNodeModel;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.ClientHooks;
import dev.logos.client.Manuscript;
import dev.logos.graph.LogosNode;
import dev.logos.item.SpellPages;
import dev.logos.lang.Examples;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * The manuscript drawing's cost on a page holding one of every rune, like a player's overview
 * page: solid marks submitted per frame and frames per second, zoomed out over the whole page and
 * zoomed in on a corner. Run: {@code gradlew runClient -PldTest=logos_manuscript_budget}.
 */
@LDLRegisterClient(name = "logos_manuscript_budget", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ManuscriptBudgetScenario implements UIScenario {
    /** Marks a frame may lay over the whole page; the toolkit's flat boxes cost about two per rune. */
    private static final int WHOLE_PAGE_BUDGET = 6000;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos").guiScale(2);
    }

    private static GraphView view(TestContext ctx) {
        return ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
    }

    /** Lays one of every registered rune on the page in a grid, wired to nothing. */
    private static void everyRune(TestContext ctx) {
        GraphView view = view(ctx);
        var graph = view.getGraph().graphModel;
        int n = 0;
        for (var item : view.itemLibrary.getAllItems().toList()) {
            if (!(item instanceof NodeModelLibraryItem entry)) continue;
            if (!(entry.createNode(GraphNodeCreationData.ofOrphan(graph)) instanceof ICustomNodeModel model)
                    || !(model.getNode() instanceof LogosNode node)) continue;
            try {
                graph.createNodeModel(node.getClass().getDeclaredConstructor().newInstance(),
                        new Vector2f(600 + (n % 12) * 260, (n / 12) * 260));
                n++;
            } catch (ReflectiveOperationException ignored) {
                // A rune without a plain constructor is left out of the overview.
            }
        }
        ctx.put("runes", n);
        ctx.check("the overview page holds every rune", n > 80);
    }

    private static void record(TestContext ctx, String view) {
        int marks = Manuscript.marksLastFrame();
        int fps = Minecraft.getInstance().getFps();
        ctx.log("[manuscript budget] " + view + ": " + marks + " marks per frame, " + fps + " fps, "
                + ctx.get("runes") + " runes");
        ctx.put("marks_" + view, marks);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("open the workbench", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO,
                        SpellPages.fitting(Examples.ALL.get("meteor"))))
                .awaitModularUI()
                .ticks(10)
                .step("lay every rune on the page", ManuscriptBudgetScenario::everyRune)
                .ticks(10)
                .step("zoom out over the whole page", ctx -> view(ctx).fitGraphChildren())
                .ticks(40)
                .step("measure the whole page", ctx -> {
                    record(ctx, "whole");
                    int marks = ctx.get("marks_whole");
                    ctx.check("the whole page stays within " + WHOLE_PAGE_BUDGET + " marks a frame (" + marks + ")",
                            marks <= WHOLE_PAGE_BUDGET);
                })
                .screenshot("budget_whole_page")
                .step("zoom in on a corner", ctx -> view(ctx).fitGraph(600, 0, 1200, 340, 0))
                .ticks(40)
                .step("measure the close view", ctx -> {
                    record(ctx, "close");
                    int marks = ctx.get("marks_close");
                    ctx.check("a close view stays within " + WHOLE_PAGE_BUDGET + " marks a frame (" + marks + ")",
                            marks <= WHOLE_PAGE_BUDGET);
                })
                .screenshot("budget_close");
    }
}
