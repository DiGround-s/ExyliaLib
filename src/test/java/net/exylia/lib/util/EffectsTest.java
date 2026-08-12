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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Potion effects from a compact string.
 */
class EffectsTest {

    private FakePlayer player;
    private final List<String> applied = new ArrayList<>();
    private Effects.EffectApplier originalApplier;
    private Effects.EffectResolver originalResolver;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        player = new FakePlayer("Steve");
        FakeServer.online(player.player());

        applied.clear();
        originalApplier = Effects.getApplier();
        originalResolver = Effects.getResolver();

        // Fake resolver: returns the effect name as the resolved type.
        // The applier records "typeName:amplifier:duration".
        Effects.setResolver(name -> "SPEED".equals(name) || "JUMP_BOOST".equals(name)
                || "REGENERATION".equals(name) ? name : null);
        Effects.setApplier((p, type, amplifier, duration) -> {
            if (p.equals(player.player())) {
                applied.add(type + ":" + amplifier + ":" + duration);
            }
        });
    }

    @AfterEach
    void tearDown() {
        Effects.resetCache();
        Effects.setApplier(originalApplier);
        Effects.setResolver(originalResolver);
        FakeServer.reset();
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a full string parses every effect")
    void parseFull() {
        Effects.ParsedEffect[] effects = Effects.parse("SPEED:1:300|JUMP_BOOST:2:120|REGENERATION:0:60");
        assertEquals(3, effects.length);
        assertEquals("SPEED", effects[0].name());
        assertEquals(1, effects[0].amplifier());
        assertEquals(300, effects[0].duration());
    }

    @Test
    @DisplayName("a bare name gets the defaults")
    void defaults() {
        Effects.ParsedEffect[] effects = Effects.parse("SPEED");
        assertEquals(1, effects.length);
        assertEquals("SPEED", effects[0].name());
        assertEquals(0, effects[0].amplifier());
        assertEquals(200, effects[0].duration());
    }

    @Test
    @DisplayName("a name with only amplifier gets the default duration")
    void partialDefaults() {
        Effects.ParsedEffect[] effects = Effects.parse("SPEED:2");
        assertEquals(1, effects.length);
        assertEquals(2, effects[0].amplifier());
        assertEquals(200, effects[0].duration());
    }

    @Test
    @DisplayName("an empty string returns nothing")
    void emptyReturnsNothing() {
        assertEquals(0, Effects.parse("").length);
        assertEquals(0, Effects.parse("   ").length);
        assertEquals(0, Effects.parse("||").length);
    }

    @Test
    @DisplayName("a string with spaces around separators still parses")
    void whitespaceTolerated() {
        Effects.ParsedEffect[] effects = Effects.parse(" SPEED : 1 : 300 | JUMP_BOOST ");
        assertEquals(2, effects.length);
        assertEquals("SPEED", effects[0].name());
        assertEquals(1, effects[0].amplifier());
        assertEquals(300, effects[0].duration());
    }

    @Test
    @DisplayName("the same string hits the parse cache")
    void cachingHitsOnce() {
        Effects.ParsedEffect[] first = Effects.parse("SPEED:1:300");
        Effects.ParsedEffect[] second = Effects.parse("SPEED:1:300");
        assertSame(first, second);
    }

    @Test
    @DisplayName("resetting the cache forces a fresh parse")
    void resetProducesNewObject() {
        Effects.ParsedEffect[] first = Effects.parse("SPEED:1:300");
        Effects.resetCache();
        Effects.ParsedEffect[] second = Effects.parse("SPEED:1:300");
        assertNotSame(first, second);
    }

    @Test
    @DisplayName("an unknown name is silently skipped")
    void unknownSkipped() {
        Effects.apply(player.player(), "SPEED:1:300|NOT_REAL:2:100|JUMP_BOOST:0:50");
        assertEquals(2, applied.size());
    }

    // ------------------------------------------------------------------
    // Application
    // ------------------------------------------------------------------

    @Test
    @DisplayName("apply fires the right number of effects with correct values")
    void apply() {
        Effects.apply(player.player(), "SPEED:1:300|JUMP_BOOST:0:100");
        assertEquals(2, applied.size());
        assertEquals("SPEED:1:300", applied.get(0));
        assertEquals("JUMP_BOOST:0:100", applied.get(1));
    }

    @Test
    @DisplayName("a resolver that returns null means nothing is applied")
    void nullResolver() {
        Effects.setResolver(name -> null);
        Effects.apply(player.player(), "SPEED:1:300");
        assertEquals(0, applied.size());
    }
}
