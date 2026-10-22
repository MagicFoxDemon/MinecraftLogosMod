package dev.logos.client;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.Style;
import com.lowdragmc.lowdraglib2.gui.ui.UIElementRendererRegistry;
import com.lowdragmc.lowdraglib2.gui.ui.elements.GraphViewLod;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.IGUIContext;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.dependency.ModelUpdateVisitor;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.node.CollapsibleInOutNodeElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.node.CollapsibleNodeTitleElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.node.InOutPortContainerElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.node.NodeOptionsInspector;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.node.PortContainerElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.node.VerticalPortContainerElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.ChangeHint;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.AbstractNodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.NodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.PortModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.PortNodeModel;

import dev.logos.graph.LogosNode;
import dev.logos.graph.LTypes;
import dev.logos.lang.Category;
import dev.logos.lang.Element;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import net.minecraft.network.chat.Component;

/**
 * A rune drawn as a figure in a Renaissance notebook rather than a grey box: each family has its
 * own outline and wash, so the kind of a rune reads before its name does, even in grey, and a
 * small drawing beside the name says what this rune in particular does. Ports, wires and editors
 * keep the toolkit's behaviour; only the paper they sit on changes.
 */
final class ManuscriptNode extends CollapsibleInOutNodeElement {
    /** Watercolour (a touch of parchment shows through), deeper wash under the title, and the ink or chalk a family is drawn in. */
    private record Look(int wash, int titleWash, int ink) {
        static Look of(Category category) {
            return switch (category) {
                // Gilded title page.
                case INVOCATION -> new Look(0xF2F3DDA2, 0xF6E7C673, 0xFF6B4614);
                // A draughtsman's construction in umber on ivory.
                case FORM -> new Look(0xF2F4E9CC, 0xF6E4D2A6, Manuscript.INK);
                // Verdigris: sighting and pointing.
                case BINDING, MOTION -> new Look(0xF2DCE6CF, 0xF6BDD3B4, 0xFF2C5049);
                // Red chalk: what the spell does to the world.
                case EFFECT -> new Look(0xF2F5D9C3, 0xF6EBB99A, Manuscript.SANGUINE);
                // Folium violet: the machinery of order.
                case LOGIC -> new Look(0xF2E5DCE8, 0xF6D0C1D9, 0xFF4A3560);
                // Lapis: number and proportion.
                case VALUE -> new Look(0xF2DCE3EE, 0xF6C2CFE3, 0xFF26406B);
            };
        }
    }

    /** How far red chalk cuts the corners of an effect. */
    private static final int CUT = 4;

    /** The family's emblem beside the title, in place of the alchemical glyph the toolkit would print. */
    private static String[] emblem(Category category) {
        return switch (category) {
            // ☉ the sun over the page.
            case INVOCATION -> new String[]{
                    "....+....",
                    ".+.....+.",
                    "...###...",
                    "..#+++#..",
                    "+.#+#+#.+",
                    "..#+++#..",
                    "...###...",
                    ".+.....+.",
                    "....+...."};
            // A solid in perspective, as Leonardo drew them for Pacioli.
            case FORM -> new String[]{
                    "...######",
                    "..#++++##",
                    ".#++++#.#",
                    "######..#",
                    "#....#..#",
                    "#....#..#",
                    "#....#.#.",
                    "#....##..",
                    "######..."};
            // ☞ the reader's pointing hand from the margins.
            case BINDING, MOTION -> new String[]{
                    ".........",
                    "##.......",
                    "#+######.",
                    "#+++++++#",
                    "#+#####..",
                    "#+++#....",
                    "#+++#....",
                    "##.##....",
                    "........."};
            // 🜍 sulphur, the principle of change.
            case EFFECT -> new String[]{
                    "....#....",
                    "...#+#...",
                    "..#+++#..",
                    ".#+++++#.",
                    "#########",
                    "....#....",
                    "..#####..",
                    "....#....",
                    "....#...."};
            // A toothed wheel from the machine studies.
            case LOGIC -> new String[]{
                    "....#....",
                    ".#.###.#.",
                    "..#+++#..",
                    ".#+#.#+#.",
                    "##+...+##",
                    ".#+#.#+#.",
                    "..#+++#..",
                    ".#.###.#.",
                    "....#...."};
            // Dividers striking an arc.
            case VALUE -> new String[]{
                    "....#....",
                    "...#+#...",
                    "...#.#...",
                    "..#...#..",
                    "..#...#..",
                    ".#.....#.",
                    ".#.+++.#.",
                    "#.+...+.#",
                    "#.......#"};
        };
    }

