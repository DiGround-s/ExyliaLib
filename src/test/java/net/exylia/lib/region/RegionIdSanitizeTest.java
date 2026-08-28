package net.exylia.lib.region;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Turning what an admin typed into an id this grammar accepts.
 *
 * <p>The case that made this necessary: every capture event built its zone id
 * as {@code eventId + "/zone"}, and a slash is not in the grammar — so starting
 * a KOTH threw out of the region constructor and the event never began.
 */
class RegionIdSanitizeTest {

    @Test
    @DisplayName("a slash — the one that broke a live server — becomes a hyphen")
    void slash() {
        assertEquals("test-zone", RegionId.sanitize("test/zone"));
        assertEquals("test-zone-0", RegionId.sanitize("test/zone-0"));
    }

    @Test
    @DisplayName("what it produces is always something the record accepts")
    void alwaysConstructible() {
        for (String raw : new String[] {"Arena Two/zone", "koth:main", "a // b",
                "  padded  ", "ÑOÑO", "MiXeD.Case_1"}) {
            new RegionId("capture", RegionId.sanitize(raw));   // throws if invalid
        }
    }

    @Test
    @DisplayName("a run of rubbish collapses into one hyphen")
    void collapses() {
        assertEquals("a-b", RegionId.sanitize("a // b"));
    }

    @Test
    @DisplayName("what is already valid is left alone")
    void untouched() {
        assertEquals("koth_main.1-a", RegionId.sanitize("koth_main.1-a"));
    }

    @Test
    @DisplayName("text with nothing usable in it is refused rather than guessed at")
    void nothingUsable() {
        assertThrows(IllegalArgumentException.class, () -> RegionId.sanitize("///"));
        assertThrows(IllegalArgumentException.class, () -> RegionId.sanitize("   "));
    }
}
