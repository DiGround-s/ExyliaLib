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

    // ------------------------------------------------------- lengths of time

    @Test
    @DisplayName("a timing reads a duration written out, and a bare number stays seconds")
    void timingsReadWrittenDurations() {
        Sequence written = compile(List.of("[DELAY] 1m30s", "[PARTICLE] FLAME"));
        Sequence bare = compile(List.of("[DELAY] 90", "[PARTICLE] FLAME"));

        assertEquals(90_000L, written.steps().get(0).holdMillis());
        assertEquals(bare.steps().get(0).holdMillis(), written.steps().get(0).holdMillis());
        assertTrue(problems.isEmpty(), "neither line is a problem: " + problems);
    }

    @Test
    @DisplayName("a title's times read the same way")
    void titleTimesReadWrittenDurations() {
        Sequence written = compile(List.of("[TITLE] Hello;There;0.5;1m;1s"));
        assertEquals(1, written.steps().size());
        assertTrue(problems.isEmpty(), "the line should compile: " + problems);
    }

    // ------------------------------------------------------------------ shake

    @Test
    @DisplayName("a shake compiles with its radius, times and gap, and lasts as long as its beats")
    void shakeCompiles() {
        List<String> found = new java.util.ArrayList<>();
        var compiler = SequenceAccess.compiler(SequenceAccess.builtInShapes(),
                (line, problem) -> found.add(line + ": " + problem));
        Sequence once = SequenceAccess.sequence(compiler.compile(List.of("[SHAKE]")));
        Sequence thrice = SequenceAccess.sequence(compiler.compile(
                List.of("[SHAKE] radius:10;times:3;every:0.1")));
        SequenceAccess.sequence(compiler.compile(List.of("[SHAKE] radius:10;strength:9")));

        assertEquals(1, once.steps().size());
        assertEquals(1, thrice.steps().size());
        assertTrue(thrice.durationMillis() >= 200, "three beats a tenth apart: "
                + thrice.durationMillis());
        assertEquals(1, found.size(), "only the unknown strength: is reported: " + found);
        assertTrue(found.get(0).contains("strength"));
    }

    // ------------------------------------------------------- tempo and rhythm

    @Test
    @DisplayName("a body that rolls its tempo makes the whole play roll with it")
    void aVaryingBodyVariesItsSequence() {
        Sequence sequence = compile(List.of(
                "[RAGDOLL] {victim};loop:true;tempo:0.9-1.6;loop_from:0.2;"
                        + "keys:0.2 crouch | 0.3 stand | 0.3 crouch",
                "[SOUND] BLOCK_NOTE_BLOCK_BIT;0.6;1.2;repeat:8;every:0.3"));

        assertEquals(0.9, sequence.tempoFrom(), 1e-9);
        assertEquals(1.6, sequence.tempoTo(), 1e-9);
        assertTrue(problems.isEmpty(), "both lines should compile: " + problems);
    }

    @Test
    @DisplayName("a rhythm that winds up puts each beat closer than the last")
    void aWindingRhythmQuickens() {
        Sequence sequence = compile(List.of(
                "[SOUND] BLOCK_NOTE_BLOCK_BIT;0.6;1.2;repeat:4;every:0.4;accel:2;max_speed:4"));

        // 400ms, then 200, then 100: the gap halves after every beat and stops
        // halving at four times quicker than written.
        assertEquals(700L, sequence.steps().get(0).trailMillis());
        assertTrue(problems.isEmpty(), "the line should compile: " + problems);
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
    @DisplayName("one offset number spreads over all three axes")
    void aSingleOffsetNumberIsAccepted() {
        Sequence sequence = compile(List.of("[PARTICLE] SONIC_BOOM;count:1;offset:0;speed:0"));

        assertEquals(1, sequence.steps().size(), "the line still plays");
        assertTrue(problems.isEmpty(), "nothing to report: " + problems);
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
