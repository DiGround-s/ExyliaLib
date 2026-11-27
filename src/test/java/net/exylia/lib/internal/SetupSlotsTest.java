package net.exylia.lib.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SetupSlotsTest {

    @Test
    void centresRowsByTheMenuRule() {
        assertEquals("13", SetupMenu.slots(1, 1));
        assertEquals("12,14", SetupMenu.slots(2, 1));
        assertEquals("11,12,14,15", SetupMenu.slots(4, 1));
        assertEquals("11,12,13,14,15", SetupMenu.slots(5, 1));
        assertEquals("10,11,12,14,15,16", SetupMenu.slots(6, 1));
        assertEquals("10,11,12,13,14,15,16", SetupMenu.slots(7, 1));
    }
}
