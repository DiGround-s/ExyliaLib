package net.exylia.lib.util.wizard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the boss bar is allowed to say.
 *
 * <p>A progress bar is a promise about a flow the player cannot otherwise see
 * the shape of, and there is exactly one way to break that promise: show them a
 * number that makes no sense. "Step 4 of 3" and a total that grows while they
 * answer are both worse than no bar at all, because a bar that lies is a bar
 * they stop reading.
 *
 * <p>Branches are what make this hard. How many steps a given player answers is
 * only knowable while they are answering, so the total is an upper bound that
 * counts every branch as taken and falls as one is skipped. It may fall; it may
 * never rise, and the position may never pass it.
 */
class WizardProgressTest {

    private static final WizardKey<String> KIND = WizardKey.text("kind");
    private static final WizardKey<String> NAME = WizardKey.text("name");
    private static final WizardKey<Long> POINTS = WizardKey.integer("points");

    private WizardHarness harness;

    @BeforeEach
    void setUp() {
        harness = WizardHarness.start("Events", "DiGround");
    }

    @AfterEach
    void tearDown() {
        harness.stop();
    }

    private Wizard branching() {
        return harness.wizards().define("event")
                .ask(KIND, step -> step.choice("Kind", List.of("koth", "conquest")))
                .when(KIND, kind -> kind.equals("koth"),
                        branch -> branch.ask(POINTS, step -> step.integer("Capture points")))
                .ask(NAME, step -> step.text("Display name"))
                .summary()
                .build();
    }

    @Test
    @DisplayName("the definition counts every branch as taken, which is the upper bound")
    void definitionCountsTheDeepestPath() {
        assertEquals(3, branching().stepCount(),
                "the bar's denominator before anything is decided is the deepest path");
    }

    @Test
    @DisplayName("progress never passes the total and the total never rises")
    void progressStaysSane() {
        WizardRun run = harness.wizards().start(harness.player(), branching());
        harness.settle();

        List<String> readings = new ArrayList<>();
        int highestTotal = record(run, readings);

        harness.answer("conquest");
        int afterKind = record(run, readings);
        assertTrue(afterKind <= highestTotal,
                "skipping a branch may lower the total, never raise it: " + readings);

        harness.answer("Sky Fortress");
        int afterName = record(run, readings);
        assertTrue(afterName <= afterKind, "the total must not rise: " + readings);

        assertEquals(2, run.stepIndex(), "a skipped branch is not a step anybody answered");
        assertEquals(2, run.stepCount(), "the total settles on what was actually asked");
    }

    @Test
    @DisplayName("the position never exceeds the total, at any point in a flow")
    void positionNeverExceedsTotal() {
        WizardRun run = harness.wizards().start(harness.player(), branching());
        harness.settle();
        assertSane(run);

        for (String answer : List.of("koth", "5", "Sky Fortress")) {
            harness.answer(answer);
            assertSane(run);
        }
    }

    @Test
    @DisplayName("a redo does not push the position past the total")
    void redoKeepsThePositionSane() {
        // Re-asking an answered step means a step is running that already has
        // an answer. Counting it as one more to come would show a total of four
        // in a three-question flow, which is the nonsense bar this guards.
        WizardRun run = harness.wizards().start(harness.player(), branching());
        harness.settle();
        harness.answer("koth");
        harness.answer("5");
        harness.answer("Sky Fortress");

        int total = run.stepCount();
        harness.deny();
        assertSane(run);
        harness.answer("name");
        assertSane(run);
        assertTrue(run.stepCount() <= total,
                "re-asking a step must not grow the flow: " + run.stepCount() + " > " + total);

        harness.answer("New Name");
        assertSane(run);
        assertEquals(3, run.stepIndex(),
                "a redone answer replaces one, it does not add one");
    }

    @Test
    @DisplayName("a redo that revives a branch raises the position honestly, never past the total")
    void redoThatAddsStepsStaysSane() {
        WizardRun run = harness.wizards().start(harness.player(), branching());
        harness.settle();
        harness.answer("conquest");
        harness.answer("Sky Fortress");
        assertEquals(2, run.stepIndex());

        harness.redo("kind");
        harness.answer("koth");
        // The branch just came back, so there is genuinely one more question.
        assertSane(run);
        assertEquals(3, run.stepCount(),
                "a branch that now applies is part of the flow again");

        harness.answer("7");
        assertSane(run);
        assertEquals(3, run.stepIndex());
    }

    @Test
    @DisplayName("a flow that turns the bar off shows nothing at all")
    void progressCanBeTurnedOff() {
        // Not cosmetic: a flow that runs while something else owns the boss bar
        // has to be able to leave it alone, and a bar nobody stopped is a leak
        // the player can see.
        Wizard quiet = harness.wizards().define("event")
                .ask(NAME, step -> step.text("Display name"))
                .progress(false)
                .build();

        harness.wizards().start(harness.player(), quiet);
        harness.settle();

        assertEquals(0, harness.fake().bossBarsShown(),
                "a flow told not to draw a bar must not draw one");
    }

    /** The position and total right now, remembered for the failure message. */
    private static int record(WizardRun run, List<String> readings) {
        readings.add(run.stepIndex() + "/" + run.stepCount());
        return run.stepCount();
    }

    private static void assertSane(WizardRun run) {
        assertTrue(run.stepIndex() <= run.stepCount(),
                "a bar reading " + run.stepIndex() + " of " + run.stepCount()
                        + " tells the player the flow is longer than it is");
        assertTrue(run.stepIndex() >= 0, "a flow cannot be at a negative step");
    }
}