    /** A rune's own drawing (or its family's), centred in whatever box it is given; shared with the rune library. */
    static GuiTexture emblem(LogosNode rune) {
        if (rune instanceof dev.logos.graph.EnumValues.Value value) return EnumPreviews.cardIcon(value.kind, value.value());
        Category category = rune.category();
        String[] own = MathEmblems.of(rune.word());
        if (own == null) own = RuneEmblems.of(rune.word());
        Manuscript.Plan picture = new Manuscript.Plan(0);
        picture.picture(0, 0, own != null ? own : emblem(category), Look.of(category).ink, emblemAccent(category));
        return (context, x, y, w, h) -> picture.draw(context, Math.round(x + (w - 9) / 2), Math.round(y + (h - 9) / 2));
    }

    /** A family's wash, for the rune library's entries. */
    static int wash(Category category) {
        return Look.of(category).titleWash;
    }

    private static int emblemAccent(Category category) {
        return switch (category) {
            case INVOCATION -> 0xFFC8902A;
            case FORM -> 0xFFD9C08A;
            case BINDING, MOTION -> 0xFFEADCBC;
            case EFFECT -> 0xFFD9772E;
            case LOGIC -> 0xFFB7A3CC;
            case VALUE -> 0xFF7F98C4;
        };
    }

    private final Category category;
    private final String word;
    private final LogosNode rune;
    private final Look look;
    private final int seed;
    private final ManuscriptMarks marks = new ManuscriptMarks();
    private Manuscript.Plan frame;
    private Manuscript.Plan circling;
    private float frameW = -1, frameH = -1, frameTitleTop = -1, frameTitle = -1;
    private @Nullable Element frameElement;
    private Manuscript.@Nullable Plan sketch;
    private float sketchW = -1, sketchH = -1;

    ManuscriptNode(AbstractNodeModel model, LogosNode node) {
        super(model);
        category = node.category();
        word = node.word();
        rune = node;
        look = Look.of(category);
        seed = model.getUid().hashCode();
        addClass("__manuscript-node__");
        addClass("manuscript-" + category.name().toLowerCase(Locale.ROOT));
    }

    /** The toolkit's own part list, with the title swapped for one that shows the family's emblem. */
    @Override
    protected void buildPartList() {
        parts.add(nodeTittle = new Heading(getModel()));
        if (getModel() instanceof NodeModel nodeModel) parts.add(nodeOptionContainer = new Choices(nodeModel));
        if (getModel() instanceof PortNodeModel ports) {
            parts.add(topPortContainer = new VerticalPortContainerElement(ports,
                    PortContainerElement.VERTICAL_PORT_FILTER.and(PortContainerElement.INPUT_PORT_FILTER)));
            parts.add(portContainerElement = new InOutPortContainerElement(ports, PortContainerElement.HORIZONTAL_PORT_FILTER));
            parts.add(bottomPortContainer = new VerticalPortContainerElement(ports,
                    PortContainerElement.VERTICAL_PORT_FILTER.and(PortContainerElement.OUTPUT_PORT_FILTER)));
        }
        buildPreviewPart();
    }

    /** A connected enum port supplies the value; its legacy inline fallback only appears while unwired. */
    private final class Choices extends NodeOptionsInspector {
        Choices(NodeModel model) { super(model); }

        @Override public boolean hasModelDependenciesChanged() { return true; }

