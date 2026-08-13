package net.exylia.lib.util;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Potion effects from a compact string: {@code NAME|LEVEL|SECONDS}, one
 * effect per line, the notation production configs already write.
 */
class EffectsTest {

    private FakePlayer player;
    private final List<String> applied = new ArrayList<>();
    private final List<String> removed = new ArrayList<>();
    private Effects.EffectApplier originalApplier;
    private Effects.EffectResolver originalResolver;
    private Effects.EffectRemover originalRemover;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        player = new FakePlayer("Steve");
        FakeServer.online(player.player());

        applied.clear();
        removed.clear();
        originalApplier = Effects.getApplier();
        originalResolver = Effects.getResolver();
        originalRemover = Effects.getRemover();

        // Fake resolver: returns the effect name as the resolved type.
        // The applier records "typeName:amplifier:duration".
        Effects.setResolver(name -> "SPEED".equals(name) || "JUMP_BOOST".equals(name)
                || "REGENERATION".equals(name) || "DAMAGE_RESISTANCE".equals(name) ? name : null);
        Effects.setApplier((p, type, amplifier, duration) -> {
            if (p.equals(player.player())) {
                applied.add(type + ":" + amplifier + ":" + duration);
            }
        });
        Effects.setRemover((p, type) -> {
            if (p.equals(player.player())) {
                removed.add(String.valueOf(type));
            }
        });
    }

    @AfterEach
    void tearDown() {
        Effects.resetCache();
        Effects.setApplier(originalApplier);
        Effects.setResolver(originalResolver);
        Effects.setRemover(originalRemover);
        FakeServer.reset();
    }

    // ------------------------------------------------------------------
    // Parsing — NAME|LEVEL|SECONDS
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a full line parses name, level and seconds")
    void parseFull() {
        Effects.ParsedEffect effect = Effects.parse("SPEED|2|5");
        assertNotNull(effect);
        assertEquals("SPEED", effect.name());
        assertEquals(1, effect.amplifier());
        assertEquals(100, effect.duration());
    }

    @Test
    @DisplayName("the level is written the way a player reads it")
    void levelIsHuman() {
        // SPEED|2 is Speed II, which Bukkit calls amplifier 1.
        Effects.ParsedEffect effect = Effects.parse("SPEED|2");
        assertNotNull(effect);
        assertEquals(1, effect.amplifier());

        Effects.ParsedEffect first = Effects.parse("SPEED|1");
        assertNotNull(first);
        assertEquals(0, first.amplifier());
    }

    @Test
    @DisplayName("a level of zero or less clamps to amplifier zero")
    void levelClamps() {
        Effects.ParsedEffect effect = Effects.parse("SPEED|0|5");
        assertNotNull(effect);
        assertEquals(0, effect.amplifier());
    }

    @Test
    @DisplayName("a bare name gets level I and ten seconds")
    void defaults() {
        Effects.ParsedEffect effect = Effects.parse("SPEED");
        assertNotNull(effect);
        assertEquals("SPEED", effect.name());
        assertEquals(0, effect.amplifier());
        assertEquals(200, effect.duration());
    }

    @Test
    @DisplayName("a name with only a level gets the default duration")
    void partialDefaults() {
        Effects.ParsedEffect effect = Effects.parse("SPEED|3");
        assertNotNull(effect);
        assertEquals(2, effect.amplifier());
        assertEquals(200, effect.duration());
    }

    @Test
    @DisplayName("the word infinite and -1 both mean the effect does not end")
    void infiniteDuration() {
        Effects.ParsedEffect byWord = Effects.parse("SPEED|2|infinite");
        assertNotNull(byWord);
        assertEquals(-1, byWord.duration());

        Effects.ParsedEffect byNumber = Effects.parse("SPEED|2|-1");
        assertNotNull(byNumber);
        assertEquals(-1, byNumber.duration());
    }

    @Test
    @DisplayName("seconds become ticks")
    void secondsBecomeTicks() {
        Effects.ParsedEffect effect = Effects.parse("SPEED|1|35");
        assertNotNull(effect);
        assertEquals(700, effect.duration());
    }

    @Test
    @DisplayName("a string with spaces around separators still parses")
    void whitespaceTolerated() {
        Effects.ParsedEffect effect = Effects.parse(" SPEED | 2 | 5 ");
        assertNotNull(effect);
        assertEquals("SPEED", effect.name());
        assertEquals(1, effect.amplifier());
        assertEquals(100, effect.duration());
    }

    @Test
    @DisplayName("the name is upper-cased")
    void nameUpperCased() {
        Effects.ParsedEffect effect = Effects.parse("speed|1|5");
        assertNotNull(effect);
        assertEquals("SPEED", effect.name());
    }

    // ------------------------------------------------------------------
    // Malformed input — skipped, never fatal
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an empty line parses to nothing")
    void emptyReturnsNull() {
        assertNull(Effects.parse(""));
        assertNull(Effects.parse("   "));
        assertNull(Effects.parse("||"));
        assertNull(Effects.parse("|2|5"));
    }

    @Test
    @DisplayName("a colon in the name position means another notation — skipped, not guessed")
    void colonRejected() {
        assertNull(Effects.parse("SPEED:1:300"));
        assertNull(Effects.parse("minecraft:speed|1|5"));
    }

    @Test
    @DisplayName("an unparseable level or duration falls back, never fails")
    void badNumbersFallBack() {
        Effects.ParsedEffect effect = Effects.parse("SPEED|fast|soon");
        assertNotNull(effect);
        assertEquals(0, effect.amplifier());   // level "fast" → default I
        assertEquals(200, effect.duration());  // duration "soon" → default 10s
    }

    @Test
    @DisplayName("a list skips its malformed lines and keeps the rest")
    void listSkipsMalformed() {
        List<Effects.ParsedEffect> effects = Effects.parse(
                List.of("SPEED|2|5", "", "SPEED:1:300", "JUMP_BOOST|1"));
        assertEquals(2, effects.size());
        assertEquals("SPEED", effects.get(0).name());
        assertEquals("JUMP_BOOST", effects.get(1).name());
    }

    // ------------------------------------------------------------------
    // Caching
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the same string hits the parse cache")
    void cachingHitsOnce() {
        Effects.ParsedEffect first = Effects.parse("SPEED|1|5");
        Effects.ParsedEffect second = Effects.parse("SPEED|1|5");
        assertSame(first, second);
    }

    @Test
    @DisplayName("resetting the cache forces a fresh parse")
    void resetProducesNewObject() {
        Effects.ParsedEffect first = Effects.parse("SPEED|1|5");
        Effects.resetCache();
        Effects.ParsedEffect second = Effects.parse("SPEED|1|5");
        assertNotSame(first, second);
    }

    // ------------------------------------------------------------------
    // Application
    // ------------------------------------------------------------------

    @Test
    @DisplayName("apply fires the effect with the level converted and seconds in ticks")
    void apply() {
        Effects.apply(player.player(), "SPEED|2|5");
        assertEquals(1, applied.size());
        assertEquals("SPEED:1:100", applied.get(0));
    }

    @Test
    @DisplayName("a list applies every effect, as configs hand them over")
    void applyList() {
        Effects.apply(player.player(), List.of("SPEED|2", "JUMP_BOOST|1|10"));
        assertEquals(2, applied.size());
        assertEquals("SPEED:1:200", applied.get(0));
        assertEquals("JUMP_BOOST:0:200", applied.get(1));
    }

    @Test
    @DisplayName("an unknown name is silently skipped")
    void unknownSkipped() {
        Effects.apply(player.player(), List.of("SPEED|2", "NOT_REAL|1", "JUMP_BOOST|1"));
        assertEquals(2, applied.size());
    }

    @Test
    @DisplayName("a resolver that returns null means nothing is applied")
    void nullResolver() {
        Effects.setResolver(name -> null);
        Effects.apply(player.player(), "SPEED|1|5");
        assertEquals(0, applied.size());
    }

    // ------------------------------------------------------------------
    // Effects that stay
    // ------------------------------------------------------------------

    @Test
    @DisplayName("applyInfinite keeps the level and drops the duration")
    void applyInfiniteIgnoresDuration() {
        Effects.applyInfinite(player.player(), "SPEED|2|5");
        assertEquals(1, applied.size());
        assertEquals("SPEED:1:-1", applied.get(0));
    }

    @Test
    @DisplayName("applyInfinite works on a bare name")
    void applyInfiniteBareName() {
        Effects.applyInfinite(player.player(), "SPEED");
        assertEquals(1, applied.size());
        assertEquals("SPEED:0:-1", applied.get(0));
    }

    @Test
    @DisplayName("a line that says infinite applies as infinite through apply")
    void infiniteThroughApply() {
        Effects.apply(player.player(), "SPEED|2|infinite");
        assertEquals(1, applied.size());
        assertEquals("SPEED:1:-1", applied.get(0));
    }

    @Test
    @DisplayName("applyInfinite takes the list form configs hand over")
    void applyInfiniteList() {
        Effects.applyInfinite(player.player(), List.of("SPEED|2", "DAMAGE_RESISTANCE|2"));
        assertEquals(2, applied.size());
        assertEquals("SPEED:1:-1", applied.get(0));
        assertEquals("DAMAGE_RESISTANCE:1:-1", applied.get(1));
    }

    @Test
    @DisplayName("remove takes back exactly the effects it was given")
    void removeNamesOnly() {
        Effects.remove(player.player(), List.of("SPEED|1|300", "JUMP_BOOST"));
        assertEquals(2, removed.size());
        assertEquals("SPEED", removed.get(0));
        assertEquals("JUMP_BOOST", removed.get(1));
    }

    @Test
    @DisplayName("remove skips an unknown name instead of failing")
    void removeUnknownSkipped() {
        Effects.remove(player.player(), List.of("SPEED", "NOT_REAL", "JUMP_BOOST"));
        assertEquals(2, removed.size());
    }

    @Test
    @DisplayName("apply then remove leaves the player with neither effect")
    void applyThenRemoveRoundTrip() {
        List<String> passives = List.of("SPEED|2", "REGENERATION|1");
        Effects.applyInfinite(player.player(), passives);
        assertEquals(2, applied.size());

        Effects.remove(player.player(), passives);
        assertEquals(2, removed.size());
        assertEquals("SPEED", removed.get(0));
        assertEquals("REGENERATION", removed.get(1));
    }

    @Test
    @DisplayName("an empty line applies and removes nothing")
    void emptyIsNoop() {
        Effects.apply(player.player(), "");
        Effects.applyInfinite(player.player(), "");
        Effects.remove(player.player(), "");
        assertEquals(0, applied.size());
        assertEquals(0, removed.size());
    }
}
