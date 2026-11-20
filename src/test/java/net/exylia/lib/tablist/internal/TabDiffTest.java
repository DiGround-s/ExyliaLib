package net.exylia.lib.tablist.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TabDiffTest {

    @Test
    void sendsOnlyWhenTheValueChanges() {
        TabDiff diff = new TabDiff();
        assertFalse(diff.changed(TabDiff.HEADER, null), "nothing set, nothing sent");
        assertTrue(diff.changed(TabDiff.HEADER, "a"));
        assertFalse(diff.changed(TabDiff.HEADER, "a"));
        assertTrue(diff.changed(TabDiff.HEADER, "b"));
        assertTrue(diff.changed(TabDiff.HEADER, null), "a dropped field is a change back to vanilla");
        assertNull(diff.last(TabDiff.ORDER));
    }

    @Test
    void slotsAreIndependent() {
        TabDiff diff = new TabDiff();
        assertTrue(diff.changed(TabDiff.ORDER, 5));
        assertTrue(diff.changed(TabDiff.NAME, "5"));
        assertFalse(diff.changed(TabDiff.ORDER, 5));
        assertEquals(5, diff.last(TabDiff.ORDER));
    }
}
