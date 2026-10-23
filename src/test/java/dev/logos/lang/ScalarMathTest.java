package dev.logos.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScalarMathTest {
    @Test
    void blendKeepsEndpointsAndExtrapolates() {
        assertEquals(10, ScalarMath.lerp(10, 20, 0));
        assertEquals(20, ScalarMath.lerp(10, 20, 1));
        assertEquals(12.5, ScalarMath.lerp(10, 20, 0.25));
        assertEquals(0, ScalarMath.lerp(10, 20, -1));
        assertEquals(30, ScalarMath.lerp(10, 20, 2));
        assertEquals(5, ScalarMath.lerp(10, 0, 0.5));
        assertEquals(0, ScalarMath.lerp(-Double.MAX_VALUE, Double.MAX_VALUE, 0.5));
    }

    @Test
    void boundsAndNegativeRoundingAreExplicit() {
        assertEquals(0, ScalarMath.clamp(-4, 0, 1));
        assertEquals(1, ScalarMath.clamp(4, 0, 1));
        assertEquals(0.25, ScalarMath.clamp(0.25, 1, 0));
        assertEquals(7, ScalarMath.clamp(2, 7, 7));
        assertEquals(0, ScalarMath.round(Math.nextDown(0.5), "nearest"));
        assertEquals(2, ScalarMath.round(1.5, "nearest"));
        assertEquals(-2, ScalarMath.round(-1.5, "nearest"));
        assertEquals(-1, ScalarMath.round(-1.2, "up"));
        assertEquals(-2, ScalarMath.round(-1.2, "down"));
        assertEquals(0x1.0p52 + 1, ScalarMath.round(0x1.0p52 + 1, "nearest"));
    }

    @Test
    void lotsUseDirectedRealIntervalsAndInclusiveIntegerBounds() {
        assertEquals(0, ScalarMath.random(0, 5, false, 0));
        assertEquals(-2.5, ScalarMath.random(0, -5, false, 0.5));
        assertTrue(ScalarMath.random(2, 6, false, Math.nextDown(1.0)) < 6);
        assertTrue(ScalarMath.random(0, -5, false, Math.nextDown(1.0)) > -5);
        assertEquals(2, ScalarMath.random(2, 6, true, 0));
        assertEquals(6, ScalarMath.random(2, 6, true, Math.nextDown(1.0)));
        assertEquals(2, ScalarMath.random(6, 2, true, 0));
        assertEquals(-2, ScalarMath.random(-2.8, -1.2, true, 0));
        assertEquals(0, ScalarMath.random(0.2, 0.8, true, 0.5));
        assertEquals(4, ScalarMath.random(4, 4, true, 0.9));
        for (int i = 0; i < 10000; i++) {
            double draw = i / 10000.0;
            double value = ScalarMath.random(-4.8, 8.2, true, draw);
            assertTrue(value >= -4 && value <= 8 && value == Math.floor(value));
        }
    }
}
