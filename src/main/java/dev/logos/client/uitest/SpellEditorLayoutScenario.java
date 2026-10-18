package dev.logos.client.uitest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphPreview;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.node.NodeElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.AbstractNodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ICustomNodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.PortModel;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.client.ClientHooks;
import dev.logos.graph.LTypes;
import dev.logos.graph.LogosNode;
import dev.logos.graph.RuneNode;
import dev.logos.graph.SpecialNodes.SpellRoot;
import dev.logos.item.SpellPages;
import dev.logos.lang.Examples;
import net.minecraft.core.BlockPos;

/**
 * Loads every example template into the workbench and checks that the graph built from its source
 * leaves every node clear of every other one, so no port or wire is hidden under a neighbour.
 * Run: {@code gradlew runClient -PldTest=logos_spell_editor_layout}.
 */
@LDLRegisterClient(name = "logos_spell_editor_layout", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class SpellEditorLayoutScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos").guiScale(2);
    }

    @SuppressWarnings("unchecked")
    private static void selectTemplate(TestContext ctx, String id) {
        ctx.ui().select("#templates", Selector.class).findFirst().ifPresent(sel -> ((Selector<Object>) sel).setSelected(id, true));
    }

    private static String name(AbstractNodeModel model) {
        return model instanceof ICustomNodeModel c ? c.getNode().getClass().getSimpleName() : model.getClass().getSimpleName();
    }

    /**
     * Rendered node boxes in graph units, compared pairwise; then each flow wire (▶, events, bodies),
     * taken as the line from its port to the top of the next node, must cross no other node.
     */
    private static void checkLayout(TestContext ctx, String id) {
        var view = ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
        Map<AbstractNodeModel, float[]> boxes = new LinkedHashMap<>();
        for (UIElement child : view.getLayer(NodeElement.NODE_LAYER).getChildren()) {
            if (!(child instanceof NodeElement node)) continue;
            var model = (AbstractNodeModel) node.getModel();
            var p = model.getPosition();
            boxes.put(model, new float[]{p.x, p.y, p.x + node.getSizeWidth(), p.y + node.getSizeHeight()});
        }
        List<AbstractNodeModel> models = new ArrayList<>(boxes.keySet());
        List<String> overlaps = new ArrayList<>();
        for (int i = 0; i < models.size(); i++) {
            for (int j = i + 1; j < models.size(); j++) {
                float[] a = boxes.get(models.get(i)), b = boxes.get(models.get(j));
                if (a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]) overlaps.add(name(models.get(i)) + " / " + name(models.get(j)));
            }
        }
        List<String> hidden = new ArrayList<>();
        for (AbstractNodeModel from : models) {
            if (!(from instanceof ICustomNodeModel c) || !(c.getNode() instanceof LogosNode n)) continue;
            for (PortModel port : n.model().getOutputsById().values()) {
                if (!port.getDataTypeHandle().equals(LTypes.FLOW)) continue;
                // ▶ leaves the bottom; events and bodies leave the right side, about halfway down.
                boolean down = port.getPortId().equals(RuneNode.NEXT) || port.getPortId().equals(SpellRoot.START);
                for (PortModel to : LogosNode.connected(port)) {
                    float[] a = boxes.get(from), b = boxes.get(to.getNodeModel());
                    if (b == null) continue;
                    float x0 = down ? (a[0] + a[2]) / 2 : a[2], y0 = down ? a[3] : (a[1] + a[3]) / 2;
                    float x1 = (b[0] + b[2]) / 2, y1 = b[1];
                    for (AbstractNodeModel other : models) {
                        if (other == from || other == to.getNodeModel()) continue;
                        float[] o = boxes.get(other);
                        for (int k = 0; k <= 32; k++) {
                            float x = x0 + (x1 - x0) * k / 32, y = y0 + (y1 - y0) * k / 32;
                            if (x > o[0] && x < o[2] && y > o[1] && y < o[3]) {
                                hidden.add(name(from) + " → " + name(to.getNodeModel()) + " under " + name(other));
                                break;
                            }
                        }
                    }
                }
            }
        }
        if (!overlaps.isEmpty()) LogosMod.LOGGER.warn("[layout] {} overlapping nodes: {}", id, overlaps);
        if (!hidden.isEmpty()) LogosMod.LOGGER.warn("[layout] {} flow wires under nodes: {}", id, hidden);
        ctx.check(id + ": the template has nodes", !boxes.isEmpty());
        ctx.check(id + ": no nodes overlap", overlaps.isEmpty());
        ctx.check(id + ": no flow wire runs under a node", hidden.isEmpty());
    }

    /** Zooms in on the root and the node its ▶ start leads to, where the overlap used to hide the port. */
    private static void frameRoot(TestContext ctx) {
        var view = ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (UIElement child : view.getLayer(NodeElement.NODE_LAYER).getChildren()) {
            if (!(child instanceof NodeElement node) || !(node.getModel() instanceof ICustomNodeModel c)) continue;
            if (!(c.getNode() instanceof SpellRoot root)) continue;
            List<AbstractNodeModel> shown = new ArrayList<>();
            shown.add((AbstractNodeModel) node.getModel());
            LogosNode.connected(root.out(SpellRoot.START)).forEach(p -> shown.add(p.getNodeModel()));
            for (AbstractNodeModel m : shown) {
                var p = m.getPosition();
                var e = (NodeElement) view.getModelElement(m);
                minX = Math.min(minX, p.x);
                minY = Math.min(minY, p.y);
                maxX = Math.max(maxX, p.x + e.getSizeWidth());
                maxY = Math.max(maxY, p.y + e.getSizeHeight());
            }
        }
        view.fitGraph(minX, minY, maxX, maxY, 24);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("open the workbench", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO,
                        SpellPages.fitting(Examples.ALL.get("fireball"))))
                .awaitModularUI()
                .ticks(10);
        for (String id : Examples.ALL.keySet()) {
            s.step("pick the " + id + " template", ctx -> selectTemplate(ctx, id))
                    .click("#load_template")
                    .ticks(2)
                    .step("check the " + id + " layout", ctx -> checkLayout(ctx, id));
            if (id.equals("meteor") || id.equals("execute-proof")) {
                String shot = id.replace('-', '_');
                s.screenshot(shot + "_graph")
                        .step("frame the " + id + " root and its first node", SpellEditorLayoutScenario::frameRoot)
                        .ticks(3)
                        .screenshot(shot + "_root");
            }
        }
        s.closeScreen();
    }
}
