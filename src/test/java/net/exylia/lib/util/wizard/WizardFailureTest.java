package net.exylia.lib.util.wizard;

import net.exylia.lib.debug.DebugCapture;
import net.exylia.lib.util.wizard.internal.WizardPeek;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens when the plugin's own code is the thing that breaks.
 *
 * <p>Three places in a definition are somebody else's lambda: the branch
 * predicate, the step builder and the finish callback. Any of them may throw,
 * and the only unacceptable answer is the one the old flow gave &mdash; the
 * chain simply stopped, so the player stood there holding a wizard slot with no
 * question on screen and no line in the log to find it by. "It just stopped" is
 * not a bug report anybody can act on.
 *
 * <p>So each of them ends the run as {@link WizardOutcome#FAILED}, releases the
 * player, and says so on the console against the plugin that owns the flow.
 */
class WizardFailureTest {

    private static final WizardKey<String> KIND = WizardKey.text("kind");
    private static final WizardKey<String> ID = WizardKey.text("id");
    private static final WizardKey<Long> POINTS = WizardKey.integer("points");

    private WizardHarness harness;
    private List<String> console;

    @BeforeEach
    void setUp() {
        harness = WizardHarness.start("Events", "DiGround");
        console = DebugCapture.start();
    }

    @AfterEach
    void tearDown() {
        DebugCapture.stop();
        harness.stop();
    }

    @Test
    @DisplayName("a finish callback that throws fails the run instead of reporting success")
    void throwingFinish() {
        // Reported as FAILED rather than as a completed run with an exception in
        // the log: what the flow was for did not happen, and a caller waiting on
        // the result has to be able to tell.
        Wizard event = harness.wizards().define("event")
                .ask(ID, step -> step.id("Event id"))
                .summary()
                .onFinish(values -> {
                    throw new IllegalStateException("the plugin's creation code is broken");
                })
                .build();

        WizardRun run = harness.wizards().start(harness.player(), event);
        harness.settle();
        harness.answer("blitz");
        harness.confirm();

        assertEquals(WizardOutcome.FAILED, outcomeOf(run));
        assertReported("What wizard 'event' was meant to create failed");
        harness.assertNothingLeaked();
    }

    @Test
    @DisplayName("a branch predicate that throws fails the run instead of guessing")
    void throwingPredicate() {
        // Guessing would be worse than failing either way round: taking the
        // branch asks for something the event may not have, and skipping it
        // silently drops a required answer.
        Wizard event = harness.wizards().define("event")
                .ask(KIND, step -> step.id("Kind"))
                .when(KIND, kind -> {
                    throw new IllegalStateException("the predicate is broken");
                }, branch -> branch.ask(POINTS, step -> step.integer("Capture points")))
                .summary()
                .build();

        WizardRun run = harness.wizards().start(harness.player(), event);
        harness.settle();
        harness.answer("koth");

        assertEquals(WizardOutcome.FAILED, outcomeOf(run));
        assertReported("threw while deciding whether it applied");
        harness.assertNothingLeaked();
    }

    @Test
    @DisplayName("a step that cannot be built fails the run instead of freezing the player")
    void throwingStepBuilder() {
        Wizard event = harness.wizards().define("event")
                .ask(ID, step -> {
                    throw new IllegalStateException("the step builder is broken");
                })
                .summary()
                .build();

        WizardRun run = harness.wizards().start(harness.player(), event);
        harness.settle();

        assertEquals(WizardOutcome.FAILED, outcomeOf(run));
        assertReported("could not be asked");
        // The point of the whole test: a player left with a slot and no question
        // can never start another wizard until they reconnect.
        harness.assertNothingLeaked();
    }

    @Test
    @DisplayName("a step that builds nothing is a failure, not a silent skip")
    void nullRequest() {
        Wizard event = harness.wizards().define("event")
                .ask(ID, step -> null)
                .summary()
                .build();

        WizardRun run = harness.wizards().start(harness.player(), event);
        harness.settle();

        assertEquals(WizardOutcome.FAILED, outcomeOf(run));
        harness.assertNothingLeaked();
    }

    @Test
    @DisplayName("a predicate that throws during a redo fails rather than half-resolving")
    void throwingPredicateDuringRedo() {
        // Re-resolution runs the predicates a second time, on a flow that
        // already has answers in it. A throw there must not leave the run with
        // half a step queue and the player holding a review that no longer
        // matches what the flow believes.
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        Wizard event = harness.wizards().define("event")
                .ask(KIND, step -> step.choice("Kind", List.of("koth", "conquest")))
                .when(KIND, kind -> {
                    if (calls.incrementAndGet() > 1) {
                        throw new IllegalStateException("broken on the second pass");
                    }
                    return kind.equals("koth");
                }, branch -> branch.ask(POINTS, step -> step.integer("Capture points")))
                .summary()
                .onFinish(values -> finished.incrementAndGet())
                .build();

        WizardRun run = harness.wizards().start(harness.player(), event);
        harness.settle();
        harness.answer("koth");
        harness.answer("3");

        harness.redo("kind");
        harness.answer("conquest");

        assertEquals(WizardOutcome.FAILED, outcomeOf(run));
        assertEquals(0, finished.get(), "a flow that failed must create nothing");
        harness.assertNothingLeaked();
    }

    /** Asserts the console was told, which is the only way anybody finds this. */
    private void assertReported(String fragment) {
        assertTrue(console.stream().anyMatch(line -> line.contains(fragment)),
                "the console must say what broke; it said: " + console);
    }

    private WizardOutcome outcomeOf(WizardRun run) {
        AtomicReference<WizardResult> seen = new AtomicReference<>();
        WizardPeek.result(run).thenAccept(seen::set);
        harness.settle();
        assertNotNull(seen.get(), "the run never told anybody how it ended");
        return seen.get().outcome();
    }
}
