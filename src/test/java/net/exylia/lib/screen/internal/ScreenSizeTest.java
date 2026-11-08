package net.exylia.lib.screen.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScreenSizeTest {

    @Test
    void sizeStaysWithinWhatCanBeDrawn() {
        assertEquals(50, ScreenRuntime.clampSize(10));
        assertEquals(100, ScreenRuntime.clampSize(100));
        assertEquals(250, ScreenRuntime.clampSize(900));
    }
}
