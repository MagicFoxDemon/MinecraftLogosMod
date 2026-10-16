package dev.logos.client;

import java.util.List;
import org.joml.Vector2f;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.Style;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextArea;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.dependency.ModelUpdateVisitor;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.wiget.PlacematElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.wiget.StickyNoteElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.wiget.PlacematModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.wiget.StickyNoteModel;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Paper annotations share the workbench's ink palette while retaining toolkit editing behavior. */
final class SpellAnnotations {
    private static final int INK = 0xFF57391F;
    private static final IGuiTexture PAPER = SpriteTexture.of("logos:textures/gui/spell_editor_note.png")
            .setSprite(0, 0, 192, 128).setBorder(16);

    static final class PaperNote extends StickyNoteElement {
        private final GuiTexture paper = this::drawPaper;
        private Label summary;

        PaperNote(StickyNoteModel model) {
            super(model);
        }

        @Override
        protected void buildUI() {
            super.buildUI();
            summary = new Label();
            summary.addClass("spell-note-summary");
            summary.layout(l -> l.flex(1).minWidth(0).height(12).marginRight(5));
            summary.textStyle(s -> s.textColor(INK).textShadow(false).fontSize(9));
            select(".__sticky-note_header__").findFirst().orElseThrow().addChild(summary);
            addEventListener(UIEvents.TICK, ev -> updateSummary());
            updateSummary();
            paperStyle();
        }

        private void updateSummary() {
            if (summary == null) return;
            summary.setDisplay(getModel().isCollapsed() ? TaffyDisplay.FLEX : TaffyDisplay.NONE);
            String firstLine = getModel().getContent().lines().filter(line -> !line.isBlank()).findFirst()
                    .orElseGet(() -> Component.translatable("logos.editor.note").getString());
            summary.setText(Minecraft.getInstance().font.plainSubstrByWidth(firstLine,
                    Math.max(0, (int) getSizeWidth() - 28)));
        }

        private void drawPaper(GUIContext context, float x, float y, float width, float height) {
            if (!getModel().isCollapsed()) {
                context.drawTexture(PAPER, x, y, width, height);
                return;
            }
            // A collapsed note is a small paper label, never a vertically squeezed full sheet.
            int edge = isSelected() ? 0xFF805024 : 0xFF977045;
            DrawerHelperClient.drawSolidRect(context, x + 2, y + 2, width - 2, height - 1, 0x403A2818);
            DrawerHelperClient.drawSolidRect(context, x + 1, y + 1, width - 2, height - 2, 0xFFF6E4BB);
            DrawerHelperClient.drawSolidRect(context, x + 2, y, width - 4, 1, edge);
            DrawerHelperClient.drawSolidRect(context, x + 2, y + height - 1, width - 4, 1, edge);
            DrawerHelperClient.drawSolidRect(context, x, y + 2, 1, height - 4, edge);
            DrawerHelperClient.drawSolidRect(context, x + width - 1, y + 2, 1, height - 4, edge);
            DrawerHelperClient.drawSolidRect(context, x + 2, y + 1, width - 4, 1, 0xFFFFF1D1);
        }

        @Override
        public void updateUIFromModel(ModelUpdateVisitor visitor) {
            super.updateUIFromModel(visitor);
            paperStyle();
        }

        private void paperStyle() {
            Style.importantPipeline(getStyle(), s -> s.backgroundTexture(paper));
            select(".__sticky-note_text-area__", TextArea.class).forEach(area -> {
                Style.importantPipeline(area.getLayout(), l -> l.marginLeft(10).marginRight(12)
                        .marginBottom(10).widthAuto().minWidth(0));
                Style.importantPipeline(area.getTextAreaStyle(), s -> s.textColor(INK)
                        .cursorColor(INK).textShadow(false));
            });
            select(".__sticky-note_header__").forEach(header ->
                    Style.importantPipeline(header.getLayout(), l -> l.paddingLeft(6)));
        }

        @Override
        public void drawBackgroundOverlay(GUIContext context) {
            if (getModel().isCollapsed()) return;
            if (isSelected() || isSelfOrChildHover() || isUnderRegionSelection()) {
                roughBorder(context, this, isSelected() ? 0xFF9A642C : 0x99754F2B, 2);
            }
        }
    }

    static final class InkRegion extends PlacematElement {
        InkRegion(PlacematModel model) {
            super(model);
        }

        @Override
        protected void buildUI() {
            super.buildUI();
            inkStyle();
        }

        @Override
        public void updateUIFromModel(ModelUpdateVisitor visitor) {
            super.updateUIFromModel(visitor);
            inkStyle();
        }

        private void inkStyle() {
            Style.importantPipeline(getStyle(), s -> s.backgroundTexture(IGuiTexture.EMPTY));
            select(".__placemat_title__", Label.class).forEach(label ->
                    Style.importantPipeline(label.getTextStyle(), s -> s.textColor(INK).textShadow(false).fontSize(12)));
        }

        private void drawHatching(GUIContext context) {
            float x = getPositionX() + 4, y = getPositionY() + TITLE_BAR_HEIGHT;
            float w = getSizeWidth() - 8, h = getSizeHeight() - TITLE_BAR_HEIGHT - 4;
            if (w <= 0 || h <= 0) return;
            context.enableScissor(x, y, w, h);
            try {
                // Submit whole strokes so large grouping regions do not draw thousands of tiny quads.
                for (float start = -h; start < w; start += 18) {
                    float from = Math.max(0, -start), to = Math.min(h, w - start);
                    if (to <= from) continue;
                    DrawerHelperClient.drawLines(context, List.of(new Vector2f(x + start + from, y + h - from),
                            new Vector2f(x + start + to, y + h - to)), 0x2857391F, 0x2857391F, 1);
                }
            } finally {
                context.disableScissor();
            }
        }

        @Override
        public void drawBackgroundOverlay(GUIContext context) {
            drawHatching(context);
            int color = isSelected() ? 0xFF915B25
                    : isSelfOrChildHover() || isUnderRegionSelection() ? 0xD057391F : 0xA057391F;
            roughBorder(context, this, color, 1);
            if (isSelected() || isSelfOrChildHover()) {
                float x = getPositionX() + getSizeWidth() - 5, y = getPositionY() + getSizeHeight() - 5;
                DrawerHelperClient.drawSolidRect(context, x - 4, y, 5, 1, color);
                DrawerHelperClient.drawSolidRect(context, x, y - 4, 1, 5, color);
            }
        }
    }

    /** Small repeatable offsets suggest a quill stroke without flickering as the canvas pans. */
    private static void roughBorder(GUIContext context, UIElement element, int color, float inset) {
        float x = element.getPositionX() + inset, y = element.getPositionY() + inset;
        float w = element.getSizeWidth() - inset * 2, h = element.getSizeHeight() - inset * 2;
        for (int i = 0; i < w; i += 9) {
            float offset = (i / 9 % 3 == 1) ? 1 : 0;
            float length = Math.min(9, w - i);
            DrawerHelperClient.drawSolidRect(context, x + i, y + offset, length, 2, color);
            DrawerHelperClient.drawSolidRect(context, x + i, y + h - 2 - offset, length, 2, color);
        }
        for (int i = 0; i < h; i += 9) {
            float offset = (i / 9 % 3 == 2) ? 1 : 0;
            float length = Math.min(9, h - i);
            DrawerHelperClient.drawSolidRect(context, x + offset, y + i, 2, length, color);
            DrawerHelperClient.drawSolidRect(context, x + w - 2 - offset, y + i, 2, length, color);
        }
    }
}