        @Override public void addModelDependencies() {
            dependencies.addModelDependency(nodeModel);
            if (!(rune instanceof dev.logos.graph.RuneNode rn)) return;
            for (var p : rn.rune().params) {
                if (!dev.logos.graph.RuneNode.isOption(p)) continue;
                var port = nodeModel.getInputsById().get(p.name());
                if (port == null) continue;
                dependencies.addModelDependency(port);
                port.getConnectedWires().forEach(dependencies::addModelDependency);
            }
        }

        @Override public void updateUIFromModel(ModelUpdateVisitor visitor) {
            super.updateUIFromModel(visitor);
            refreshChoices();
        }

        void refreshChoices() {
            if (!(rune instanceof dev.logos.graph.RuneNode rn)) return;
            int row = 0;
            for (var option : nodeModel.getNodeOptions()) {
                if (!option.getVisibility().showInNode() || !option.getPortModel().isConfiguratorEnabled()) continue;
                if (row >= getChildren().size()) break;
                boolean connected = rn.rune().params.stream().anyMatch(p -> dev.logos.graph.RuneNode.isOption(p)
                        && dev.logos.graph.RuneNode.option(p).equals(option.getId())
                        && nodeModel.getInputsById().get(p.name()) != null && nodeModel.getInputsById().get(p.name()).isConnected());
                Style.importantPipeline(getChildren().get(row++).getLayout(), l -> l.display(connected ? TaffyDisplay.NONE : TaffyDisplay.FLEX));
            }
        }
    }

    /** A title written in ink after the family's emblem; the glyph the model's title starts with is left out. */
    private final class Heading extends CollapsibleNodeTitleElement {
        private Heading(AbstractNodeModel model) {
            super(model);
        }

        @Override
        public void updateUIFromModel(ModelUpdateVisitor visitor) {
            super.updateUIFromModel(visitor);
            if (visitor.hasHint(ChangeHint.STYLE) || visitor.hasHint(ChangeHint.DATA)) {
                String title = rune instanceof dev.logos.graph.EnumValues.Value value ? value.title() : nodeModel.getTitle().getString();
                String glyph = category.glyph + " ";
                nodeTittle.setText(Component.literal(title.startsWith(glyph) ? title.substring(glyph.length()) : title));
            }
            if (visitor.hasHint(ChangeHint.STYLE) || visitor.hasHint(ChangeHint.DATA)) {
                GuiTexture drawn = emblem(rune);
                int size = rune instanceof dev.logos.graph.EnumValues.Value ? 24 : 9;
                Style.importantPipeline(nodeIcon.getLayout(), l -> l.display(TaffyDisplay.FLEX).width(size).height(size));
                Style.importantPipeline(nodeIcon.getStyle(), s -> s.background(drawn));
            }
        }
    }

    @Override
    protected void buildUI() {
        super.buildUI();
        GuiTexture paper = this::drawFrame;
        Style.importantPipeline(getStyle(), s -> s.background(paper));
    }

    @Override
    protected int getLodBodyColor() {
        return look.wash;
    }

    @Override
    protected int getLodAccentColor() {
        return look.ink;
    }

    /** Off screen, nothing; zoomed out, a sketch of the figure rather than the toolkit's flat block. */
    @Override
    protected void drawBackgroundTexture(IGUIContext context) {
        if (isCulled() || !(context instanceof GUIContext gui)) return;
        if (lod() != GraphViewLod.FULL) {
            drawSketch(gui);
        } else {
            marks.dress(this);
            super.drawBackgroundTexture(context);
        }
    }

