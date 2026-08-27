package net.exylia.lib.util.wizard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The values a wizard hands around, tested without a server.
 *
 * <p>They are worth their own file because they are what the plugin actually
 * touches. A key is the answer to a {@code Map<String, Object>} of half-built
 * state, where reading a field was a cast and a guess and a key spelled one way
 * going in and another coming out compiled perfectly and failed on the server.
 * A result is the answer to a flow that could only end two ways.
 *
 * <p>All of it is pure, so none of it needs a fake player: if any of this
 * needed one, that would itself be the bug.
 */
class WizardValueTypesTest {

    private static final WizardKey<String> ID = WizardKey.text("id");
    private static final WizardKey<Long> SLOTS = WizardKey.integer("slots");
    private static final WizardKey<String> MISSING = WizardKey.text("missing");

    // --------------------------------------------------------------- values

    private static WizardValues values() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", "blitz");
        raw.put("slots", 24L);
        return WizardValues.of(raw);
    }

    @Test
    @DisplayName("an answer comes back as the type its key declared")
    void typedReads() {
        WizardValues values = values();
        assertEquals("blitz", values.get(ID));
        assertEquals(24L, values.getLong(SLOTS));
        assertEquals("blitz", values.getText(ID));
    }

    @Test
    @DisplayName("reading an answer nobody collected names it and lists what was collected")
    void undeclaredKeyThrows() {
        // A null here would become a NullPointerException three lines later
        // inside the plugin's own creation code, with nothing pointing back at
        // the key that was actually wrong.
        WizardException thrown = assertThrows(WizardException.class, () -> values().get(MISSING));

        assertTrue(thrown.getMessage().contains("'missing'"),
                "the error must name the key: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("id"),
                "the error must list what is there, so a typo is visible: " + thrown.getMessage());
    }

    @Test
    @DisplayName("a key of the wrong type is refused, naming both types")
    void wrongTypeThrows() {
        WizardKey<Long> lying = WizardKey.integer("id");
        WizardException thrown = assertThrows(WizardException.class, () -> values().get(lying));

        assertTrue(thrown.getMessage().contains("String"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("Long"), thrown.getMessage());
    }

    @Test
    @DisplayName("an answer behind a branch is read with a fallback, not an error")
    void fallbackForBranchAnswers() {
        // The right accessor for anything behind a when: a branch that did not
        // run collected nothing, and that is not a mistake.
        assertEquals("none", values().getOr(MISSING, "none"));
        assertFalse(values().has(MISSING));
        assertTrue(values().has(ID));
    }

    @Test
    @DisplayName("the answers keep the order they were given in, which is the order shown")
    void orderIsPreserved() {
        assertEquals(List.of("id", "slots"), List.copyOf(values().asMap().keySet()));
        assertEquals(2, values().size());
    }

    @Test
    @DisplayName("a snapshot is a copy nobody can write through")
    void snapshotsAreCopies() {
        // A predicate able to write into the session's own map could change
        // what a later step asks, which is a flow rewriting itself under the
        // player.
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", "blitz");
        WizardValues snapshot = WizardValues.of(source);

        source.put("id", "changed");
        source.put("extra", "sneaked in");

        assertEquals("blitz", snapshot.get(ID), "a snapshot must not follow its source");
        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.asMap().put("id", "written through"));
    }

    @Test
    @DisplayName("empty answers are a value, not a null")
    void emptyIsAValue() {
        assertEquals(0, WizardValues.empty().size());
        assertFalse(WizardValues.empty().has(ID));
    }

    // ------------------------------------------------------------------ keys

    @Test
    @DisplayName("each factory declares the type it produces")
    void keyFactories() {
        assertEquals(String.class, WizardKey.text("a").type());
        assertEquals(Long.class, WizardKey.integer("a").type());
        assertEquals(BigDecimal.class, WizardKey.decimal("a").type());
        assertEquals(Boolean.class, WizardKey.flag("a").type());
        assertEquals(Duration.class, WizardKey.duration("a").type());
        assertEquals(org.bukkit.Location.class, WizardKey.location("a").type());
        assertEquals(org.bukkit.inventory.ItemStack.class, WizardKey.item("a").type());
        assertEquals(net.exylia.lib.region.SelectionResult.class, WizardKey.region("a").type());
        assertEquals("a", WizardKey.text("a").name());
    }

    @Test
    @DisplayName("a key is its name and its type together")
    void keyIdentity() {
        assertEquals(WizardKey.text("id"), WizardKey.text("id"));
        assertEquals(WizardKey.text("id").hashCode(), WizardKey.text("id").hashCode());
        // Same name, different type: not the same answer, and treating them as
        // one is how a Long gets read out of a String.
        assertNotEquals(WizardKey.text("id"), WizardKey.integer("id"));
        assertNotEquals(WizardKey.text("id"), WizardKey.text("name"));
    }

    @Test
    @DisplayName("a key needs a name, so an error can say which one is wrong")
    void keyNeedsAName() {
        assertThrows(WizardException.class, () -> WizardKey.text(" "));
        assertThrows(NullPointerException.class, () -> WizardKey.text(null));
    }

    // ---------------------------------------------------------------- result

    @Test
    @DisplayName("a completed result carries its answers and runs the completed branch")
    void completedResult() {
        WizardResult result = WizardResult.completed(values());
        AtomicReference<WizardValues> got = new AtomicReference<>();
        AtomicReference<WizardOutcome> other = new AtomicReference<>();

        result.ifCompleted(got::set).otherwise(other::set);

        assertTrue(result.completed());
        assertEquals(WizardOutcome.COMPLETED, result.outcome());
        assertEquals("blitz", got.get().get(ID));
        assertEquals(null, other.get(), "the else-branch must not run on a completed run");
        assertEquals("blitz", result.value(ID).orElseThrow());
        assertTrue(result.optional().isPresent());
    }

    @Test
    @DisplayName("an ended result carries why, and refuses to invent answers")
    void endedResult() {
        WizardResult result = WizardResult.ended(WizardOutcome.CANCELLED);
        AtomicReference<WizardOutcome> why = new AtomicReference<>();

        result.ifCompleted(values -> {
            throw new AssertionError("a cancelled run has no answers to act on");
        }).otherwise(why::set);

        assertFalse(result.completed());
        assertEquals(WizardOutcome.CANCELLED, why.get());
        assertTrue(result.optional().isEmpty());
        assertTrue(result.value(ID).isEmpty());
        // Louder than a null: a caller that skipped the check finds out here
        // rather than three lines into its own creation code.
        assertThrows(NoSuchElementException.class, result::values);
    }

    @Test
    @DisplayName("a completed result cannot be made without its answers")
    void completedNeedsValues() {
        assertThrows(IllegalArgumentException.class,
                () -> WizardResult.ended(WizardOutcome.COMPLETED));
    }

    @Test
    @DisplayName("only a completed run has answers, and only the player ends some of them")
    void outcomeQuestions() {
        assertTrue(WizardOutcome.COMPLETED.hasValues());
        for (WizardOutcome outcome : WizardOutcome.values()) {
            assertEquals(outcome == WizardOutcome.COMPLETED, outcome.hasValues(),
                    outcome + " must not claim to carry answers");
        }
        assertTrue(WizardOutcome.CANCELLED.byPlayer());
        assertTrue(WizardOutcome.DISCONNECTED.byPlayer());
        // A replace is a plugin changing its mind, not the player: reopening a
        // menu on one is how two screens end up fighting each other.
        assertFalse(WizardOutcome.REPLACED.byPlayer());
        assertFalse(WizardOutcome.FAILED.byPlayer());
        assertFalse(WizardOutcome.TIMED_OUT.byPlayer());
    }

    // -------------------------------------------------------------- settings

    @Test
    @DisplayName("the defaults are the Exylia ones")
    void settingsDefaults() {
        WizardSettings defaults = new WizardSettings();
        assertEquals(300, defaults.timeoutSeconds());
        assertEquals(3, defaults.maxRedos());
        assertTrue(defaults.progress());
        assertTrue(defaults.progressText().contains("%step%"));
    }

    @Test
    @DisplayName("a run shorter than one question's own limit is raised to a usable one")
    void settingsClampTheTimeout() {
        // A run of five seconds would end the flow while the player was still
        // reading the first prompt.
        assertEquals(30, new WizardSettings(5, 3, true, "x").timeoutSeconds());
        assertEquals(30, new WizardSettings(-100, 3, true, "x").timeoutSeconds());
        assertEquals(600, new WizardSettings(600, 3, true, "x").timeoutSeconds());
    }

    @Test
    @DisplayName("the redo cap is clamped, and zero keeps its meaning")
    void settingsClampTheRedos() {
        // Zero is not "no limit": a review that may be denied but never edited
        // is still a review, and denying it simply cancels.
        assertEquals(0, new WizardSettings(300, 0, true, "x").maxRedos());
        assertEquals(0, new WizardSettings(300, -5, true, "x").maxRedos());
        assertEquals(20, new WizardSettings(300, 999, true, "x").maxRedos());
    }

    @Test
    @DisplayName("a blank progress text falls back rather than drawing an empty bar")
    void settingsFallBackOnBlankText() {
        assertFalse(new WizardSettings(300, 3, true, "  ").progressText().isBlank());
        assertFalse(new WizardSettings(300, 3, true, null).progressText().isBlank());
        assertEquals("custom %step%", new WizardSettings(300, 3, true, "custom %step%").progressText());
    }
}
