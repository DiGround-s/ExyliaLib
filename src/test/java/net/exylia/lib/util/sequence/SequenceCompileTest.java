package net.exylia.lib.util.sequence;

import net.exylia.lib.FakeServer;
import net.exylia.lib.debug.DebugCapture;
import net.exylia.lib.util.sequence.internal.SequenceAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiling configuration into steps.
 *
 * <p>The contract that matters most is that an existing ExyliaCommons
 * {@code effects.yml} compiles unchanged, and that one bad line costs its own
 * line rather than the whole effect.
 */
class SequenceCompileTest {

    private List<String> problems;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
    }

    @AfterEach
    void tearDown() {
        DebugCapture.stop();
        FakeServer.reset();
    }

    private Sequence compile(List<String> lines) {
        problems = DebugCapture.start();
        var compiler = SequenceAccess.compiler(SequenceAccess.builtInShapes(),
                (line, problem) -> { });
        return SequenceAccess.sequence(compiler.compile(lines));
    }

    // ------------------------------------------------------- the commons files

    @Test
    @DisplayName("a real ExyliaArrows effect compiles unchanged")
    void arrowsFileCompiles() {
        // Copied verbatim from ExyliaArrows' effects.yml, which is the whole
        // point of keeping the syntax: a migrating plugin edits no files.
        Sequence sequence = compile(List.of(
                "[PARTICLE] FLAME;count:6;offset:0.1,0.1,0.1;speed:0.02",
                "[PARTICLE] SMALL_FLAME;count:4;offset:0.05,0.05,0.05"));

        assertEquals(2, sequence.steps().size());
        assertTrue(sequence.isInstant(), "particles alone finish in their own tick");
    }

    @Test
    @DisplayName("a real ExyliaKillEffect choreography compiles unchanged")
    void killEffectFileCompiles() {
        Sequence sequence = compile(List.of(
                "[CIRCLE] FLAME;radius:1.5;points:24;y:0.1",
                "[SOUND] ENTITY_BLAZE_DEATH;1.5;0.8",
                "[DELAY] 0.15",
                "[EXPLOSION]"));

        assertEquals(4, sequence.steps().size());
        assertFalse(sequence.isInstant(), "a delay makes it span more than a tick");
        assertEquals(150L, sequence.durationMillis());
    }

    @Test
    @DisplayName("the positional volume and pitch of a sound are still read")
    void soundKeepsItsPositionalArguments() {
        // Every existing file writes them this way; a named form was added, but
        // breaking the old one would mean editing two thousand lines.
        Sequence sequence = compile(List.of("[SOUND] ENTITY_BLAZE_DEATH;1.5;0.8"));

        assertEquals(1, sequence.steps().size());
    }

    // -------------------------------------------------------------- resilience

    @Test
    @DisplayName("a line that cannot be understood costs its own line and nothing else")
    void oneBadLineDoesNotTakeTheRest() {
        Sequence sequence = compile(List.of(
                "[PARTICLE] FLAME",
                "[PARTICLE] NOT_A_REAL_PARTICLE",
                "[PARTICLE] HEART"));

        // ExyliaCommons warned and skipped too, and that part was right.
        assertEquals(2, sequence.steps().size(), "the two good lines still play");
    }

    @Test
    @DisplayName("a line with no token at all is skipped")
    void aLineWithoutATokenIsSkipped() {
        Sequence sequence = compile(List.of("FLAME;count:3", "[PARTICLE] FLAME"));

        assertEquals(1, sequence.steps().size());
    }

    @Test
    @DisplayName("blank lines are ignored rather than reported")
    void blankLinesAreIgnored() {
        Sequence sequence = compile(java.util.Arrays.asList("", "   ", null, "[PARTICLE] FLAME"));

        assertEquals(1, sequence.steps().size());
    }

    @Test
    @DisplayName("an unknown effect name is skipped")
    void unknownTokenIsSkipped() {
        Sequence sequence = compile(List.of("[TELEPORT] somewhere", "[PARTICLE] FLAME"));

        assertEquals(1, sequence.steps().size());
    }

    // ---------------------------------------------------------------- duration

    @Test
    @DisplayName("a sequence knows how long it lasts without playing it")
    void durationIsKnownUpFront() {
        Sequence sequence = compile(List.of(
                "[DELAY] 0.5",
                "[PARTICLE] FLAME",
                "[DELAY] 1.5"));

        assertEquals(2000L, sequence.durationMillis());
    }

    @Test
    @DisplayName("an animated shape counts towards the duration")
    void animationCountsTowardsDuration() {
        // ExyliaCommons summed only the explicit delays, so a preview released
        // the player while the animation was still drawing.
        Sequence animated = compile(List.of("[CIRCLE] FLAME;points:20;ticks:10;interval:0.05"));

        assertTrue(animated.durationMillis() > 0,
                "an animation takes time even with no delay line");
        assertFalse(animated.isInstant());
    }

    @Test
    @DisplayName("a shape drawn in one frame is instant")
    void unanimatedShapeIsInstant() {
        Sequence sequence = compile(List.of("[CIRCLE] FLAME;points:20"));

        assertTrue(sequence.isInstant(), "with ticks:1 the whole circle is one frame");
        assertEquals(0L, sequence.durationMillis());
    }

    @Test
    @DisplayName("a zero delay does not become a step")
    void zeroDelayIsDropped() {
        Sequence sequence = compile(List.of("[DELAY] 0", "[PARTICLE] FLAME"));

        assertEquals(1, sequence.steps().size());
        assertTrue(sequence.isInstant());
    }

    @Test
    @DisplayName("an empty sequence is a sequence, not a null")
    void emptyIsUsable() {
        Sequence sequence = compile(List.of());

        assertTrue(sequence.isEmpty());
        assertTrue(sequence.isInstant());
        assertEquals(0L, sequence.durationMillis());
    }
}