    /**
     * Zoomed out, a rune is a few pixels across, and the full figure costs hundreds of marks for
     * detail nobody can see; so it is reduced to its wash, title band and outline in its family's ink.
     */
    private void drawSketch(GUIContext context) {
        float w = Math.round(getSizeWidth()), h = Math.round(getSizeHeight());
        float title = nodeTittle == null ? 14 : Math.round(nodeTittle.getPositionY() + nodeTittle.getSizeHeight() - getPositionY());
        float top = nodeTittle == null ? 0 : Math.round(nodeTittle.getPositionY() - getPositionY());
        if (sketch == null || w != sketchW || h != sketchH) {
            sketchW = w;
            sketchH = h;
            sketch = new Manuscript.Plan(seed);
            float bandTop = Math.max(1, top);
            sketch.rect(1, 1, w - 2, bandTop - 1, look.wash);
            sketch.rect(1, bandTop, w - 2, title - bandTop, look.titleWash);
            sketch.rect(1, title, w - 2, h - 1 - title, look.wash);
            // Two pixels thick: a one-pixel line drops out entirely below one screen pixel per unit.
            sketch.rect(0, 0, w, 2, look.ink);
            sketch.rect(0, h - 2, w, 2, look.ink);
            sketch.rect(0, 2, 2, h - 4, look.ink);
            sketch.rect(w - 2, 2, 2, h - 4, look.ink);
            sketch.rect(w, 2, 1, h - 1, 0x50000000 | (Manuscript.INK & 0xFFFFFF));
            sketch.rect(2, h, w - 1, 1, 0x50000000 | (Manuscript.INK & 0xFFFFFF));
        }
        sketch.draw(context, getPositionX(), getPositionY());
    }

    @Override
    protected void drawBackgroundAdditional(IGUIContext context) {
        if (lod() == GraphViewLod.FULL) super.drawBackgroundAdditional(context);
    }

    private void drawFrame(GUIContext context, float x, float y, float width, float height) {
        float w = Math.round(width), h = Math.round(height);
        float top = nodeTittle == null ? 0 : Math.round(nodeTittle.getPositionY() - y);
        float bottom = nodeTittle == null ? 14 : Math.round(nodeTittle.getPositionY() + nodeTittle.getSizeHeight() - y);
        Element element = category == Category.EFFECT ? element() : null;
        if (frame == null || w != frameW || h != frameH || top != frameTitleTop || bottom != frameTitle || element != frameElement) {
            frameElement = element;
            frameW = w;
            frameH = h;
            frameTitleTop = top;
            frameTitle = bottom;
            frame = plan(w, h, top, bottom, element);
            circling = circling(w, h);
        }
        frame.draw(context, x, y);
    }

    /** Selection is circled in red chalk, the way a reader marks a figure; hovering only traces it faintly. */
    @Override
    public void drawBackgroundOverlay(GUIContext context) {
        UIElementRendererRegistry.defaultRenderer().drawBackgroundOverlay(this, context);
        if (circling == null) return;
        if (isSelected()) {
            circling.draw(context, getPositionX(), getPositionY());
        } else if (shouldBeHighlighted() || showHoverHighlight()) {
            float x = getPositionX() - 3, y = getPositionY() - 3, w = frameW + 6, h = frameH + 6;
            int faint = 0x8C000000 | (look.ink & 0xFFFFFF);
            for (float i = 0; i < w; i += 4) {
                DrawerHelperClient.drawSolidRect(context, x + i, y, Math.min(2, w - i), 1, faint);
                DrawerHelperClient.drawSolidRect(context, x + i, y + h - 1, Math.min(2, w - i), 1, faint);
            }
            for (float i = 0; i < h; i += 4) {
                DrawerHelperClient.drawSolidRect(context, x, y + i, 1, Math.min(2, h - i), faint);
                DrawerHelperClient.drawSolidRect(context, x + w - 1, y + i, 1, Math.min(2, h - i), faint);
            }
        }
    }

    private Manuscript.Plan plan(float w, float h, float titleTop, float title, @Nullable Element element) {
        Manuscript.Plan p = new Manuscript.Plan(seed);
        shade(p, w, h);
        wash(p, w, h, titleTop, title);
        switch (category) {
            case INVOCATION -> invocation(p, w, h);
            case FORM -> form(p, w, h);
            case BINDING, MOTION -> binding(p, w, h, (titleTop + title) / 2);
            case EFFECT -> effect(p, w, h, element);
            case LOGIC -> logic(p, w, h);
            case VALUE -> value(p, w, h);
        }
        // Ruled lines under the title, as a heading in a notebook is underlined.
        int soft = 0x99000000 | (look.ink & 0xFFFFFF);
        p.hStroke(3, w - 3, title - 1, 0, soft, 900);
        p.hStroke(5, w - 5, title + 1, 0, 0x55000000 | (look.ink & 0xFFFFFF), 950);
        return p;
    }

