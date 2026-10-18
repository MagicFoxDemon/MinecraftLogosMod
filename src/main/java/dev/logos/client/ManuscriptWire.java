package dev.logos.client;

import java.util.List;

import org.joml.Vector2f;

import com.lowdragmc.lowdraglib2.gui.ui.elements.GraphViewLod;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.IGUIContext;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.WireElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.PortModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.wire.WireModel;

import dev.logos.graph.LTypes;

/**
 * A wire drawn as a notebook draws its arrows. The spell's order of events is a bold quill arrow
 * with a fletched tail and a solid head (where it meets a rune, the rune draws the head on its own
 * edge, since wires run beneath runes); a value carried into a slot is a light dashed line in the
 * ink of what it carries, pricked at its source, like the construction lines that feed a figure.
 * The route is laid in whole pixels once and replayed; routing, picking and dragging stay the toolkit's.
 */
final class ManuscriptWire extends WireElement {
    /** How far short of the slot an arrow stops, so the slot's own mark stays readable. */
    private static final float SHORT = 4;

    private List<Vector2f> planned;
    private Manuscript.Plan plain, bold;

    ManuscriptWire(WireModel model) {
        super(model);
    }

    private PortModel port() {
        return getModel().getFromPort() != null ? getModel().getFromPort() : getModel().getToPort();
    }

    private boolean isFlow() {
        PortModel port = port();
        LTypes.init();
        return port != null && port.getDataTypeHandle().equals(LTypes.FLOW);
    }

    @Override
    protected void drawBackgroundAdditional(IGUIContext guiContext) {
        if (!(guiContext instanceof GUIContext context)) return;
        GraphViewLod lod = effectiveLod();
        if (lod != lastLod) {
            lastLod = lod;
            geometryDirty = true;
        }
        if (lod == GraphViewLod.BLOCK) return;
        updatePortPosition();
        if (drawPoints.size() < 2) return;
        boolean flow = isFlow();
        if (planned != drawPoints) {
            planned = drawPoints;
            if (lod == GraphViewLod.FULL) {
                plain = plan(flow, false);
                bold = plan(flow, true);
            } else {
                // From far away a route is a few screen pixels wide: one solid line in coarse cells.
                plain = bold = new Manuscript.Plan(0);
                new Manuscript.Pen(plain, flow ? 4 : 3, 0, 0).grid(flow ? 4 : 3).trace(drawPoints, 0);
            }
        }
        boolean marked = isSelected() || isUnderRegionSelection();
        PortModel port = port();
        int color = marked ? Manuscript.SANGUINE : flow || port == null ? Manuscript.INK : ManuscriptMarks.inkOf(port.getDataTypeHandle());
        if (!isActive()) color = (color & 0x00FFFFFF) | 0x66000000;
        (marked || isHover() ? bold : plain).draw(context, 0, 0, color);
    }

    private Manuscript.Plan plan(boolean flow, boolean heavy) {
        Manuscript.Plan p = new Manuscript.Plan(0);
        if (flow) {
            new Manuscript.Pen(p, heavy ? 3 : 2, 0, 0).trace(drawPoints, SHORT + 3);
            fletching(p);
            head(p, 7, heavy ? 4.5f : 4f, true);
        } else {
            new Manuscript.Pen(p, heavy ? 2 : 1, 4, 3).trace(drawPoints, 0);
            Vector2f start = drawPoints.getFirst();
            p.rect(Math.round(start.x) - 1, Math.round(start.y) - 1, 2, 2, 0xFF000000);
        }
        return p;
    }

    private void head(Manuscript.Plan p, float length, float halfWidth, boolean solid) {
        Vector2f tip = along(drawPoints.reversed(), SHORT), back = along(drawPoints.reversed(), SHORT + length);
        Vector2f dir = new Vector2f(tip).sub(back);
        if (dir.lengthSquared() < 1e-4f) return;
        dir.normalize();
        Vector2f side = new Vector2f(-dir.y, dir.x).mul(halfWidth);
        Vector2f left = new Vector2f(back).add(side), right = new Vector2f(back).sub(side);
        if (solid) {
            p.triangle(tip.x, tip.y, left.x, left.y, right.x, right.y, 0xFF000000);
        } else {
            Manuscript.Pen pen = new Manuscript.Pen(p, 1, 0, 0);
            pen.segment(left.x, left.y, tip.x, tip.y);
            pen.segment(right.x, right.y, tip.x, tip.y);
        }
    }

    /** Two feathers at the tail of an order-of-events arrow. */
    private void fletching(Manuscript.Plan p) {
        Vector2f start = drawPoints.getFirst(), ahead = along(drawPoints, 14);
        Vector2f dir = new Vector2f(ahead).sub(start);
        if (dir.lengthSquared() < 1e-4f) return;
        dir.normalize();
        Vector2f side = new Vector2f(-dir.y, dir.x);
        Manuscript.Pen pen = new Manuscript.Pen(p, 1, 0, 0);
        // Past the rune's edge: the first few pixels of a wire run under the rune it leaves.
        for (float at : new float[]{11, 14}) {
            Vector2f base = along(drawPoints, at);
            Vector2f tail = new Vector2f(base).sub(new Vector2f(dir).mul(3));
            Vector2f l = new Vector2f(tail).add(new Vector2f(side).mul(3)), r = new Vector2f(tail).sub(new Vector2f(side).mul(3));
            pen.segment(l.x, l.y, base.x, base.y);
            pen.segment(r.x, r.y, base.x, base.y);
        }
    }

    private static Vector2f along(List<Vector2f> points, float distance) {
        float left = distance;
        for (int i = 1; i < points.size(); i++) {
            Vector2f a = points.get(i - 1), b = points.get(i);
            float len = a.distance(b);
            if (len >= left && len > 0) return new Vector2f(a).lerp(b, left / len);
            left -= len;
        }
        return new Vector2f(points.getLast());
    }
}
