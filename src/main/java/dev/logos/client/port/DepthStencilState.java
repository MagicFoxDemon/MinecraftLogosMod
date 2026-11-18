package dev.logos.client.port;

/** Stands in for 26.1's {@code com.mojang.blaze3d.pipeline.DepthStencilState}: the depth test, and whether depth is written. */
public record DepthStencilState(CompareOp depthTest, boolean writeDepth) {
    public static final DepthStencilState DEFAULT = new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true);
}
