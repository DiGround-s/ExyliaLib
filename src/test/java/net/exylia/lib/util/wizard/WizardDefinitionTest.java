package net.exylia.lib.util.wizard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wiring mistakes that must be caught while the plugin is loading.
 *
 * <p>Each of these has a runtime shape that is far worse than a crash at
 * startup. Two steps under one key means the second answer overwrites the
 * first and the review can only ever show one of them. A {@code when} guarded
 * by a key nothing asked for is a branch that can never apply, which is a step
 * that quietly never happens &mdash; the hardest kind of bug to see, because
 * nothing at all appears to be wrong.
 *
 * <p>So both fail here, on the thread that reads the configuration, naming the
 * key that is wrong. The message matters as much as the throw: a
 * {@code WizardException} that does not say which key is a developer grepping
 * their own definition.
 */
class WizardDefinitionTest {

    private static final WizardKey<String> ID = WizardKey.text("id");
    private static final WizardKey<String> KIND = WizardKey.text("kind");
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

    @Test
    @DisplayName("two steps under one key are refused, naming the key")
    void duplicateKey() {
        WizardException thrown = assertThrows(WizardException.class, () ->
                harness.wizards().define("event")
                        .ask(ID, step -> step.id("Event id"))
                        .ask(ID, step -> step.text("Event id again"))
                        .build());

        assertTrue(thrown.getMessage().contains("'id'"),
                "the error must name the key that is declared twice: " + thrown.getMessage());
    }

    @Test
    @DisplayName("a key duplicated inside a branch is refused too")
    void duplicateKeyInsideABranch() {
        // The branch collector shares the flow's rules rather than copying
        // them, and this is the test that says so: a second copy of the check
        // would drift, and the branch copy is the harder path to reach.
        WizardException thrown = assertThrows(WizardException.class, () ->
                harness.wizards().define("event")
                        .ask(KIND, step -> step.id("Kind"))
                        .when(KIND, kind -> true,
                                branch -> branch.ask(KIND, step -> step.id("Kind again")))
                        .build());

        assertTrue(thrown.getMessage().contains("'kind'"), thrown.getMessage());
    }

    @Test
    @DisplayName("a branch guarded by a key nothing asks for is refused, naming the key")
    void branchOnAnUnaskedKey() {
        WizardException thrown = assertThrows(WizardException.class, () ->
                harness.wizards().define("event")
                        .ask(ID, step -> step.id("Event id"))
                        .when(KIND, kind -> true,
                                branch -> branch.ask(POINTS, step -> step.integer("Points")))
                        .build());

        assertTrue(thrown.getMessage().contains("'kind'"),
                "the error must name the key nothing asks for: " + thrown.getMessage());
    }

    @Test
    @DisplayName("a branch guarded by a key asked only after it is still refused")
    void branchBeforeItsGuard() {
        // Declaration order is what decides this, not membership: a branch is
        // evaluated against the answers collected so far, so one placed above
        // its own guard could never apply however the flow ran.
        assertThrows(WizardException.class, () ->
                harness.wizards().define("event")
                        .when(KIND, kind -> true,
                                branch -> branch.ask(POINTS, step -> step.integer("Points")))
                        .ask(KIND, step -> step.id("Kind"))
                        .build());
    }

    @Test
    @DisplayName("a branch may be guarded by a key an earlier branch asked for")
    void branchOnAKeyFromAnEarlierBranch() {
        // The mirror of the rule above, and the reason the nested collector
        // shares its parent's set instead of copying it: forbidding this would
        // forbid a legitimate chain of questions.
        Wizard event = harness.wizards().define("event")
                .ask(KIND, step -> step.id("Kind"))
                .when(KIND, kind -> kind.equals("koth"),
                        branch -> branch.ask(POINTS, step -> step.integer("Points")))
                .when(POINTS, points -> points > 3,
                        branch -> branch.ask(ID, step -> step.id("Event id")))
                .build();

        assertEquals(3, event.stepCount(), "every declared step counts towards the upper bound");
    }

    @Test
    @DisplayName("a flow with no steps is refused")
    void emptyFlow() {
        WizardException thrown = assertThrows(WizardException.class, () ->
                harness.wizards().define("event").build());
        assertTrue(thrown.getMessage().contains("event"), thrown.getMessage());
    }

    @Test
    @DisplayName("a branch with no steps is refused")
    void emptyBranch() {
        assertThrows(WizardException.class, () ->
                harness.wizards().define("event")
                        .ask(KIND, step -> step.id("Kind"))
                        .when(KIND, kind -> true, branch -> {
                        })
                        .build());
    }

    @Test
    @DisplayName("a blank prompt is refused: an empty line tells the player nothing")
    void blankPrompt() {
        assertThrows(WizardException.class, () ->
                harness.wizards().define("event")
                        .pick(WizardKey.location("spawn"), "  ")
                        .build());
    }

    @Test
    @DisplayName("a wizard needs an id and a title a log line can name")
    void blankIdentity() {
        assertThrows(WizardException.class, () -> harness.wizards().define(" "));
        assertThrows(WizardException.class, () ->
                harness.wizards().define("event").title(""));
    }

    @Test
    @DisplayName("a definition is immutable and shared, holding nothing about a run")
    void definitionIsShared() {
        // The whole design rests on this: a flow compiled once at load and used
        // by everybody. The old wizard welded definition and run together in a
        // static map, so two players in one flow shared one object.
        Wizard event = harness.wizards().define("event")
                .ask(KIND, step -> step.choice("Kind", List.of("koth", "conquest")))
                .summary()
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> event.steps().add(event.steps().get(0)));

        harness.wizards().start(harness.player(), event);
        harness.settle();
        harness.answer("koth");

        assertEquals(1, event.steps().size(),
                "running a flow must not change the flow itself");
    }
}
