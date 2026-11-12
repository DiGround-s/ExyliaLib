package net.exylia.lib.util.teleport.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TeleporterBudgetTest {

    @Test
    void aBurstIsSpreadOverLaterTicks() {
        long last = 0;
        for (int i = 0; i < 50; i++) {
            last = Teleporter.nextSlot(5);
        }
        // 50 moves at 5 a tick need about 10 ticks; a slow machine may cross a tick meanwhile.
        assertTrue(last >= 7 && last <= 10, "last move waits " + last + " ticks");
    }
}
