package net.exylia.lib.screen.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A window's hole: what is left of a rect covers it exactly but for the hole. */
class WindowCutTest {

    private static double area(List<double[]> pieces) {
        return pieces.stream().mapToDouble(piece -> piece[2] * piece[3]).sum();
    }

    @Test
    void aHoleInTheMiddleLeavesFourPieces() {
        List<double[]> pieces = ScreenMath.around(0, 0, 10, 10, 1, 1, 2, 4);
        assertEquals(4, pieces.size());
        assertEquals(100 - 8, area(pieces), 1e-9);
    }

    @Test
    void aHoleAtAnEdgeLeavesThree() {
        List<double[]> pieces = ScreenMath.around(0, 0, 10, 10, 4, 0, 2, 4);
        assertEquals(3, pieces.size());
        assertEquals(100 - 8, area(pieces), 1e-9);
    }

    @Test
    void aHolePastTheEdgeOnlyTakesWhatOverlaps() {
        List<double[]> pieces = ScreenMath.around(0, 0, 10, 10, 5, 0, 2, 2);
        assertEquals(100 - 2, area(pieces), 1e-9);
    }
}
