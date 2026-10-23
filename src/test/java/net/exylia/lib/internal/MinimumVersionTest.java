package net.exylia.lib.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinimumVersionTest {

    @Test
    void olderVersionsAreReported() {
        assertTrue(MinimumVersion.olderThan("1.21.1", "1.21.4"));
        assertTrue(MinimumVersion.olderThan("1.21", "1.21.4"));
        assertTrue(MinimumVersion.olderThan("1.20.6", "1.21.4"));
    }

    @Test
    void baselineAndNewerAreNot() {
        assertFalse(MinimumVersion.olderThan("1.21.4", "1.21.4"));
        assertFalse(MinimumVersion.olderThan("1.21.10", "1.21.4"));
        assertFalse(MinimumVersion.olderThan("1.21.11", "1.21.4"));
        assertFalse(MinimumVersion.olderThan("26.1", "1.21.4"));
        assertFalse(MinimumVersion.olderThan("26.1.2", "1.21.4"));
        assertFalse(MinimumVersion.olderThan("26.1-rc-1", "1.21.4"));
    }

    @Test
    void unreadableVersionsAreNot() {
        assertFalse(MinimumVersion.olderThan("", "1.21.4"));
        assertFalse(MinimumVersion.olderThan(null, "1.21.4"));
        assertFalse(MinimumVersion.olderThan("unknown", "1.21.4"));
    }
}
