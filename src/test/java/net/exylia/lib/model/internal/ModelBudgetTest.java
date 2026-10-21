package net.exylia.lib.model.internal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The ceiling pushes viewers further away; it never refuses them. */
class ModelBudgetTest {

    @AfterEach
    void reset() {
        ModelRuntime.load(-ModelRuntime.load());
        ModelRuntime.ceiling(30_000);
    }

    @Test
    void pressureRisesWithTheLoad() {
        ModelRuntime.ceiling(1_000);
        assertEquals(0, ModelRuntime.pressure());
        ModelRuntime.load(749);
        assertEquals(0, ModelRuntime.pressure());
        ModelRuntime.load(1);
        assertEquals(1, ModelRuntime.pressure());
        ModelRuntime.load(250);
        assertEquals(2, ModelRuntime.pressure());
        ModelRuntime.load(-1_000);
        assertEquals(0, ModelRuntime.pressure());
    }

    @Test
    void noCeilingIsNoPressure() {
        ModelRuntime.ceiling(0);
        ModelRuntime.load(10_000_000);
        assertEquals(0, ModelRuntime.pressure());
    }
}
