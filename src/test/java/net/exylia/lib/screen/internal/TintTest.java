package net.exylia.lib.screen.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class TintTest {

    @Test
    void aTintMultipliesEveryPixelAndKeepsItsAlpha() {
        int[] out = Pictures.tinted(new int[]{0xFFFFFFFF, 0x80FF8000, 0x00000000}, "#808080");
        assertArrayEquals(new int[]{0xFF808080, 0x80804000, 0x00000000}, out);
    }
}
