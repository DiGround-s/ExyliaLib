package net.exylia.lib.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickGuardTest {

    private static final long MS = 1_000_000L;

    @AfterEach
    void reset() {
        ClickGuard.resetClock();
        ClickGuard.cooldown(ClickGuard.DEFAULT_COOLDOWN);
    }

    @Test
    @DisplayName("a burst inside the cooldown is one click; the next deliberate one passes")
    void debounces() {
        AtomicLong now = new AtomicLong(1_000 * MS);
        ClickGuard.setClock(now::get);
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertTrue(ClickGuard.tryClick(player));
        now.addAndGet(120 * MS); // the release burst of a shift-double-click
        assertFalse(ClickGuard.tryClick(player));
        assertTrue(ClickGuard.tryClick(other), "per player");
        now.addAndGet(30 * MS); // 150 ms after the accepted one, not the refused one
        assertTrue(ClickGuard.tryClick(player));

        assertTrue(ClickGuard.tryClick(player, Duration.ZERO), "zero turns it off");
        ClickGuard.forget(player);
        assertTrue(ClickGuard.tryClick(player), "forgotten on quit");
    }
}
