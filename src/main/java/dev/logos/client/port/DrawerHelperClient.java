package dev.logos.client.port;

import java.util.List;

import org.joml.Vector2f;

import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib2.math.Rect;

/** Stands in for LDLib2 26.1's {@code DrawerHelperClient}, which draws into a GUI context; 1.21.1's {@link DrawerHelper} draws into its graphics. */
public final class DrawerHelperClient {
    private DrawerHelperClient() {}

    public static void drawSolidRect(GUIContext context, float x, float y, float width, float height, int color) {
        DrawerHelper.drawSolidRect(context.graphics, x, y, width, height, color);
    }

    public static void drawSolidRect(GUIContext context, Rect rect, int color) {
        DrawerHelper.drawSolidRect(context.graphics, rect, color);
    }

    public static void drawLines(GUIContext context, List<Vector2f> points, int startColor, int endColor, float width) {
        DrawerHelper.drawLines(context.graphics, points, startColor, endColor, width);
    }
}
