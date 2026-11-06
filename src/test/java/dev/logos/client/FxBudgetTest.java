package dev.logos.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FxBudgetTest {
    @Test
    void effectsStopAtTheBudgetUntilTheNextTick() {
        FxBudget budget = new FxBudget();
        for (int i = 0; i < 64; i++) assertTrue(budget.effect(64), "effect " + i);
        assertFalse(budget.effect(64));
        budget.refill();
        assertTrue(budget.effect(64));
    }

    @Test
    void particlesAreGrantedUpToWhatIsLeft() {
        FxBudget budget = new FxBudget();
        assertEquals(64, budget.particles(64, 100));
        assertEquals(36, budget.particles(64, 100));
        assertEquals(0, budget.particles(1, 100));
        budget.refill();
        assertEquals(1, budget.particles(1, 100));
    }

    @Test
    void particlesAndEffectsAreCountedApart() {
        FxBudget budget = new FxBudget();
        assertEquals(10, budget.particles(10, 10));
        assertTrue(budget.effect(1));
        assertEquals(0, budget.particles(1, 10));
        assertFalse(budget.effect(1));
    }

    @Test
    void aBudgetOfNothingGrantsNothing() {
        FxBudget budget = new FxBudget();
        assertFalse(budget.effect(0));
        assertEquals(0, budget.particles(5, 0));
    }
}
