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

    @Test
    @DisplayName("a head line round-trips and replaces an item or a block")
    void headLines() {
        HologramLine line = HologramLine.parse("<head:Notch><size:0.5>{letters}Hi");
        assertEquals("Notch", line.head());
        assertTrue(line.isModel());
        assertEquals("<head:Notch><size:0.5>{letters}Hi", line.raw());
        assertEquals(line, HologramLine.parse(line.raw()));

        HologramLine swapped = line.toBuilder().item("diamond").build();
        assertNull(swapped.head());
        assertEquals("<item:DIAMOND><size:0.5>{letters}Hi", swapped.raw());
        assertNull(HologramLine.builder().block("stone").head("Notch").build().block());
    }

    @Test
    @DisplayName("a head is read from a name, a UUID, a URL, a hash, base64 or an item prefix")
    void headSources() {
        assertEquals(net.exylia.lib.skull.SkullSource.player("Notch"),
                net.exylia.lib.skull.SkullSource.parse("Notch"));
        java.util.UUID id = java.util.UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        assertEquals(net.exylia.lib.skull.SkullSource.player(id),
                net.exylia.lib.skull.SkullSource.parse(id.toString()));
        String url = "http://textures.minecraft.net/texture/abc";
        assertEquals(net.exylia.lib.skull.SkullSource.url(url),
                net.exylia.lib.skull.SkullSource.parse(url));
        String hash = "a".repeat(64);
        assertEquals(net.exylia.lib.skull.SkullSource.url(hash),
                net.exylia.lib.skull.SkullSource.parse("urlhead-" + hash));
        assertEquals(net.exylia.lib.skull.SkullSource.url(hash),
                net.exylia.lib.skull.SkullSource.parse(hash));
        assertEquals(net.exylia.lib.skull.SkullSource.texture("eyJ0ZXh0dXJlcyI6e30="),
                net.exylia.lib.skull.SkullSource.parse("eyJ0ZXh0dXJlcyI6e30="));
    }
}