    /** Hatching to the lower right, the way Leonardo's left hand shaded. */
    private void shade(Manuscript.Plan p, float w, float h) {
        int hatch = 0x50000000 | (Manuscript.INK & 0xFFFFFF);
        for (float y = 3; y < h; y += 3) p.hatch(w + 1, y, (int) Math.min(3, h + 2 - y), hatch);
        for (float x = 4; x < w + 1; x += 3) p.hatch(x, h + 1, 3, hatch);
    }

    /** Watercolour that pools a little darker where it dried against the pen line. */
    private void wash(Manuscript.Plan p, float w, float h, float titleTop, float title) {
        int cut = category == Category.EFFECT ? CUT : 0;
        int topWash = titleTop <= cut ? look.titleWash : look.wash;
        for (int i = 0; i < cut; i++) {
            p.rect(1 + cut - i, 1 + i, w - 2 - 2 * (cut - i), 1, topWash);
            p.rect(1 + cut - i, h - 2 - i, w - 2 - 2 * (cut - i), 1, look.wash);
        }
        // Laid side by side, never over each other: the washes are translucent.
        float bandTop = Math.max(1 + cut, titleTop);
        p.rect(1, 1 + cut, w - 2, bandTop - 1 - cut, look.wash);
        p.rect(1, bandTop, w - 2, title - 1 - bandTop, look.titleWash);
        p.rect(1, title - 1, w - 2, h - title - cut, look.wash);
        int pool = (look.titleWash & 0xFFFFFF) | 0x99000000;
        for (int i = 0; i < 6; i++) {
            float len = 8 + p.roll(400 + i, 18);
            float along = p.roll(410 + i, Math.max(1, (int) (w - len - 4 - 2 * cut))) + 2 + cut;
            p.rect(along, i % 2 == 0 ? h - 2 : 1, len, 1, pool);
        }
    }

    /** The four sides of a figure, with lines carried past the corners as a draughtsman draws them. */
    private void box(Manuscript.Plan p, float x0, float y0, float x1, float y1, int over, int color, int salt) {
        p.hStroke(x0 - over, x1 + over, y0, -1, color, salt);
        p.hStroke(x0 - over, x1 + over, y1 - 1, 1, color, salt + 100);
        p.vStroke(x0, y0 - over, y1 + over, -1, color, salt + 200);
        p.vStroke(x1 - 1, y0 - over, y1 + over, 1, color, salt + 300);
    }

