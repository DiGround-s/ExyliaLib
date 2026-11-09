package net.exylia.lib.screen.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OutlineEdgesTest {

    /** A box with an outline and no fill must show what is behind it: no edge reaches the middle, and they meet at the corners. */
    @Test
    void anUnfilledOutlineLeavesItsMiddleOpen() {
        double[][] edges = LiveScreen.edges(10, 20, 8, 6, 1);
        assertEquals(4, edges.length);
        double area = 0;
        for (double[] e : edges) {
            area += e[2] * e[3];
            boolean coversMiddle = Math.abs(10 - e[0]) < e[2] / 2 && Math.abs(20 - e[1]) < e[3] / 2;
            assertFalse(coversMiddle);
        }
        assertEquals(8 * 6 - 6 * 4, area, 1e-9);
    }
}
