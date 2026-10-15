package dev.logos.client;

import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphPreview;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphView;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.node.NodeElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.util.NodeColors;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.AbstractNodeModel;

/** Borderless spell map; a soft viewport wash replaces the toolkit's bright rectangle. */
final class SpellNavigation extends GraphPreview {
    SpellNavigation(GraphView view) {
        super(view);
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        var layer = graphView.getLayer(NodeElement.NODE_LAYER);
        if (layer == null || getContentWidth() <= 0 || getContentHeight() <= 0) return;
        var nodes = layer.getChildren().stream().filter(NodeElement.class::isInstance)
                .map(NodeElement.class::cast).toList();
        if (nodes.isEmpty()) return;

        var canvas = graphView.graphView;
        float viewportX = canvas.getOffsetX(), viewportY = canvas.getOffsetY();
        float viewportW = canvas.getContentWidth() / canvas.getScale();
        float viewportH = canvas.getContentHeight() / canvas.getScale();
        float minX = viewportX, minY = viewportY;
        float maxX = viewportX + viewportW, maxY = viewportY + viewportH;
        for (var node : nodes) {
            var position = ((AbstractNodeModel) node.getModel()).getPosition();
            minX = Math.min(minX, position.x);
            minY = Math.min(minY, position.y);
            maxX = Math.max(maxX, position.x + node.getSizeWidth());
            maxY = Math.max(maxY, position.y + node.getSizeHeight());
        }
        // Match GraphPreview's bounds and padding so its inherited click/drag mapping stays exact.
        minX -= 20;
        minY -= 20;
        maxX += 20;
        maxY += 20;
        float scale = Math.min(getContentWidth() / (maxX - minX), getContentHeight() / (maxY - minY));
        float originX = getContentX() + (getContentWidth() - (maxX - minX) * scale) / 2;
        float originY = getContentY() + (getContentHeight() - (maxY - minY) * scale) / 2;

        context.enableScissor(getContentX(), getContentY(), getContentWidth(), getContentHeight());
        try {
            DrawerHelperClient.drawSolidRect(context, originX + (viewportX - minX) * scale,
                    originY + (viewportY - minY) * scale, viewportW * scale, viewportH * scale, 0x18FFFFFF);
            for (var node : nodes) {
                var model = (AbstractNodeModel) node.getModel();
                var position = model.getPosition();
                int color = graphView.isSelected(model) ? 0xFF4488FF : NodeColors.resolve(model);
                DrawerHelperClient.drawSolidRect(context, originX + (position.x - minX) * scale,
                        originY + (position.y - minY) * scale,
                        node.getSizeWidth() * scale, node.getSizeHeight() * scale, color);
            }
        } finally {
            context.disableScissor();
        }
    }
}
