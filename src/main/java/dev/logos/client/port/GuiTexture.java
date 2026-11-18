package dev.logos.client.port;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Stands in for LDLib2 26.1's {@code GuiTexture}: a texture drawn by a function of the GUI context and the box. LDLib2
 * 1.21.1 draws a texture through its context too; drawn straight into graphics, it gets a context made of them.
 */
@FunctionalInterface
public interface GuiTexture extends IGuiTexture {
    @Override
    void draw(GUIContext context, float x, float y, float width, float height);

    @Override
    default void draw(GuiGraphics graphics, float mouseX, float mouseY, float x, float y, float width, float height, float partialTicks) {
        draw(GUIContext.of(null, graphics, (int) mouseX, (int) mouseY, partialTicks), x, y, width, height);
    }

    @Override
    default IGuiTexture copy() {
        return this;
    }

    static GuiTexture of(GuiTexture texture) {
        return texture;
    }
}
