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

    @Test
    @DisplayName("every tag added in 1.301.0 reads back exactly as it is written")
    void newTagsRoundTrip() {
        for (String raw : java.util.List.of(
                "<gap:0.3>Hi",
                "<gap:-0.1>Hi",
                "<align:left>Hi",
                "<width:120>Hi",
                "<billboard:fixed>Hi",
                "<seethrough:true>Hi",
                "<seethrough:false>Hi",
                "<brightness:15>Hi",
                "<autoscale>Hi",
                "<autoscale:4,24,3>Hi",
                "<sway:20>Hi",
                "<sway:20,period=6>Hi",
                "<bob:0.1,period=3>Hi",
                "<pulse:0.05,period=4>Hi",
                "<shine:highlight,speed=15,pause=1,width=4>Hi",
                "<shine:speed=15>Hi",
                "<rainbow:8>Hi",
                "<frames:1.5>A||B",
                "<marquee:20>Hi",
                "<marquee:20,speed=3>Hi",
                "<fade:2>Hi",
                "<blink:0.5>Hi",
                "<scramble:30>Hi",
                "<button:join>Hi",
                "<size:2><align:right><gap:0.2><spin:90><rainbow:6><typewriter:24><range:8><button:join><if:\"%a% > 1\">Hi")) {
            HologramLine line = HologramLine.parse(raw);
            assertEquals(raw, line.raw(), raw);
            assertEquals(line, HologramLine.parse(line.raw()), raw);
            assertTrue(line.hasTags(), raw);
        }
    }

    @Test
    @DisplayName("new tags read into their fields, bare ones into their defaults")
    void newTagsRead() {
        HologramLine line = HologramLine.parse(
                "<shine:#ffd700,width=4,speed=15><bob:period=5><align:LEFT><autoscale><frames:500ms>A||B");
        assertEquals("#ffd700", line.shine());
        assertEquals(15.0, line.shineSpeed());
        assertEquals(4.0, line.shineWidth());
        assertNull(line.shinePause());
        assertEquals(HologramLine.DEFAULT_BOB, line.bob());
        assertEquals(5.0, line.bobPeriod());
        assertEquals("left", line.align());
        assertEquals("", line.autoscale());
        assertEquals(0.5, line.frames());
        assertEquals("A||B", line.text());

        assertEquals(HologramLine.DEFAULT_SWAY, HologramLine.parse("<sway>x").sway());
        assertEquals(HologramLine.DEFAULT_FRAMES, HologramLine.parse("<frames>x").frames());
        assertEquals(HologramLine.DEFAULT_RAINBOW, HologramLine.parse("<rainbow>x").rainbow());
        assertEquals(HologramLine.DEFAULT_FADE, HologramLine.parse("<fade>x").fade());
        assertEquals(HologramLine.DEFAULT_BLINK, HologramLine.parse("<blink>x").blink());
        assertEquals(HologramLine.DEFAULT_MARQUEE, HologramLine.parse("<marquee>x").marquee());
        assertEquals(HologramLine.DEFAULT_SCRAMBLE, HologramLine.parse("<scramble>x").scramble());
        assertEquals(Boolean.TRUE, HologramLine.parse("<seethrough>x").seeThrough());
        assertEquals("<frames:2>x", HologramLine.parse("<frames>x").raw());
        assertEquals("<shine>x", HologramLine.parse("<shine>x").raw(), "a bare shine is unchanged");
        assertEquals("<shine:highlight>x", HologramLine.parse("<shine:highlight>x").raw());
    }

    @Test
    @DisplayName("a broken new tag stays in the text, and options need their tag")
    void brokenNewTags() {
        for (String raw : java.util.List.of("<align:middle>x", "<billboard:up>x",
                "<autoscale:1,2>x", "<shine:gold,speed=fast>x", "<bob:0.1,speed=2>x",
                "<button>x", "<gap:wide>x", "<marquee:ten>x")) {
            HologramLine line = HologramLine.parse(raw);
            assertEquals(raw, line.text(), raw);
            assertFalse(line.hasTags(), raw);
        }
        HologramLine cleared = HologramLine.parse("<shine:gold,speed=5><bob:0.1,period=3>x")
                .toBuilder().shine(null).bob(null).build();
        assertNull(cleared.shineSpeed());
        assertNull(cleared.bobPeriod());
        assertEquals("x", cleared.raw());
    }

    @Test
    @DisplayName("the distance curve grows in a straight line between start and end")
    void distanceCurve() {
        HologramConfig.DistanceScale curve = new HologramConfig.DistanceScale(true, 8, 32, 1, 2.5);
        assertEquals(1.0, curve.scaleAt(0), 1e-9);
        assertEquals(1.0, curve.scaleAt(8), 1e-9);
        assertEquals(1.75, curve.scaleAt(20), 1e-9);
        assertEquals(2.5, curve.scaleAt(32), 1e-9);
        assertEquals(2.5, curve.scaleAt(500), 1e-9);

        HologramConfig.DistanceScale clamped = new HologramConfig.DistanceScale(true, 10, 5, 0, 99);
        assertEquals(11, clamped.end(), 1e-9, "an end before the start is moved past it");
        assertEquals(0.1, clamped.min(), 1e-9);
        assertEquals(10, clamped.max(), 1e-9);
        assertFalse(new HologramConfig.Properties().distanceScale().enabled(), "off unless asked");
    }

    @Test
    @DisplayName("a click section written before buttons keeps its meaning and gains the button look")
    void clickDefaults() {
        HologramConfig.Click old = new HologramConfig.Click(java.util.List.of("spawn"), 1.5, 500);
        assertTrue(old.buttonStyle());
        assertEquals(java.util.Map.of(), old.buttons());
        assertEquals("", old.sound());
        HologramConfig.Click none = new HologramConfig.Click(java.util.List.of(), 1.5, 500, 1.08,
                "none", null, null, null, null);
        assertFalse(none.buttonStyle());
        assertEquals("{primary}", none.color());
    }
}
