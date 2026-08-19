package net.exylia.lib.util.wizard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What changing an answer at the review screen has to mean.
 *
 * <p>The first version of this module went straight back to the summary
 * whenever a redone answer arrived. That is right for the common redo &mdash; a
 * mistyped display name &mdash; and quietly wrong for the one that matters: an
 * answer some {@code when} is guarded on.
 *
 * <p>A wizard asks the kind of event, and only a KOTH has capture points. A
 * player who answers KOTH, is asked for points, reaches the review and then
 * changes the kind to CONQUEST used to hand {@code onFinish} a {@code points}
 * that kind of event does not have; the mirror case, CONQUEST changed to KOTH,
 * handed it answers with a required one missing. Neither showed up until the
 * plugin's own creation code read a field, which is to say: on a live server,
 * reported by whoever built the broken event.
 *
 * <p>So these tests assert against what {@code onFinish} actually receives,
 * never against an intermediate. What the flow believes about itself is not the
 * contract; what the plugin is handed is.
 */
class WizardRedoTest {

    private static final WizardKey<String> KIND = WizardKey.text("kind");
    private static final WizardKey<String> NAME = WizardKey.text("name");
    private static final WizardKey<Long> POINTS = WizardKey.integer("points");
    private static final WizardKey<Long> DURATION = WizardKey.integer("duration");
    private static final WizardKey<String> FLAG_CHOICE = WizardKey.text("flagkind");

    private WizardHarness harness;
    private final AtomicReference<WizardValues> collected = new AtomicReference<>();
    private final AtomicInteger finished = new AtomicInteger();

    @BeforeEach
    void setUp() {
        harness = WizardHarness.start("Events", "DiGround");
    }

    @AfterEach
    void tearDown() {
        harness.stop();
    }

    /**
     * The flow the bug was found in: a kind, a name, and points only for a KOTH.
     *
     * <p>The name sits after the branch on purpose. It is the answer that must
     * survive both directions, and an answer collected before the branch would
     * survive a naive implementation too.
     */
    private Wizard koth() {
        return harness.wizards().define("event")
                .title("New event")
                .ask(KIND, step -> step.choice("Kind", List.of("koth", "conquest")))
                .when(KIND, kind -> kind.equals("koth"),
                        branch -> branch.ask(POINTS, step -> step.integer("Capture points")))
                .ask(NAME, step -> step.text("Display name"))
                .summary()
                .onFinish(values -> {
                    finished.incrementAndGet();
                    collected.set(values);
                })
                .build();
    }

    // ------------------------------------------------------- the branch dies

    @Test
    @DisplayName("changing the guard so a branch stops applying drops that branch's answers")
    void redoingTheGuardDropsAnAbandonedBranch() {
        harness.wizards().start(harness.player(), koth());
        harness.settle();

        harness.answer("koth");
        harness.answer("5");
        harness.answer("Sky Fortress");

        harness.redo("kind");
        harness.answer("conquest");
        harness.confirm();

        WizardValues values = collected.get();
        assertEquals(1, finished.get(), "the flow must apply once");
        assertEquals("conquest", values.get(KIND));
        // The whole bug, in one line. A conquest has no capture points, and a
        // plugin handed one would either write a field its config has no place
        // for or throw reading a key it never asked about.
        assertFalse(values.has(POINTS),
                "an answer belonging to a branch that no longer applies must not reach onFinish");
        assertEquals("Sky Fortress", values.get(NAME),
                "changing the kind must not cost the player the name they already typed");
        assertEquals(2, values.size(), "exactly the answers a conquest has");
    }

    // ------------------------------------------------------ the branch wakes

    @Test
    @DisplayName("changing the guard so a branch starts applying asks its steps before the review returns")
    void redoingTheGuardAsksANewBranch() {
        harness.wizards().start(harness.player(), koth());
        harness.settle();

        harness.answer("conquest");
        harness.answer("Sky Fortress");

        harness.redo("kind");
        harness.answer("koth");
        // If the flow went straight back to the review here, this answer would
        // be read as the confirmation and the run would finish with no points.
        harness.answer("7");
        harness.confirm();

        WizardValues values = collected.get();
        assertEquals(1, finished.get(), "the flow must apply once");
        assertEquals("koth", values.get(KIND));
        assertTrue(values.has(POINTS),
                "a branch that now applies must be asked, not silently skipped");
        assertEquals(7L, values.get(POINTS));
        assertEquals("Sky Fortress", values.get(NAME),
                "a step outside the branch must keep its answer");
    }

    @Test
    @DisplayName("the player is really asked the new branch's question, not answered for")
    void theNewQuestionIsShown() {
        harness.wizards().start(harness.player(), koth());
        harness.settle();
        harness.answer("conquest");
        harness.answer("Sky Fortress");

        int before = harness.prompts().size();
        harness.redo("kind");
        harness.answer("koth");

        List<String> asked = new ArrayList<>(harness.prompts().subList(before, harness.prompts().size()));
        assertTrue(asked.contains("Capture points"),
                "the player must see the question the branch introduced; asked instead: " + asked);
    }

    // ------------------------------------------------------------ nesting

