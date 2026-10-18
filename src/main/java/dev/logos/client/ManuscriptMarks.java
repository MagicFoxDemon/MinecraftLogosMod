package dev.logos.client;

import java.util.Map;
import java.util.WeakHashMap;

import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.Style;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.port.PortModelOptions;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.port.PortOrientation;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandle;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.node.PortConnectorElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.node.PortConnectorWithIconElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.PortModel;

import dev.logos.graph.LTypes;
import dev.logos.lang.Type;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import net.minecraft.network.chat.Component;

/**
 * What a rune asks for, written the way a notebook labels the parts of a machine: each slot is
 * marked with a small drawing of the thing it takes (a figure, a surveyor's mark, an arrow…), faint
 * while it waits and inked in once something is wired to it, and a value is written on a dotted
 * line rather than typed into a box. Only the drawing changes; the toolkit's ports and editors
 * still do all the work, so these are reapplied whenever the toolkit rebuilds one of them.
 */
final class ManuscriptMarks {
    /** The drawing for a kind of slot and the ink it is drawn in. */
    private record Mark(String[] rows, int ink) {}

    private static final Mark ENTITY = new Mark(new String[]{
            "..###..",
            "..###..",
            "...#...",
            ".#####.",
            "...#...",
            "..#.#..",
            ".#...#."}, 0xFF9C3D24);
    private static final Mark ENTITIES = new Mark(new String[]{
            ".#...#.",
            "###.###",
            ".#...#.",
            "###.###",
            ".#...#.",
            "#.#.#.#",
            "......."}, 0xFF8A4A1E);
    private static final Mark POS = new Mark(new String[]{
            "..###..",
            ".#.#.#.",
            "#..#..#",
            "#######",
            "#..#..#",
            ".#.#.#.",
            "..###.."}, 0xFF2C5049);
    private static final Mark BLOCKS = new Mark(new String[]{
            "..####.",
            ".#..##.",
            "####.#.",
            "#..#.#.",
            "#..##..",
            "####...",
            "......."}, 0xFF4E5F2A);
    private static final Mark DIR = new Mark(new String[]{
            ".......",
            "#...#..",
            ".#...#.",
            "#######",
            ".#...#.",
            "#...#..",
            "......."}, 0xFF2F6670);
    private static final Mark NUM = new Mark(new String[]{
            ".......",
            ".......",
            "#######",
            "#.#.#.#",
            "#.#.#.#",
            ".......",
            "......."}, 0xFF26406B);
    private static final Mark BOOL = new Mark(new String[]{
            "..###..",
            ".#.###.",
            "#..####",
            "#..####",
            "#..####",
            ".#.###.",
            "..###.."}, 0xFF8A6418);
    private static final Mark ELEMENT = new Mark(new String[]{
            "...#...",
            "...#...",
            "..#.#..",
            "..#.#..",
            ".#...#.",
            ".#...#.",
            "#######"}, 0xFFB0481F);
    private static final Mark MOTION = new Mark(new String[]{
            ".#...#.",
            "..###..",
            ".#...#.",
            ".#...#.",
            "..###..",
            "...#...",
            "..###.."}, 0xFF4F5A6E);
    private static final Mark SHAPE = new Mark(new String[]{
            ".......",
            ".#####.",
            "#.....#",
            "#.....#",
            "#.....#",
            ".#####.",
            "......."}, 0xFF6B4A2A);
    private static final Mark TEXT = new Mark(new String[]{
            "......#",
            ".....#.",
            "....#..",
            "...#...",
            "..#....",
            ".##....",
            "##....."}, 0xFF5A4030);
    private static final Mark FLOW = new Mark(new String[]{
            "#......",
            "###....",
            "#####..",
            "#######",
            "#####..",
            "###....",
            "#......"}, Manuscript.INK);

    /** The ink a kind of value is drawn in, shared by its slots and the wires that carry it. */
    static int inkOf(TypeHandle type) {
        return of(type).ink;
    }

    /** The order of events enters a rune at its top and leaves at its bottom: drawn as the head of the arrow crossing the edge. */
    private static final Mark FLOW_DOWN = new Mark(new String[]{
            ".......",
            ".......",
            "#######",
            ".#####.",
            "..###..",
            "...#...",
            "......."}, Manuscript.INK);

