package dev.logos.client.port;

import java.util.Optional;

/** Stands in for 26.1's {@code com.mojang.blaze3d.pipeline.ColorTargetState}: the blending, if any, and which channels are written. */
public record ColorTargetState(Optional<BlendFunction> blendFunction, int writeMask) {
    public static final int WRITE_COLOR = 7;
    public static final int WRITE_ALL = 15;
    public static final ColorTargetState DEFAULT = new ColorTargetState(Optional.empty(), WRITE_ALL);

    public ColorTargetState(BlendFunction blend) {
        this(Optional.of(blend), WRITE_ALL);
    }
}
