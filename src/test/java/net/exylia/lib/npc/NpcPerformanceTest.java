package net.exylia.lib.npc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The performance notation: what counts as one, and the line it becomes.
 */
class NpcPerformanceTest {

    @Test
    @DisplayName("a value with a colon is a performance; vanilla moves never have one")
    void tellsMovesFromPerformances() {
        assertFalse(NpcPerformance.isPerformance("wave"));
        assertFalse(NpcPerformance.isPerformance("swing, 250ms, offhand"));
        assertFalse(NpcPerformance.isPerformance(""));
        assertFalse(NpcPerformance.isPerformance(null));
        assertTrue(NpcPerformance.isPerformance("anim:wave"));
        assertTrue(NpcPerformance.isPerformance("emote:bow"));
        assertTrue(NpcPerformance.isPerformance("follow:2;keys:0.3 stand"));
    }

    @Test
    @DisplayName("anim: and keys: become a [RAGDOLL] line with the performance defaults in front")
    void ragdollLines() {
        assertEquals(List.of("[RAGDOLL] {victim};intact:0;follow:1;breathe:1;anim:wave"),
                NpcPerformance.parse(" anim:wave ").lines());
        assertEquals(List.of("[RAGDOLL] {victim};intact:0;breathe:1;follow:2;keys:0.3 arm_r=90 | 0.3 stand"),
                NpcPerformance.parse("follow:2;keys:0.3 arm_r=90 | 0.3 stand").lines(),
                "a default the notation sets itself is not added");
        assertNull(NpcPerformance.parse("anim:wave").emote());
    }

    @Test
    @DisplayName("emote:<id> is a reference with no lines of its own; blank is nothing")
    void emotesAndBlanks() {
        NpcPerformance emote = NpcPerformance.parse("EMOTE: bow");
        assertEquals("bow", emote.emote());
        assertEquals(List.of(), emote.lines());
        assertFalse(emote.isEmpty());
        assertTrue(NpcPerformance.parse("emote:").isEmpty());
        assertTrue(NpcPerformance.parse("  ").isEmpty());
        assertTrue(NpcPerformance.parse(null).isEmpty());
    }

    @Test
    @DisplayName("raw lines keep what they say, minus blanks; a loop limit is at least a second")
    void linesAndLimits() {
        NpcPerformance lines = NpcPerformance.lines(List.of("[RAGDOLL] {victim};anim:bow", " ", "[SOUND] X"));
        assertEquals(List.of("[RAGDOLL] {victim};anim:bow", "[SOUND] X"), lines.lines());
        assertTrue(NpcPerformance.lines(List.of("")).isEmpty());
        assertEquals(NpcPerformance.DEFAULT_LOOP_LIMIT, lines.loopLimit());
        assertEquals(Duration.ofSeconds(1), lines.loopLimit(Duration.ofMillis(10)).loopLimit());
    }

    @Test
    @DisplayName("NpcAnimations keeps its seven-value constructor and reads a bad perform range as the default")
    void animationsPerformRange() {
        NpcAnimations old = new NpcAnimations("", "wave", "", Duration.ZERO, "", 4, Duration.ZERO);
        assertEquals(NpcAnimations.DEFAULT_PERFORM_RANGE, old.performRange());
        assertEquals(NpcAnimations.DEFAULT_PERFORM_RANGE,
                new NpcAnimations("", "", "", Duration.ZERO, "", 4, Duration.ZERO, -3).performRange());
    }
}
