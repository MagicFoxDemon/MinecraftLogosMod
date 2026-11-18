package dev.logos.client.port;

import org.lwjgl.opengl.GL11;

/** Stands in for 26.1's {@code com.mojang.blaze3d.platform.CompareOp}: a depth test. */
public enum CompareOp {
    ALWAYS_PASS(GL11.GL_ALWAYS),
    LESS_THAN(GL11.GL_LESS),
    LESS_THAN_OR_EQUAL(GL11.GL_LEQUAL),
    EQUAL(GL11.GL_EQUAL),
    GREATER_THAN(GL11.GL_GREATER);

    final int gl;

    CompareOp(int gl) {
        this.gl = gl;
    }
}
