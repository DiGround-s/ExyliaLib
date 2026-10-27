package net.exylia.lib.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A typed command's label, and the allowlist check built on it. */
class CommandLabelsTest {

    @Test
    void labelDropsSlashNamespaceAndCase() {
        assertEquals("msg", CommandLabels.label("/Essentials:MSG Bob hi"));
        assertEquals("spawn", CommandLabels.label("spawn"));
        assertEquals("", CommandLabels.label("/"));
        assertEquals("", CommandLabels.label(null));
    }

    @Test
    void listedMatchesEntriesWithOrWithoutSlash() {
        List<String> allowed = List.of("/msg", "R", "rk");
        assertTrue(CommandLabels.listed("/msg Bob", allowed));
        assertTrue(CommandLabels.listed("/r hey", allowed));
        assertTrue(CommandLabels.listed("/exyliarandomkits:rk leave", allowed));
        assertFalse(CommandLabels.listed("/spawn", allowed));
        assertFalse(CommandLabels.listed("/", allowed));
        assertFalse(CommandLabels.listed("/msgx", allowed));
    }
}
