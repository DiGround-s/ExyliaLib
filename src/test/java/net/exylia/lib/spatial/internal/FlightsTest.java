package net.exylia.lib.spatial.internal;

import net.exylia.lib.spatial.SpatialLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The flight preview's arc: it lands on the target and never leaves the room the default layout gives it. */
class FlightsTest {

    private static final double SCALE = new SpatialLayout.Preview().scale();

    @Test
    void setsOffOnTheLeftAndLandsOnTheTargetsFace() {
        double[] start = Flights.at(0, SCALE);
        double[] end = Flights.at(1, SCALE);
        assertTrue(start[0] < -1.0 * SCALE, "sets off well left of the pedestal");
        assertEquals(-Flights.TARGET / 2 * SCALE, end[0], 1e-9);
        assertEquals(Flights.TARGET / 2 * SCALE, end[1], 1e-9);
    }

    @Test
    void staysBetweenTheSidePanelsAndUnderTheFocusText() {
        SpatialLayout.Preview preview = new SpatialLayout.Preview();
        for (int i = 0; i <= 100; i++) {
            double[] here = Flights.at(i / 100.0, SCALE);
            // The default side panels' inner edges are 1.375 either side; the focus text's bottom is at 0.84.
            assertTrue(Math.abs(here[0] + preview.at().get(0)) < 1.3, "across " + here[0]);
            assertTrue(here[1] + preview.at().get(1) < 0.6, "up " + here[1]);
            assertTrue(here[1] >= 0, "never under the pedestal's top");
        }
    }

    @Test
    void headingIsTheWayItMoves() {
        for (double t = 0.05; t < 1; t += 0.1) {
            double[] a = Flights.at(t - 1e-4, 1), b = Flights.at(t + 1e-4, 1);
            double[] heading = Flights.heading(t, 1);
            assertEquals(heading[0], (b[0] - a[0]) / 2e-4, 1e-3);
            assertEquals(heading[1], (b[1] - a[1]) / 2e-4, 1e-3);
        }
    }
}
