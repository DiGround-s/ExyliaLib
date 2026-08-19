package net.exylia.lib.util.wizard;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.util.wizard.internal.WizardPeek;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every way a flow can stop, and what each of them owes.
 *
 * <p>{@code EventConfigWizard} had two endings: an answer, or nothing at all.
 * A timeout at step four, a disconnect at step five, a second
 * {@code /event create} starting over &mdash; each left the half-built state in
 * a static map and left the menu that opened the flow closed forever, because
 * the branch that reopened it only existed on the success path.
 *
 * <p>The rule here is the opposite and has no exceptions: exactly one outcome
 * is delivered, {@code onFinish} runs for exactly one of them, the reopen
 * callback runs for all of them, and nothing is left holding the player.
 */
class WizardEndingTest {

    private static final WizardKey<String> ID = WizardKey.text("id");
    private static final WizardKey<String> NAME = WizardKey.text("name");

    private WizardHarness harness;
    private final AtomicInteger finished = new AtomicInteger();
    private final AtomicInteger afterwards = new AtomicInteger();
    private final List<WizardOutcome> cancelled = new ArrayList<>();

    @BeforeEach
    void setUp() {
        harness = WizardHarness.start("Events", "DiGround");
    }

    @AfterEach
    void tearDown() {
        harness.stop();
    }

    private Wizard twoSteps() {
        return harness.wizards().define("event")
                .ask(ID, step -> step.id("Event id"))
                .ask(NAME, step -> step.text("Display name"))
                .summary()
                .onFinish(values -> finished.incrementAndGet())
                .onCancel(cancelled::add)
                .build();
    }

    private WizardRun startOne() {
        WizardRun run = harness.wizards().start(harness.player(), twoSteps(),
                afterwards::incrementAndGet);
        harness.settle();
        return run;
    }

    // --------------------------------------------------------- nothing applies

    @Test
    @DisplayName("a cancelled flow applies nothing")
    void cancelAppliesNothing() {
        WizardRun run = startOne();
        harness.answer("blitz");

        run.cancel();
        harness.settle();

        assertEquals(0, finished.get(), "a player who changed their mind created nothing");
        assertEquals(WizardOutcome.CANCELLED, outcomeOf(run));
        harness.assertNothingLeaked();
    }

    @Test
    @DisplayName("a flow that ran past its limit applies nothing")
    void timeoutAppliesNothing() {
        harness.wizards().using(new WizardSettings(30, 3, false, "%title%"));
        WizardRun run = startOne();
        harness.answer("blitz");

        // The run timeout is the safety net that a per-question timeout cannot
        // be: a player answering one question every fifty seconds trips no
        // single question's limit and holds the flow forever.
        harness.settle(30 * 20 + 5);

        assertEquals(0, finished.get(), "a flow nobody finished must create nothing");
        assertEquals(WizardOutcome.TIMED_OUT, outcomeOf(run));
        harness.assertNothingLeaked();
    }

    @Test
    @DisplayName("a player who leaves ends their flow as a disconnect, not a cancel")
    void disconnectAppliesNothing() {
        WizardRun run = startOne();
        harness.answer("blitz");

        harness.disconnect();

        assertEquals(0, finished.get(), "somebody who left created nothing");
        assertEquals(WizardOutcome.DISCONNECTED, outcomeOf(run),
                "a disconnect is its own outcome: the player did not decide anything");
        assertEquals(0, Wizards.active(), "a leaving player must free their wizard slot");
    }

    @Test
    @DisplayName("a replaced flow applies nothing")
    void replacementAppliesNothing() {
        WizardRun first = startOne();
        harness.answer("blitz");

        harness.wizards().start(harness.player(), twoSteps());
        harness.settle();

        assertEquals(0, finished.get(), "the flow that was displaced must create nothing");
        assertEquals(WizardOutcome.REPLACED, outcomeOf(first));
    }

    @Test
    @DisplayName("disabling the owning plugin applies nothing")
    void shutdownAppliesNothing() {
        WizardRun run = startOne();
        harness.answer("blitz");

        Wizards.release(harness.plugin().getName());
        harness.settle();

        assertEquals(0, finished.get(), "a plugin being torn down must create nothing");
        assertEquals(WizardOutcome.SHUT_DOWN, outcomeOf(run),
                "SHUT_DOWN rather than CANCELLED, so a caller does not try to save its work");
        harness.assertNothingLeaked();
    }

    // ------------------------------------------------------------- afterwards

    @Test
    @DisplayName("what was meant to happen afterwards happens however the flow ended")
    void afterwardsRunsOnEveryEnding() {
        // The menu that opened the wizard has to come back either way. In the
        // old flow the reopen lived on the success path only, so a player who
        // cancelled was left staring at nothing.
        record Ending(String name, java.util.function.Consumer<WizardRun> stop) {
        }
        List<Ending> endings = List.of(
                new Ending("confirmed", run -> {
                    harness.answer("blitz");
                    harness.answer("Blitz");
                    harness.confirm();
                }),
                new Ending("cancelled", WizardRun::cancel),
                new Ending("timed out", run -> harness.settle(30 * 20 + 5)),
                new Ending("replaced", run -> {
                    harness.wizards().start(harness.player(), twoSteps());
                    harness.settle();
                }),
                new Ending("shut down", run -> Wizards.release(harness.plugin().getName())));

        for (Ending ending : endings) {
            harness.stop();
            harness = WizardHarness.start("Events", "DiGround");
            harness.wizards().using(new WizardSettings(30, 3, false, "%title%"));
            afterwards.set(0);

            WizardRun run = harness.wizards().start(harness.player(), twoSteps(),
                    afterwards::incrementAndGet);
            harness.settle();
            ending.stop().accept(run);
            harness.settle(3);

            assertEquals(1, afterwards.get(),
                    "the reopen callback must run exactly once when a flow is " + ending.name());
        }
    }

