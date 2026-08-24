package net.exylia.lib.util.sequence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Effects written by ExyliaCommons, read as sequences.
 *
 * <p>Its entry was forty fields over eight types and this one is ten fields over
 * a sequence, so reading a stored row means translating it. The JSON here is the
 * shape a bare {@code new Gson().toJson(...)} over the old bean produced, which
 * is what the mines columns hold.
 *
 * <p>The translation is the feature: nobody re-authors a mine's break effects.
 */
class EffectCodecTest {

    private final List<String> problems = new ArrayList<>();

    private List<EffectEntry> decode(String json) {
        return EffectCodec.decode(json, (where, problem) -> problems.add(where + ": " + problem));
    }

    // ------------------------------------------------------------------
    // Reading what commons stored
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a sound entry becomes the line that plays the same sound")
    void legacySound() {
        List<EffectEntry> effects = decode("""
                [{"id":"a","type":"SOUND","name":"Break","chance":100.0,"priority":0,\
                "delayTicks":0,"scope":"NEARBY","radius":16.0,\
                "sound":"BLOCK_STONE_BREAK","soundVolume":0.8,"soundPitch":1.2}]""");

        assertEquals(1, effects.size());
        EffectEntry entry = effects.get(0);
        assertEquals(List.of("[SOUND] BLOCK_STONE_BREAK;0.8;1.2"), entry.lines());
        assertEquals("Break", entry.name());
        assertEquals("a", entry.id());
        assertTrue(problems.isEmpty(), problems::toString);
    }

    @Test
    @DisplayName("a particle entry keeps its count, offsets and colour")
    void legacyParticle() {
        List<EffectEntry> effects = decode("""
                [{"id":"a","type":"PARTICLE","chance":100.0,"particle":"DUST",\
                "particleCount":20,"offsetX":0.3,"offsetY":0.5,"offsetZ":0.3,\
                "particleExtra":0.05,"particleColor":"#8a51c4","dustSize":1.5}]""");

        assertEquals(List.of("[PARTICLE] DUST;count:20;offset:0.3,0.5,0.3;speed:0.05;color:#8a51c4;size:1.5"),
                effects.get(0).lines());
    }

    @Test
    @DisplayName("a title entry's times come across in seconds, because that is what a line means")
    void legacyTitleConvertsTicks() {
        List<EffectEntry> effects = decode("""
                [{"id":"a","type":"TITLE","chance":100.0,"title":"&aWell done",\
                "subtitle":"keep going","titleFadeIn":10,"titleStay":70,"titleFadeOut":20}]""");

        // 10, 70 and 20 ticks are 0.5, 3.5 and 1.0 seconds.
        assertEquals(List.of("[TITLE] &aWell done;keep going;0.5;3.5;1.0"),
                effects.get(0).lines());
    }

    @Test
    @DisplayName("a potion entry keeps ticks, because both sides count in ticks")
    void legacyPotion() {
        List<EffectEntry> effects = decode("""
                [{"id":"a","type":"POTION","chance":100.0,"potion":"SPEED",\
                "potionDurationTicks":200,"potionAmplifier":1}]""");

        assertEquals(List.of("[POTION] SPEED;200;1"), effects.get(0).lines());
    }

    @Test
    @DisplayName("a message entry becomes one line per message")
    void legacyMessages() {
        List<EffectEntry> effects = decode("""
                [{"id":"a","type":"MESSAGE","chance":100.0,"message":"first",\
                "messages":["second","third"]}]""");

        assertEquals(List.of("[MESSAGE] first", "[MESSAGE] second", "[MESSAGE] third"),
                effects.get(0).lines());
    }

    @Test
    @DisplayName("a sequence entry is already lines and is taken as it is")
    void legacySequence() {
        List<EffectEntry> effects = decode("""
                [{"id":"a","type":"SEQUENCE","chance":100.0,\
                "sequence":["[CIRCLE] FLAME;radius:1.5","[DELAY] 0.2","[EXPLOSION]"]}]""");

        assertEquals(List.of("[CIRCLE] FLAME;radius:1.5", "[DELAY] 0.2", "[EXPLOSION]"),
                effects.get(0).lines());
    }

