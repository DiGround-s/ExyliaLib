package net.exylia.lib.screen.internal;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MiniMapTest {

    @Test
    void discClearsTheCornersAndKeepsTheMiddle() {
        int size = 16;
        int[] argb = new int[size * size];
        Arrays.fill(argb, 0xFF00FF00);
        MiniMap.disc(argb, size, 0xFF8A51C4);
        assertEquals(0, argb[0]);
        assertEquals(0, argb[size * size - 1]);
        assertEquals(0xFF8A51C4, argb[size / 2 * size]);
        assertEquals(0xFF00FF00, argb[size / 2 * size + size / 2]);
    }

    @Test
    void markerPointsUpOverTheGround() {
        int size = 16;
        int[] argb = new int[size * size];
        Arrays.fill(argb, 0xFF00FF00);
        MiniMap.marker(argb, size);
        int c = size / 2;
        assertEquals(0xFFFFFFFF, argb[(c - 1) * size + c]);
        assertNotEquals(0xFFFFFFFF, argb[(c - 1) * size + c - 1]);
        assertNotEquals(0xFFFFFFFF, argb[(c - 2) * size + c]);
        assertEquals(0xFFFFFFFF, argb[(c + 1) * size + c + 2]);
    }

    @Test
    void shadeDarkens() {
        assertEquals(0xFF6E6E6E, MiniMap.shade(0xFFFFFFFF, 0.432));
    }
}