    private static Mark of(TypeHandle type) {
        LTypes.init();
        if (type.equals(LTypes.ENTITY)) return ENTITY;
        if (type.equals(LTypes.ENTITIES)) return ENTITIES;
        if (type.equals(LTypes.POS)) return POS;
        if (type.equals(LTypes.BLOCKS)) return BLOCKS;
        if (type.equals(LTypes.DIR)) return DIR;
        if (type.equals(LTypes.BOOL)) return BOOL;
        if (type.equals(LTypes.ELEMENT)) return ELEMENT;
        if (type.equals(LTypes.MOTION)) return MOTION;
        if (type.equals(LTypes.SHAPE)) return SHAPE;
        if (type.equals(LTypes.STATUS)) return TEXT;
        if (type.equals(LTypes.FLOW)) return FLOW;
        return NUM;
    }

    /** What has been dressed already, so a rebuilt port or editor is noticed and dressed again. */
    private final Map<UIElement, IGuiTexture> dressed = new WeakHashMap<>();

    /** Walks a rune's parts and redraws any port or editor the toolkit has (re)built since the last frame. */
    void dress(UIElement root) {
        if (root instanceof PortConnectorElement connector) {
            dressConnector(connector);
        } else if (root instanceof TextField || root instanceof Selector<?>) {
            dressBlank(root);
        }
        for (UIElement child : root.getChildren()) dress(child);
    }

    private void dressConnector(PortConnectorElement connector) {
        UIElement icon = connector.getConnectorIcon();
        if (icon == null) return;
        IGuiTexture mine = dressed.computeIfAbsent(icon, k -> portMark(connector));
        if (icon.getStyle().backgroundTexture() != mine) Style.importantPipeline(icon.getStyle(), s -> s.background(mine));
        // The slot's drawing already says what it takes; the toolkit's second type icon would repeat it.
        if (connector instanceof PortConnectorWithIconElement withIcon && withIcon.getPortIcon() != null
                && withIcon.getPortIcon().isDisplayed()) {
            Style.importantPipeline(withIcon.getPortIcon().getLayout(), l -> l.display(TaffyDisplay.NONE));
        }
        // …and so would a type glyph at the front of its name ("○ 实体", or a lone "▶").
        Label name = connector.getName();
        if (name == null || connector.portModel.getOptions().hasFlag(PortModelOptions.NODE_OPTION)) return;
        String text = name.getValue().getString();
        String bare = withoutTypeGlyph(text);
        if (!bare.equals(text)) {
            name.setText(Component.literal(bare));
            if (bare.isEmpty()) Style.importantPipeline(name.getLayout(), l -> l.display(TaffyDisplay.NONE));
        }
    }

    private static String withoutTypeGlyph(String label) {
        for (Type type : Type.values()) {
            if (label.startsWith(type.glyph)) return label.substring(type.glyph.length()).stripLeading();
        }
        return label;
    }

    private static GuiTexture portMark(PortConnectorElement connector) {
        PortModel port = connector.portModel;
        Manuscript.Plan faint = new Manuscript.Plan(0), inked = new Manuscript.Plan(0), hinted = new Manuscript.Plan(0);
        Mark mark = port.getOrientation() == PortOrientation.Vertical ? FLOW_DOWN : of(port.getDataTypeHandle());
        faint.picture(0, 0, mark.rows, 0x8C000000 | (mark.ink & 0xFFFFFF), 0);
        inked.picture(0, 0, mark.rows, mark.ink, 0);
        hinted.picture(0, 0, mark.rows, Manuscript.SANGUINE, 0);
        return (context, x, y, w, h) -> {
            Manuscript.Plan plan = connector.isWillConnect() ? hinted : port.isConnected() ? inked : faint;
            plan.draw(context, Math.round(x + (w - 7) / 2), Math.round(y + (h - 7) / 2));
        };
    }

    /** A dotted line to write a value on; it turns to red chalk while the value is being written. */
    private void dressBlank(UIElement field) {
        IGuiTexture mine = dressed.computeIfAbsent(field, k -> (GuiTexture) (context, x, y, w, h) -> blank(context, field, x, y, w, h));
        if (field.getStyle().backgroundTexture() != mine) Style.importantPipeline(field.getStyle(), s -> s.background(mine));
    }

    private static void blank(GUIContext context, UIElement field, float x, float y, float w, float h) {
        boolean writing = field.isFocused();
        int color = writing ? Manuscript.SANGUINE : 0xB0000000 | (Manuscript.INK_FADED & 0xFFFFFF);
        float line = Math.round(y + h - 1);
        for (float i = 0; i < w; i += 2) DrawerHelperClient.drawSolidRect(context, x + i, line, 1, 1, color);
        if (writing) DrawerHelperClient.drawSolidRect(context, x, line, w, 1, color);
    }
}