    @Test
    @DisplayName("the gating comes across untouched")
    void legacyGating() {
        List<EffectEntry> effects = decode("""
                [{"id":"a","type":"SOUND","sound":"CLICK","chance":25.5,\
                "condition":"%player_level% >= 10","permission":"mines.vip",\
                "priority":7,"delayTicks":40,"scope":"RADIUS","radius":24.0}]""");

        EffectEntry entry = effects.get(0);
        assertEquals(25.5, entry.chance());
        assertFalse(entry.isGuaranteed());
        assertEquals("%player_level% >= 10", entry.condition());
        assertEquals("mines.vip", entry.permission());
        assertEquals(7, entry.priority());
        assertEquals(40L, entry.delayTicks());
        assertEquals(24.0, entry.radius());
    }

    @Test
    @DisplayName("every old scope means a radius, and PLAYER means them alone")
    void legacyScopes() {
        assertTrue(one("\"scope\":\"PLAYER\"").isPrivate());
        assertEquals(EffectEntry.WHOLE_WORLD, one("\"scope\":\"GLOBAL\"").radius());
        assertEquals(EffectEntry.DEFAULT_RADIUS, one("\"scope\":\"NEARBY\"").radius());
        assertEquals(EffectEntry.DEFAULT_RADIUS, one("\"scope\":\"LOCATION\"").radius());
        assertEquals(9.0, one("\"scope\":\"RADIUS\",\"radius\":9.0").radius());
        assertEquals(EffectEntry.DEFAULT_RADIUS, one("\"priority\":0").radius(),
                "A row with no scope at all still has to reach somebody");
    }

    @Test
    @DisplayName("a type this library cannot play keeps its gating and is reported")
    void legacyUnknownType() {
        List<EffectEntry> effects = decode("""
                [{"id":"a","type":"HOLOGRAM","chance":50.0,"permission":"mines.vip"}]""");

        assertEquals(1, effects.size(), "Losing the row silently is worse than showing it empty");
        assertFalse(effects.get(0).isPlayable());
        assertEquals("mines.vip", effects.get(0).permission());
        assertEquals(1, problems.size(), problems::toString);
    }

    // ------------------------------------------------------------------
    // The shape this library writes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a stored effect survives a round trip unchanged")
    void roundTrip() {
        List<EffectEntry> written = List.of(
                EffectEntry.of(List.of("[SOUND] CLICK;1;1", "[DELAY] 0.2"))
                        .id("a").name("Click").chance(25.0).condition("%x% > 1")
                        .permission("mines.vip").priority(3).delayTicks(40).nearby(12.0).build(),
                EffectEntry.of(List.of("[MESSAGE] hello")).id("b").wholeWorld().build());

        List<EffectEntry> read = decode(EffectCodec.encode(written));

        assertEquals(written, read);
        assertEquals(EffectCodec.encode(written), EffectCodec.encode(read));
        assertEquals(List.of("[SOUND] CLICK;1;1", "[DELAY] 0.2"), read.get(0).lines());
        assertEquals(12.0, read.get(0).radius());
        assertEquals(EffectEntry.WHOLE_WORLD, read.get(1).radius(),
                "Infinity is not JSON, so the whole world is written as the word");
        assertTrue(problems.isEmpty(), problems::toString);
    }

    @Test
    @DisplayName("an empty list stores as NULL, not as []")
    void emptyIsNull() {
        assertNull(EffectCodec.encode(List.of()));
    }

    @Test
    @DisplayName("a row that is not JSON costs the list, not an exception")
    void malformed() {
        assertTrue(decode("{not json").isEmpty());
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(decode(null).isEmpty());
        assertTrue(decode("   ").isEmpty());
    }

    @Test
    @DisplayName("a mixed column reads both shapes at once")
    void mixed() {
        List<EffectEntry> effects = decode("""
                [{"id":"a","type":"SOUND","chance":100.0,"sound":"CLICK"},\
                {"id":"b","lines":["[MESSAGE] hi"],"chance":100.0,"priority":0,\
                "delayTicks":0,"radius":"world"}]""");

        assertEquals(2, effects.size());
        assertEquals(List.of("[SOUND] CLICK;1.0;1.0"), effects.get(0).lines());
        assertEquals(List.of("[MESSAGE] hi"), effects.get(1).lines());
        assertEquals(EffectEntry.WHOLE_WORLD, effects.get(1).radius());
    }

    // ------------------------------------------------------------------

    private EffectEntry one(String extraFields) {
        problems.clear();
        return decode("[{\"id\":\"a\",\"type\":\"SOUND\",\"sound\":\"CLICK\","
                + extraFields + "}]").get(0);
    }
}
