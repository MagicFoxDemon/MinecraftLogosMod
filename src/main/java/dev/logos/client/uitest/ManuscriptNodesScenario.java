package dev.logos.client.uitest;

import java.util.concurrent.CompletableFuture;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphPreview;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphView;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.node.NodeElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.AbstractNodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ICustomNodeModel;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.ClientHooks;
import dev.logos.graph.LogosNode;
import dev.logos.item.SpellPages;
import dev.logos.lang.Category;
import dev.logos.lang.Examples;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;

/**
 * Real-client look at the workbench's manuscript runes: every rune family on one page, a close
 * view of a few figures, the red-chalk selection and a large template. Screenshots land in
 * {@code build/ldlib2-uitest/screenshots}. Run: {@code gradlew runClient -PldTest=logos_manuscript_nodes}.
 */
@LDLRegisterClient(name = "logos_manuscript_nodes", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ManuscriptNodesScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos").guiScale(2);
    }

    private static GraphView view(TestContext ctx) {
        return ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
    }

    /** Every rune on the canvas should be drawn by the manuscript renderer, not the toolkit's grey box. */
    private static void checkRunes(TestContext ctx) {
        int runes = 0;
        for (UIElement child : view(ctx).getLayer(NodeElement.NODE_LAYER).getChildren()) {
            if (!(child instanceof NodeElement node)) continue;
            runes++;
            ctx.check("rune " + ((AbstractNodeModel) node.getModel()).getTitle().getString() + " is drawn as a manuscript figure",
                    node.getClass().getSimpleName().equals("ManuscriptNode"));
        }
        ctx.check("the canvas holds runes", runes > 0);
    }

    /** Frames the upper-left part of the graph closely, so ink detail is visible in the capture. */
    private static void closeUp(TestContext ctx) {
        GraphView view = view(ctx);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        for (UIElement child : view.getLayer(NodeElement.NODE_LAYER).getChildren()) {
            if (!(child instanceof NodeElement node)) continue;
            var position = ((AbstractNodeModel) node.getModel()).getPosition();
            minX = Math.min(minX, position.x);
            minY = Math.min(minY, position.y);
        }
        view.fitGraph(minX - 16, minY - 16, minX + 520, minY + 300, 0);
    }

    /** Frames the right end of the graph, where the logic, value and effect runes of the proof spell sit. */
    private static void closeUpRight(TestContext ctx) {
        GraphView view = view(ctx);
        float maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (UIElement child : view.getLayer(NodeElement.NODE_LAYER).getChildren()) {
            if (!(child instanceof NodeElement node)) continue;
            var position = ((AbstractNodeModel) node.getModel()).getPosition();
            maxX = Math.max(maxX, position.x + node.getSizeWidth());
            minY = Math.min(minY, position.y);
            maxY = Math.max(maxY, position.y + node.getSizeHeight());
        }
        view.fitGraph(maxX - 640, minY - 16, maxX + 16, Math.max(maxY, minY + 340) + 16, 0);
    }

    /** Frames the number and proposition runes, where the Renaissance arithmetic is drawn. */
    private static void closeUpMath(TestContext ctx) {
        GraphView view = view(ctx);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (UIElement child : view.getLayer(NodeElement.NODE_LAYER).getChildren()) {
            if (!(child instanceof NodeElement node) || !(node.getModel() instanceof ICustomNodeModel custom)
                    || !(custom.getNode() instanceof LogosNode rune) || rune.category() != Category.VALUE) continue;
            var position = ((AbstractNodeModel) node.getModel()).getPosition();
            minX = Math.min(minX, position.x);
            minY = Math.min(minY, position.y);
            maxX = Math.max(maxX, position.x + node.getSizeWidth());
            maxY = Math.max(maxY, position.y + node.getSizeHeight());
        }
        ctx.check("the proof spell has number runes", minX < maxX);
        float cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
        view.fitGraph(cx - 230, cy - 130, cx + 230, cy + 130, 0);
    }

    /** Frames the topmost rune of a family closely, with its neighbours below. */
    private static void closeUpFirst(TestContext ctx, Category category) {
        GraphView view = view(ctx);
        float x = 0, y = Float.MAX_VALUE;
        for (UIElement child : view.getLayer(NodeElement.NODE_LAYER).getChildren()) {
            if (!(child instanceof NodeElement node) || !(node.getModel() instanceof ICustomNodeModel custom)
                    || !(custom.getNode() instanceof LogosNode rune) || rune.category() != category) continue;
            var position = ((AbstractNodeModel) node.getModel()).getPosition();
            if (position.y < y) {
                x = position.x;
                y = position.y;
            }
        }
        ctx.check("the graph has a rune of " + category, y < Float.MAX_VALUE);
        view.fitGraph(x - 170, y - 24, x + 290, y + 236, 0);
    }

    private static void selectFirstRune(TestContext ctx) {
        GraphView view = view(ctx);
        view.clearAllSelected();
        for (UIElement child : view.getLayer(NodeElement.NODE_LAYER).getChildren()) {
            if (child instanceof NodeElement node && !node.getModel().getTitle().getString().contains("spell")) {
                view.addSelected(node.getModel());
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadTemplate(TestContext ctx, String id) {
        ctx.ui().select("#templates", Selector.class).findFirst().ifPresent(sel -> ((Selector<Object>) sel).setSelected(id, true));
    }

    private static boolean reloaded(TestContext ctx) {
        CompletableFuture<Void> future = ctx.get("language_reload");
        return future.isDone() && !future.isCompletedExceptionally();
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("read the page in Chinese", ctx -> {
                    var mc = Minecraft.getInstance();
                    ctx.put("original_language", mc.getLanguageManager().getSelected());
                    mc.getLanguageManager().setSelected("zh_cn");
                    ctx.put("language_reload", mc.reloadResourcePacks());
                })
                .waitUntil("Chinese resources loaded", ManuscriptNodesScenario::reloaded)
                .step("open the proof spell", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO,
                        SpellPages.fitting(Examples.ALL.get("execute-proof"))))
                .awaitModularUI()
                .ticks(10)
                .step("every rune is a manuscript figure", ManuscriptNodesScenario::checkRunes)
                .screenshot("manuscript_families")
                .step("look closely at the first figures", ManuscriptNodesScenario::closeUp)
                .ticks(3)
                .screenshot("manuscript_close")
                .step("select a rune", ManuscriptNodesScenario::selectFirstRune)
                .ticks(3)
                .screenshot("manuscript_selected")
                .step("look closely at the right end", ManuscriptNodesScenario::closeUpRight)
                .ticks(3)
                .screenshot("manuscript_close_right")
                .step("look closely at the arithmetic", ManuscriptNodesScenario::closeUpMath)
                .ticks(3)
                .screenshot("manuscript_math")
                .step("pick the meteor template", ctx -> loadTemplate(ctx, "meteor"))
                .click("#load_template")
                .ticks(10)
                .step("the template's runes are manuscript figures too", ManuscriptNodesScenario::checkRunes)
                .screenshot("manuscript_meteor")
                .step("look at the meteor's forms and machinery", ManuscriptNodesScenario::closeUp)
                .ticks(3)
                .screenshot("manuscript_meteor_forms")
                .step("look at the meteor's effects", ctx -> closeUpFirst(ctx, Category.EFFECT))
                .ticks(3)
                .screenshot("manuscript_meteor_effects")
                .closeScreen()
                .step("restore language", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.getLanguageManager().setSelected(ctx.get("original_language"));
                    ctx.put("language_reload", mc.reloadResourcePacks());
                })
                .waitUntil("original language restored", ManuscriptNodesScenario::reloaded);
    }
}
