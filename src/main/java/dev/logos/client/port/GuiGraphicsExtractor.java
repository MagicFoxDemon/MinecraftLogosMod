package dev.logos.client.port;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/**
 * Stands in for 26.1's {@code net.minecraft.client.gui.GuiGraphicsExtractor}, as far as the mod's screens and HUD use
 * it, over 1.21.1's {@link GuiGraphics}: everything is drawn at once, in the order asked.
 */
public final class GuiGraphicsExtractor {
    private final GuiGraphics graphics;

    public GuiGraphicsExtractor(GuiGraphics graphics) {
        this.graphics = graphics;
    }

    public GuiGraphics graphics() {
        return graphics;
    }

    public int guiWidth() {
        return graphics.guiWidth();
    }

    public int guiHeight() {
        return graphics.guiHeight();
    }

    public void fill(int x0, int y0, int x1, int y1, int color) {
        graphics.fill(x0, y0, x1, y1, color);
    }

    /** A one-pixel frame around {@code width} by {@code height} at ({@code x}, {@code y}). */
    public void outline(int x, int y, int width, int height, int color) {
        graphics.renderOutline(x, y, width, height, color);
    }

    public void text(Font font, String text, int x, int y, int color) {
        graphics.drawString(font, text, x, y, color);
    }

    public void text(Font font, Component text, int x, int y, int color) {
        graphics.drawString(font, text, x, y, color);
    }

    public void text(Font font, FormattedCharSequence text, int x, int y, int color) {
        graphics.drawString(font, text, x, y, color);
    }

    public void text(Font font, String text, int x, int y, int color, boolean shadow) {
        graphics.drawString(font, text, x, y, color, shadow);
    }

    public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
        graphics.drawString(font, text, x, y, color, shadow);
    }

    public void centeredText(Font font, String text, int x, int y, int color) {
        graphics.drawCenteredString(font, text, x, y, color);
    }

    public void centeredText(Font font, Component text, int x, int y, int color) {
        graphics.drawCenteredString(font, text, x, y, color);
    }

    public void item(ItemStack stack, int x, int y) {
        graphics.renderItem(stack, x, y);
    }

    /** The texture's ({@code u0}, {@code v0})–({@code u1}, {@code v1}) part, in 0–1, stretched over the box. */
    public void blit(ResourceLocation texture, int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1) {
        int w = x1 - x0, h = y1 - y0;
        float texW = w / (u1 - u0), texH = h / (v1 - v0);
        graphics.blit(texture, x0, y0, u0 * texW, v0 * texH, w, h, Math.round(texW), Math.round(texH));
    }

    /** What is drawn after this lies over what came before: drawing moves a little towards the viewer. */
    public void nextStratum() {
        graphics.pose().translate(0, 0, 1);
    }
}
