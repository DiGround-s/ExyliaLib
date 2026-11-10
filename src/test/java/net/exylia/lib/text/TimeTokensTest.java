package net.exylia.lib.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Countdowns and clocks written into text. */
class TimeTokensTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    /** 2026-11-10 12:00:00 UTC. */
    private static final long NOW = ZonedDateTime.of(2026, 11, 10, 12, 0, 0, 0, UTC).toInstant().toEpochMilli();

    @Test
    @DisplayName("a countdown reads the time left, leading zero units left out")
    void countsDown() {
        assertEquals("Opens in 21d 6h 0m 5s",
                TimeTokens.resolve("Opens in {countdown:2026-12-01 18:00:05 UTC}", NOW));
        assertEquals("1h 30m 0s", TimeTokens.resolve("{countdown:2026-11-10 13:30 UTC}", NOW));
        assertEquals("21d 12h 0m 0s", TimeTokens.resolve("{countdown:2026-12-02 UTC}", NOW));
        assertEquals("12s", TimeTokens.countdown(12));
        assertEquals("5m 0s", TimeTokens.countdown(300));
    }

    @Test
    @DisplayName("a passed countdown reads 0s or its own text, braces and all")
    void afterItPasses() {
        assertEquals("0s", TimeTokens.resolve("{countdown:2026-11-01 UTC}", NOW));
        assertEquals("{success}Open now!",
                TimeTokens.resolve("{countdown:2026-11-01 UTC|{success}Open now!}", NOW));
        assertEquals("1s", TimeTokens.resolve("{countdown:2026-11-10 12:00:00 UTC}", NOW - 500),
                "half a second before it, it still reads one");
    }

    @Test
    @DisplayName("a zone moves the moment it counts to")
    void zones() {
        // Madrid is UTC+1 in November: 13:00 there is 12:00 UTC, an hour after 11:00 UTC.
        assertEquals("1h 0m 0s", TimeTokens.resolve("{countdown:2026-11-10 13:00 Europe/Madrid}",
                NOW - 3_600_000));
        assertEquals("13:00", TimeTokens.resolve("{date:HH:mm Europe/Madrid}", NOW));
    }

    @Test
    @DisplayName("a date token formats now with its pattern")
    void dates() {
        assertEquals("12:00 · 10 Nov 2026", TimeTokens.resolve("{date:HH:mm UTC} · {date:dd MMM yyyy UTC}", NOW));
        assertEquals("+0000", TimeTokens.resolve("{date:Z UTC}", NOW), "a pattern letter is not a zone");
    }

    @Test
    @DisplayName("a token that does not read stays as written, other braces are untouched")
    void typosStay() {
        assertEquals("{countdown:soon} {primary}", TimeTokens.resolve("{countdown:soon} {primary}", NOW));
        assertEquals("{date:qqqqqqq}", TimeTokens.resolve("{date:qqqqqqq}", NOW));
        assertEquals("{countdown:2026-12-01", TimeTokens.resolve("{countdown:2026-12-01", NOW));
        assertTrue(TimeTokens.contains("a {date:HH} b"));
        assertFalse(TimeTokens.contains("{primary}Shop"));
    }

    @Test
    @DisplayName("a token's body can be filled in first, for placeholders")
    void bodyIsFilledFirst() {
        assertEquals("1h 0m 0s", TimeTokens.resolve("{countdown:%start%}", NOW,
                body -> body.replace("%start%", "2026-11-10 13:00 UTC")));
    }
}