    @Test
    @DisplayName("a branch inside a branch that stops applying takes its children with it")
    void nestedBranchesFallTogether() {
        AtomicReference<WizardValues> seen = new AtomicReference<>();
        Wizard nested = harness.wizards().define("nested")
                .ask(KIND, step -> step.choice("Kind", List.of("koth", "conquest")))
                .when(KIND, kind -> kind.equals("koth"), branch -> branch
                        .ask(FLAG_CHOICE, step -> step.choice("Flag", List.of("timed", "instant")))
                        .when(FLAG_CHOICE, flag -> flag.equals("timed"), inner -> inner
                                .ask(DURATION, step -> step.integer("Seconds to hold"))))
                .ask(NAME, step -> step.text("Display name"))
                .summary()
                .onFinish(seen::set)
                .build();

        harness.wizards().start(harness.player(), nested);
        harness.settle();

        harness.answer("koth");
        harness.answer("timed");
        harness.answer("30");
        harness.answer("Sky Fortress");

        harness.redo("kind");
        harness.answer("conquest");
        harness.confirm();

        WizardValues values = seen.get();
        assertFalse(values.has(FLAG_CHOICE), "the outer branch's answer must go");
        // The one that a per-branch undo would miss: DURATION belongs to a
        // branch guarded on a key that itself only exists inside the branch
        // that just died. Nothing points at it from the top of the definition.
        assertFalse(values.has(DURATION),
                "a nested branch must fall with the branch that contained it");
        assertEquals("Sky Fortress", values.get(NAME));
        assertEquals(2, values.size());
    }

    @Test
    @DisplayName("a nested branch is asked in order when its parent starts applying")
    void nestedBranchesWakeTogether() {
        AtomicReference<WizardValues> seen = new AtomicReference<>();
        Wizard nested = harness.wizards().define("nested")
                .ask(KIND, step -> step.choice("Kind", List.of("koth", "conquest")))
                .when(KIND, kind -> kind.equals("koth"), branch -> branch
                        .ask(FLAG_CHOICE, step -> step.choice("Flag", List.of("timed", "instant")))
                        .when(FLAG_CHOICE, flag -> flag.equals("timed"), inner -> inner
                                .ask(DURATION, step -> step.integer("Seconds to hold"))))
                .ask(NAME, step -> step.text("Display name"))
                .summary()
                .onFinish(seen::set)
                .build();

        harness.wizards().start(harness.player(), nested);
        harness.settle();
        harness.answer("conquest");
        harness.answer("Sky Fortress");

        harness.redo("kind");
        harness.answer("koth");
        // The inner branch is guarded on an answer that does not exist yet when
        // the flow is re-resolved. It has to survive that pass undecided and be
        // decided for real once the player answers its guard.
        harness.answer("timed");
        harness.answer("45");
        harness.confirm();

        WizardValues values = seen.get();
        assertEquals("timed", values.get(FLAG_CHOICE));
        assertEquals(45L, values.get(DURATION),
                "a branch nested under one that just woke must still be reachable");
        assertEquals("Sky Fortress", values.get(NAME));
    }

    // --------------------------------------------------- the ordinary redo

    @Test
    @DisplayName("redoing a key that guards nothing re-asks only that key")
    void redoingAPlainKeyAsksNothingElse() {
        harness.wizards().start(harness.player(), koth());
        harness.settle();
        harness.answer("koth");
        harness.answer("5");
        harness.answer("Old Name");

        int before = harness.prompts().size();
        harness.redo("name");
        harness.answer("New Name");
        // The review is the next thing shown; anything else means a step was
        // re-asked that nobody changed.
        List<String> after = harness.prompts().subList(before, harness.prompts().size());
        assertEquals(0, after.stream().filter(prompt -> prompt.equals("Capture points")).count(),
                "an answer the redo cannot affect must not be asked again; saw: " + after);

        harness.confirm();

        WizardValues values = collected.get();
        assertEquals("New Name", values.get(NAME), "the redone answer must be the new one");
        assertEquals("koth", values.get(KIND), "an untouched answer must survive a redo");
        assertEquals(5L, values.get(POINTS), "a branch's answer must survive an unrelated redo");
        assertEquals(3, values.size());
    }

    @Test
    @DisplayName("redoing the guard without changing it leaves everything alone")
    void redoingTheGuardToTheSameValueKeepsTheBranch() {
        harness.wizards().start(harness.player(), koth());
        harness.settle();
        harness.answer("koth");
        harness.answer("5");
        harness.answer("Sky Fortress");

        harness.redo("kind");
        harness.answer("koth");
        harness.confirm();

        WizardValues values = collected.get();
        assertEquals(5L, values.get(POINTS),
                "re-resolving must not throw away a branch that still applies");
        assertEquals("Sky Fortress", values.get(NAME));
        assertEquals(3, values.size());
    }

    // ------------------------------------------------------------- bounded

    @Test
    @DisplayName("re-resolving a branch does not buy the player extra rounds of the review")
    void reresolutionIsBounded() {
        // The cap counts denials of the review, and re-resolution adds none of
        // its own. Without that, changing the guard would be a free round and a
        // player could hold the flow open past the limit that exists to stop
        // exactly that.
        harness.wizards().using(new WizardSettings(60, 2, false, "%title%"));
        AtomicReference<WizardOutcome> ended = new AtomicReference<>();
        Wizard event = harness.wizards().define("event")
                .ask(KIND, step -> step.choice("Kind", List.of("koth", "conquest")))
                .when(KIND, kind -> kind.equals("koth"),
                        branch -> branch.ask(POINTS, step -> step.integer("Capture points")))
                .summary()
                .onFinish(values -> finished.incrementAndGet())
                .onCancel(ended::set)
                .build();

        harness.wizards().start(harness.player(), event);
        harness.settle();
        harness.answer("conquest");

        harness.redo("kind");
        harness.answer("koth");
        harness.answer("3");

        harness.redo("kind");
        harness.answer("conquest");

        // The third denial is past the cap of two.
        harness.deny();
        harness.settle(2);

        assertEquals(0, finished.get(), "a run that ran out of redos must apply nothing");
        assertEquals(WizardOutcome.CANCELLED, ended.get(),
                "exceeding the redo cap ends the run rather than looping");
        harness.assertNothingLeaked();
    }
}
