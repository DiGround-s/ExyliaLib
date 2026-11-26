package net.exylia.lib.screen.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FitPanelTest {

    private static double[] corner(double fov, double aspect) {
        double tanY = Math.tan(Math.toRadians(fov / 2));
        return new double[]{-tanY * aspect, tanY};
    }

    @Test
    void oneCornerGivesFovAndShape() {
        double[] at = corner(70, 16.0 / 9);
        assertArrayEquals(new double[]{70, 1.78}, FitPanel.measured(at[0], at[1]), 1e-9);
        at = corner(95, 2.33);
        assertArrayEquals(new double[]{95, 2.33}, FitPanel.measured(at[0], at[1]), 1e-9);
        at = corner(110, 1.6);
        assertArrayEquals(new double[]{110, 1.6}, FitPanel.measured(-at[0], -at[1]), 1e-9, "any corner");
    }

    @Test
    void pointsNoWindowHasACornerAtAreRefused() {
        assertNull(FitPanel.measured(0.05, 0.03), "near the middle");
        assertNull(FitPanel.measured(1.2, 0.01), "beside the middle");
        assertNull(FitPanel.measured(0.01, 0.7), "above the middle");
        double[] at = corner(112, 1.78);
        assertEquals(110, FitPanel.measured(at[0], at[1])[0], 1e-9, "just past the slider is its end");
    }

    @Test
    void shapesReadAsPeopleNameThem() {
        assertEquals("16:9", FitPanel.shape(1.77));
        assertEquals("21:9", FitPanel.shape(2.37));
        assertEquals("1.90:1", FitPanel.shape(1.9));
    }
}
