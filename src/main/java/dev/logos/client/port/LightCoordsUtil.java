package dev.logos.client.port;

import net.minecraft.client.renderer.LightTexture;

/** Stands in for 26.1's {@code net.minecraft.util.LightCoordsUtil}: packed block and sky light. */
public final class LightCoordsUtil {
    public static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

    private LightCoordsUtil() {}

    public static int pack(int block, int sky) {
        return LightTexture.pack(block, sky);
    }
}
