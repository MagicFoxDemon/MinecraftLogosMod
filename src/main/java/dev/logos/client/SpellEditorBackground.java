package dev.logos.client;

import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;

/** Nine-slices the original artwork so window aspect ratios never stretch its brass corners. */
final class SpellEditorBackground implements GuiTexture {
    private final SpriteTexture[][] patches = new SpriteTexture[3][3];

    SpellEditorBackground() {
        int[] xs = {0, 128, 1544, 1672};
        int[] ys = {0, 128, 813, 941};
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                patches[row][col] = SpriteTexture.of("logos:textures/gui/spell_editor_parchment.png")
                        .setSprite(xs[col], ys[row], xs[col + 1] - xs[col], ys[row + 1] - ys[row]);
            }
        }
    }

    @Override
    public void draw(GUIContext context, float x, float y, float width, float height) {
        float corner = Math.min(40, Math.min(width, height) / 2);
        float[] xs = {x, x + corner, x + width - corner, x + width};
        float[] ys = {y, y + corner, y + height - corner, y + height};
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                context.drawTexture(patches[row][col], xs[col], ys[row], xs[col + 1] - xs[col], ys[row + 1] - ys[row]);
            }
        }
    }
}
