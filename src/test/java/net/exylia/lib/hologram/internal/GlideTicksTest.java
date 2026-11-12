package net.exylia.lib.hologram.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlideTicksTest {

    @Test
    void aResizeGlidesToTheKeyframesMomentNotFaster() {
        assertEquals(14, HologramImpl.glideTicks(1700, 1000, 5));
        assertEquals(2, HologramImpl.glideTicks(1700, 1600, 5));
        assertEquals(1, HologramImpl.glideTicks(1700, 1690, 5));
        assertEquals(5, HologramImpl.glideTicks(1700, 1700, 5));
        assertEquals(5, HologramImpl.glideTicks(0, 1000, 5));
    }
}