    /** ☉ A gilded page: double rule and a lunette holding the sun. */
    private void invocation(Manuscript.Plan p, float w, float h) {
        float cx = Math.round(w / 2);
        p.dome(cx, 1, 10, look.titleWash);
        p.arc(cx, 0, 10, look.ink, Manuscript.Plan.TOP_LEFT | Manuscript.Plan.TOP_RIGHT);
        p.arc(cx, 0, 8, 0x80000000 | (look.ink & 0xFFFFFF), Manuscript.Plan.TOP_LEFT | Manuscript.Plan.TOP_RIGHT);
        int gold = 0xFFC8902A;
        p.disc(cx, -3, 3, gold);
        p.ring(cx, -3, 3, look.ink);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * i / 4;
            float rx = (float) Math.round(Math.cos(a) * 5.5), ry = (float) Math.round(Math.sin(a) * 5.5);
            if (-3 + ry > 0) continue;
            p.rect(cx + rx - (rx > 0 ? 1 : 0), -3 + ry - (ry > 0 ? 1 : 0), 1, 1, gold);
        }
        box(p, 0, 0, w, h, 0, look.ink, 10);
        int inner = 0x8C000000 | (look.ink & 0xFFFFFF);
        p.hStroke(3, w - 3, 2, 0, inner, 60);
        p.hStroke(3, w - 3, h - 3, 0, inner, 70);
        p.vStroke(2, 3, h - 3, 0, inner, 80);
        p.vStroke(w - 3, 3, h - 3, 0, inner, 90);
    }

    /** 🜔 A construction drawing: overshooting lines, divider pricks at the corners, centre ticks. */
    private void form(Manuscript.Plan p, float w, float h) {
        int faint = 0x80000000 | (look.ink & 0xFFFFFF);
        p.ring(0, 0, 4, faint);
        p.ring(w, 0, 4, faint);
        p.ring(0, h, 4, faint);
        p.ring(w, h, 4, faint);
        box(p, 0, 0, w, h, 5, look.ink, 10);
        float cx = Math.round(w / 2);
        p.rect(cx, -3, 1, 6, faint);
        p.rect(cx, h - 3, 1, 6, faint);
    }

    /** ☌ A sighting card: pointed sides at the title and registration crosses at the corners. */
    private void binding(Manuscript.Plan p, float w, float h, float titleMiddle) {
        float c = Math.round(titleMiddle);
        for (int i = 0; i < 5; i++) {
            p.rect(-i, c - 4 + i, i + 1, (4 - i) * 2 + 1, look.titleWash);
            p.rect(w - 1, c - 4 + i, i + 1, (4 - i) * 2 + 1, look.titleWash);
            p.rect(-i - 1, c - 5 + i, 1, 1, look.ink);
            p.rect(-i - 1, c + 5 - i, 1, 1, look.ink);
            p.rect(w + i, c - 5 + i, 1, 1, look.ink);
            p.rect(w + i, c + 5 - i, 1, 1, look.ink);
        }
        p.rect(-6, c, 1, 1, look.ink);
        p.rect(w + 5, c, 1, 1, look.ink);
        box(p, 0, 0, w, h, 0, look.ink, 10);
        for (float[] corner : new float[][]{{0, 0}, {w, 0}, {0, h}, {w, h}}) {
            p.rect(corner[0] - 4, corner[1] - (corner[1] == 0 ? 0 : 1), 9, 1, look.ink);
            p.rect(corner[0] - (corner[0] == 0 ? 0 : 1), corner[1] - 4, 1, 9, look.ink);
        }
    }

    /** 🜍 Red chalk with cut corners and small flames licking up from the top. */
    private void effect(Manuscript.Plan p, float w, float h, @Nullable Element element) {
        int cut = CUT;
        p.hStroke(cut, w - cut, 0, -1, look.ink, 10);
        p.hStroke(cut, w - cut, h - 1, 1, look.ink, 110);
        p.vStroke(0, cut, h - cut, -1, look.ink, 210);
        p.vStroke(w - 1, cut, h - cut, 1, look.ink, 310);
        for (int i = 0; i < cut; i++) {
            p.rect(i, cut - 1 - i, 1, 1, look.ink);
            p.rect(w - 1 - i, cut - 1 - i, 1, 1, look.ink);
            p.rect(i, h - cut + i, 1, 1, look.ink);
            p.rect(w - 1 - i, h - cut + i, 1, 1, look.ink);
        }
        // What the deed is made of shows at the upper corners: the element when the rune is told one.
        for (float fx : new float[]{cut + 3, w - cut - 9}) accent(p, fx, element);
    }

    /** The element named on the rune, if it is written there rather than wired in. */
    private @Nullable Element element() {
        LTypes.init();
        for (PortModel port : rune.model().getInputsById().values()) {
            if (port.getDataTypeHandle().equals(LTypes.ELEMENT) && !port.isConnected() && port.getEmbeddedValue() != null
                    && port.getEmbeddedValue().getValue() instanceof Element e) return e;
        }
        return null;
    }

    /** A small flourish above an effect's corner, standing on the row just above the frame. */
    private static void accent(Manuscript.Plan p, float x, @Nullable Element element) {
        int ink = Manuscript.INK;
        if (element == null) {
            // Plain red-chalk flicks: change, of no particular stuff.
            int chalk = Manuscript.SANGUINE;
            p.rect(x, -2, 1, 1, chalk);
            p.rect(x + 1, -4, 1, 3, chalk);
            p.rect(x + 3, -3, 1, 2, chalk);
            p.rect(x + 5, -2, 1, 1, chalk);
            return;
        }
        switch (element) {
            case FIRE -> {
                int ember = 0xFFD9772E;
                p.rect(x, -3, 1, 2, ember);
                p.rect(x + 1, -5, 1, 4, ink);
                p.rect(x + 2, -2, 1, 1, ember);
                p.rect(x + 3, -4, 1, 3, ink);
                p.rect(x + 5, -2, 1, 1, ink);
            }
            case FROST -> {
                int ice = 0xFF6FA8C8, rime = 0xFFE8F4FA;
                p.rect(x, -3, 1, 3, ice);
                p.rect(x + 2, -6, 1, 6, ice);
                p.rect(x + 2, -6, 1, 1, rime);
                p.rect(x + 4, -4, 1, 4, ice);
                p.rect(x + 1, -2, 1, 1, rime);
                p.rect(x + 3, -2, 1, 1, rime);
            }
            case LIGHTNING -> {
                int bolt = 0xFFE5C33A;
                p.rect(x + 3, -7, 1, 2, bolt);
                p.rect(x + 2, -5, 1, 1, bolt);
                p.rect(x + 1, -4, 3, 1, bolt);
                p.rect(x + 2, -3, 1, 1, bolt);
                p.rect(x + 1, -2, 1, 1, bolt);
                p.rect(x + 4, -7, 1, 1, ink);
                p.rect(x, -2, 1, 1, ink);
            }
            case VOID -> {
                int shard = 0xFF6B3F8C, deep = 0xFF2A1A3A;
                p.rect(x, -3, 2, 2, shard);
                p.rect(x + 3, -6, 1, 1, deep);
                p.rect(x + 4, -4, 2, 2, deep);
                p.rect(x + 2, -8, 1, 1, shard);
            }
            case PHYSICAL -> {
                int steel = 0xFF8C8C8C, edge = 0xFFF2F2F2;
                for (int i = 0; i < 4; i++) {
                    p.rect(x + i, -1 - i, 1, 1, steel);
                    p.rect(x + i + 1, -1 - i, 1, 1, edge);
                }
                p.rect(x + 4, -5, 1, 1, ink);
            }
            case ARCANE -> {
                int lilac = 0xFF9A84C4;
                p.rect(x + 2, -6, 1, 3, lilac);
                p.rect(x + 1, -5, 3, 1, lilac);
                p.rect(x + 5, -3, 1, 1, lilac);
            }
            case LIFE -> {
                int leaf = 0xFF6E9A4A;
                p.rect(x + 2, -5, 1, 5, leaf);
                p.rect(x, -4, 2, 1, leaf);
                p.rect(x + 3, -3, 2, 1, leaf);
            }
        }
    }

    /** ♄ An escapement: gear teeth along both sides and axles at the upper corners. */
    private void logic(Manuscript.Plan p, float w, float h) {
        for (float y = 6; y + 5 < h - 3; y += 10) {
            p.rect(-2, y, 2, 5, look.titleWash);
            p.rect(w, y, 2, 5, look.titleWash);
            p.rect(-3, y, 1, 5, look.ink);
            p.rect(-2, y - 1, 2, 1, look.ink);
            p.rect(-2, y + 5, 2, 1, look.ink);
            p.rect(w + 2, y, 1, 5, look.ink);
            p.rect(w, y - 1, 2, 1, look.ink);
            p.rect(w, y + 5, 2, 1, look.ink);
        }
        box(p, 0, 0, w, h, 0, look.ink, 10);
        for (float ax : new float[]{5, w - 5}) {
            p.ring(ax, 5, 2, look.ink);
            p.rect(ax - 1, 4, 1, 1, look.ink);
        }
    }

    /** ⚖ A figure of proportion: the circle drawn inside the square, as in the Vitruvian study. */
    private void value(Manuscript.Plan p, float w, float h) {
        int faint = 0x99000000 | (look.ink & 0xFFFFFF);
        // The Vitruvian circle, laid faintly under the text as a construction the figure was built on.
        int ghost = 0x30000000 | (look.ink & 0xFFFFFF);
        int big = (int) Math.max(4, Math.min(w, h) / 2 - 3);
        p.ring(Math.round(w / 2), Math.round(h / 2), big, ghost);
        p.rect(Math.round(w / 2) - 1, Math.round(h / 2) - 1, 1, 1, ghost);
        int r = (int) Math.min(7, Math.min(w, h) / 3);
        p.arc(r + 1, r + 1, r, faint, Manuscript.Plan.TOP_LEFT);
        p.arc(w - r - 1, r + 1, r, faint, Manuscript.Plan.TOP_RIGHT);
        p.arc(r + 1, h - r - 1, r, faint, Manuscript.Plan.BOTTOM_LEFT);
        p.arc(w - r - 1, h - r - 1, r, faint, Manuscript.Plan.BOTTOM_RIGHT);
        box(p, 0, 0, w, h, 2, look.ink, 10);
        float cx = Math.round(w / 2);
        // A scale of braccia along the foot, as on Leonardo's maps.
        for (float x = 4, i = 0; x < w - 3; x += 3, i++) {
            boolean major = i % 4 == 0;
            p.rect(x, h - (major ? 5 : 3), 1, major ? 4 : 2, major ? look.ink : faint);
        }
        // Divider points pricked through the paper at the top centre.
        p.rect(cx - 3, -3, 1, 2, look.ink);
        p.rect(cx - 2, -4, 1, 1, look.ink);
        p.rect(cx - 1, -5, 1, 1, look.ink);
        p.rect(cx, -4, 1, 1, look.ink);
        p.rect(cx + 1, -3, 1, 2, look.ink);
    }

    /** A loose red-chalk loop drawn round the figure, its ends crossing at the upper left. */
    private Manuscript.Plan circling(float w, float h) {
        Manuscript.Plan p = new Manuscript.Plan(seed + 7);
        int r = 7, chalk = Manuscript.SANGUINE, dust = 0x99000000 | (Manuscript.SANGUINE & 0xFFFFFF);
        float x0 = -6, y0 = -6, x1 = w + 6, y1 = h + 6;
        // Two passes of the chalk, the second a pixel inside and only half pressed.
        for (int pass = 0; pass < 2; pass++) {
            int color = pass == 0 ? chalk : dust, in = pass;
            p.hStroke(x0 + r, x1 - r, y0 + in, pass == 0 ? -1 : 0, color, 20 + pass);
            p.hStroke(x0 + r, x1 - r, y1 - 1 - in, pass == 0 ? 1 : 0, color, 22 + pass);
            p.vStroke(x0 + in, y0 + r, y1 - r, pass == 0 ? -1 : 0, color, 24 + pass);
            p.vStroke(x1 - 1 - in, y0 + r, y1 - r, pass == 0 ? 1 : 0, color, 26 + pass);
            p.arc(x0 + r, y0 + r, r - in, color, Manuscript.Plan.TOP_LEFT);
            p.arc(x1 - r, y0 + r, r - in, color, Manuscript.Plan.TOP_RIGHT);
            p.arc(x0 + r, y1 - r, r - in, color, Manuscript.Plan.BOTTOM_LEFT);
            p.arc(x1 - r, y1 - r, r - in, color, Manuscript.Plan.BOTTOM_RIGHT);
        }
        // The loop overruns where it began, as a quick hand's does.
        p.hStroke(x0 + 2, x0 + r + 9, y0 + 2, 0, chalk, 28);
        p.rect(x0 + r + 9, y0 + 3, 2, 1, dust);
        return p;
    }
}
