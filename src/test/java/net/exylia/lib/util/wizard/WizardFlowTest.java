package net.exylia.lib.util.wizard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a player walking the whole way through is owed.
 *
 * <p>The flow this module replaces, {@code EventConfigWizard}, applied its
 * answers as it went: every step wrote a field of a half-built event, so a
 * player who reached step five had already created most of one. There was no
 * moment at which the flow was known to be complete, and therefore no single
 * place that could be trusted to run once with everything.
 *
 * <p>That single moment is {@code onFinish}, and these are the things it must
 * be true of: it runs once, it runs last, and it receives every answer with the
 * type the key declared rather than whatever the transport happened to collect.
 */
class WizardFlowTest {

    private static final WizardKey<String> ID = WizardKey.text("id");
    private static final WizardKey<Long> SLOTS = WizardKey.integer("slots");
    private static final WizardKey<Boolean> RANKED = WizardKey.flag("ranked");

    private WizardHarness harness;

    @BeforeEach
    void setUp() {
        harness = WizardHarness.start("Practice", "DiGround");
    }

    @AfterEach
    void tearDown() {
        harness.stop();
    }

    @Test
    @DisplayName("every answer reaches the finish callback, typed as its key declared it")
    void happyPath() {
        AtomicReference<WizardValues> collected = new AtomicReference<>();
        AtomicInteger ran = new AtomicInteger();

        Wizard arena = harness.wizards().define("arena")
                .title("New arena")
                .ask(ID, step -> step.id("Arena id"))
                .ask(SLOTS, step -> step.integer("Slots").range(2L, 64L))
                .ask(RANKED, step -> step.flag("Ranked?"))
                .summary()
                .onFinish(values -> {
                    ran.incrementAndGet();
                    collected.set(values);
                })
                .build();

        harness.wizards().start(harness.player(), arena);
        harness.settle();

        harness.answerAll("blitz", "24", "yes");
        harness.confirm();

        assertEquals(1, ran.get(), "the finish callback must run exactly once");
        WizardValues values = collected.get();
        // Typed reads, not map reads: a String where a Long was declared is
        // exactly the bug WizardKey exists to make impossible, and it would
        // only surface here.
        assertEquals("blitz", values.get(ID));
        assertEquals(24L, values.get(SLOTS));
        assertEquals(Boolean.TRUE, values.get(RANKED));
        assertEquals(3, values.size(), "the answers must be exactly what was asked for");
        harness.assertNothingLeaked();
    }

    @Test
    @DisplayName("a flow with no summary applies as soon as the last answer arrives")
    void withoutSummary() {
        AtomicInteger ran = new AtomicInteger();
        Wizard quick = harness.wizards().define("quick")
                .ask(ID, step -> step.id("Arena id"))
                .onFinish(values -> ran.incrementAndGet())
                .build();

        harness.wizards().start(harness.player(), quick);
        harness.settle();
        harness.answer("blitz");

        assertEquals(1, ran.get(), "a flow that declares no review has nothing left to wait for");
        harness.assertNothingLeaked();
    }

    @Test
    @DisplayName("the result stage carries the answers, once")
    void resultCarriesAnswers() {
        Wizard arena = harness.wizards().define("arena")
                .ask(ID, step -> step.id("Arena id"))
                .summary()
                .build();

        AtomicReference<WizardResult> seen = new AtomicReference<>();
        WizardRun run = harness.wizards().start(harness.player(), arena);
        net.exylia.lib.util.wizard.internal.WizardPeek.result(run).thenAccept(seen::set);
        harness.settle();

        harness.answer("blitz");
        harness.confirm();

        assertTrue(seen.get() != null, "a caller waiting on the run must be told");
        assertEquals(WizardOutcome.COMPLETED, seen.get().outcome());
        assertEquals("blitz", seen.get().values().get(ID));
        assertTrue(run.isFinished(), "the run must know it is over");
    }
}