    @Test
    @DisplayName("nothing is reopened for a player who is no longer there")
    void afterwardsSkipsAnOfflinePlayer() {
        // Reopening a menu for somebody who left is at best wasted work and at
        // worst an exception on a player object the server has released.
        startOne();
        harness.answer("blitz");

        harness.disconnect();
        harness.settle(3);

        assertEquals(0, afterwards.get(),
                "a player who left must not have anything opened for them");
    }

    // ------------------------------------------------------------ replacement

    @Test
    @DisplayName("a second flow displaces the first without evicting itself")
    void replacementKeepsTheNewRun() {
        // A real bug, already fixed and locked down here: the displaced run's
        // own cleanup used to remove the player's entry unconditionally, so
        // the flow that had just won lost its slot and a third could start
        // alongside it. The removal has to be conditional on the entry still
        // being the run that is ending.
        WizardRun first = startOne();
        harness.answer("blitz");

        WizardRun second = harness.wizards().start(harness.player(), twoSteps());
        harness.settle();

        assertEquals(WizardOutcome.REPLACED, outcomeOf(first));
        assertFalse(second.isFinished(), "the new flow must still be running");
        assertEquals(1, Wizards.active(),
                "the surviving flow must still hold the player's wizard slot");
        assertTrue(WizardPeek.hasSession(harness.player().getUniqueId()),
                "the player's session must be the new run, not nothing");
        assertEquals(second, WizardPeek.sessionOf(harness.player().getUniqueId()),
                "the slot must hold the flow that won, not the one it displaced");
    }

    // -------------------------------------------------------------- ownership

    @Test
    @DisplayName("releasing one plugin leaves another plugin's flows alone")
    void releaseIsPerPlugin() {
        WizardRun mine = startOne();

        FakePlayer other = new FakePlayer("Alex");
        FakeServer.online(harness.player(), other.player());
        PluginWizards theirs = Wizards.of(FakeServer.newPlugin("OtherPlugin", null));
        WizardRun theirRun = theirs.start(other.player(),
                theirs.define("theirs").ask(ID, step -> step.id("Their id")).build());
        harness.settle();

        Wizards.release(harness.plugin().getName());
        harness.settle();

        assertEquals(WizardOutcome.SHUT_DOWN, outcomeOf(mine));
        assertFalse(theirRun.isFinished(),
                "one plugin disabling must not take another plugin's flow with it");

        theirRun.cancel();
        harness.settle();
    }

    // ------------------------------------------------------------------ leaks

    @Test
    @DisplayName("no ending leaves the player holding anything")
    void nothingSurvivesAnEnding() {
        // The four things a run holds that a player can feel: an open question,
        // the block selector, a boss bar, and the one wizard slot. A stuck slot
        // means they can never run a wizard again until they reconnect.
        record Ending(String name, Runnable stop) {
        }
        List<Ending> endings = List.of(
                new Ending("cancelled", () -> Wizards.running(harness.player())
                        .ifPresent(WizardRun::cancel)),
                new Ending("timed out", () -> harness.settle(30 * 20 + 5)),
                new Ending("shut down", () -> Wizards.release("Events")));

        for (Ending ending : endings) {
            harness.stop();
            harness = WizardHarness.start("Events", "DiGround");
            harness.wizards().using(new WizardSettings(30, 3, true, "%title%"));

            harness.wizards().start(harness.player(), twoSteps());
            harness.settle();
            harness.answer("blitz");

            ending.stop().run();
            harness.settle(3);

            assertEquals(0, Wizards.active(),
                    "a flow that was " + ending.name() + " still holds the player's slot");
            assertEquals(0, harness.openQuestions(),
                    "a flow that was " + ending.name() + " left a question open");
            assertEquals(0, FakeServer.liveRepeatingTasks(),
                    "a flow that was " + ending.name() + " left a repeating task behind");
            assertEquals(0, net.exylia.lib.effect.internal.EffectRuntime.active(),
                    "a flow that was " + ending.name() + " left its bar on the player's screen");
        }
    }

    @Test
    @DisplayName("only the first ending counts, whichever arrives first")
    void endsExactlyOnce() {
        // Safe to cancel from a quit handler and a menu close in the same tick
        // is the whole reason the terminal slot is claimed atomically.
        WizardRun run = startOne();

        assertTrue(run.cancel(), "the first call is the one that ends it");
        assertFalse(run.cancel(), "a second cancel must do nothing");
        harness.settle();

        assertEquals(1, cancelled.size(), "the cancel handler must be told once");
        assertEquals(WizardOutcome.CANCELLED, cancelled.get(0));
    }

    /** Why a run ended, read from the stage the caller would have waited on. */
    private WizardOutcome outcomeOf(WizardRun run) {
        AtomicReference<WizardResult> seen = new AtomicReference<>();
        WizardPeek.result(run).thenAccept(seen::set);
        harness.settle();
        assertNotNull(seen.get(), "the run never told anybody how it ended");
        return seen.get().outcome();
    }
}
