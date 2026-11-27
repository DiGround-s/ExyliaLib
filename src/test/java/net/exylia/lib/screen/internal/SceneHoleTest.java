package net.exylia.lib.screen.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SceneHoleTest {

    @Test
    void clearsOnlyThePixelsTheWindowReaches() {
        // 4 x 2 picture of 10-unit cells centred on 0: columns span -20..20, rows 10..-10.
        int[] px = new int[8];
        java.util.Arrays.fill(px, 0xFF112233);
        // A window over the top-right cell's left half (x 0..5, y 0..10) clears just that cell.
        LiveScreen.clearHole(px, 4, 2, 10, 2.5, 5, 5, 10, 0);
        assertEquals(0, px[2]);
        for (int i : new int[]{0, 1, 3, 4, 5, 6, 7}) assertEquals(0xFF112233, px[i]);
        // The drift widens it into the next cells.
        LiveScreen.clearHole(px, 4, 2, 10, 2.5, 5, 5, 10, 1);
        assertEquals(0, px[1]);
        assertEquals(0, px[6]);
    }
}
