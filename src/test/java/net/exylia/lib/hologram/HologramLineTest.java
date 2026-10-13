package net.exylia.lib.hologram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A line read into fields and written back, as an editor does. */
class HologramLineTest {

    @Test
    @DisplayName("tags are read into fields and the text keeps everything else")
    void readsTags() {
        HologramLine line = HologramLine.parse("<size:2><spin><if:\"%lvl% > 3\"><bold>Hi");
        assertEquals(2.0, line.size());
        assertEquals(HologramLine.DEFAULT_SPIN, line.spin());
        assertEquals("%lvl% > 3", line.condition());
        assertEquals("<bold>Hi", line.text());
        assertNull(line.pulse());
        assertTrue(line.hasTags());
    }

    @Test
    @DisplayName("writing back only writes the tags that are set, and reads back the same")
    void roundTrips() {
        HologramLine line = HologramLine.parse("<size:2.50><shine><perm:!vip>Hello");
        HologramLine edited = line.toBuilder().size(null).pulse(0.06).build();

        assertEquals("<pulse:0.06><shine><perm:!vip>Hello", edited.raw());
        assertEquals(edited, HologramLine.parse(edited.raw()));
        assertEquals("<size:2.5><shine><perm:!vip>Hello", line.raw());
    }

    @Test
    @DisplayName("an item and a block exclude each other, and a plain line has no tags")
    void modelsAndPlainLines() {
        HologramLine block = HologramLine.builder().item("diamond").block("beacon").build();
        assertNull(block.item());
        assertEquals("<block:BEACON>", block.raw());
        assertTrue(block.isModel());

        HologramLine plain = HologramLine.parse("<size:big>Hi");
        assertEquals("<size:big>Hi", plain.text());
        assertFalse(plain.hasTags());
    }
}
