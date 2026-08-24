package net.exylia.lib.panel;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.config.Configs;
import net.exylia.lib.panel.internal.PanelPrompts;
import net.exylia.lib.panel.internal.PanelRenderer;
import net.exylia.lib.panel.internal.PanelRuntime;
import net.exylia.lib.panel.internal.RecordRebuilder;
import net.exylia.lib.panel.internal.SettingsEngine;
import net.exylia.lib.util.Effects;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rebuild is pure, and it is what stands between a bad edit and the file.
 *
 * <p>A record is put back together through its canonical constructor, in
 * declared component order, from the values a panel is holding. Two things can
 * go wrong and both must be answers rather than exceptions: a value of the wrong
 * type, and a compact constructor that refuses what it was given.
 *
 * <p>Nothing here needs a server. That is the point of decision 4: the rebuild
 * happens <em>before</em> any write path exists, so a rejection costs a message
 * and nothing else — the working copy is untouched and the config is never
 * opened.
 */
class RecordRebuilderTest {

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Five components, so "the other four survived" is a real assertion. */
    record Profile(String name, int age, double weight, boolean admin, List<String> tags) {
    }

    /** A component whose declared type is primitive, which cannot take null. */
    record Counter(int count) {
    }

    // ------------------------------------------------------------------
    // 3.1 — the canonical constructor, in declared order
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rebuilds through the canonical constructor in declared component order")
    void rebuildsInDeclaredOrder() {
        var rebuilt = RecordRebuilder.rebuild(Profile.class, values(
                "name", "Steve",
                "age", 41,
                "weight", 82.5,
                "admin", true,
                "tags", List.of("staff", "builder")));

        assertTrue(rebuilt.accepted(), "a well-typed set of values must rebuild: " + rebuilt.rejection());
        assertEquals(new Profile("Steve", 41, 82.5, true, List.of("staff", "builder")), rebuilt.value());
    }

    @Test
    @DisplayName("editing one component leaves the other four equal to what they were")
    void editingOneComponentPreservesTheRest() {
        Profile before = new Profile("Steve", 41, 82.5, true, List.of("staff"));

        Map<String, Object> values = new LinkedHashMap<>(RecordRebuilder.componentsOf(before));
        values.put("age", 42);
        Profile after = RecordRebuilder.rebuild(Profile.class, values).value();

        assertEquals(42, after.age(), "the edited component must carry the new value");
        assertEquals(before.name(), after.name());
        assertEquals(before.weight(), after.weight());
        assertEquals(before.admin(), after.admin());
        assertEquals(before.tags(), after.tags());
    }

    @Test
    @DisplayName("component order follows the record, not the map it was handed")
    void orderFollowsTheRecordNotTheMap() {
        // Deliberately reversed: a rebuild that read the map's iteration order
        // would hand "Steve" to age and 41 to name, and the record would take
        // it because both slots exist.
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("tags", List.of());
        reversed.put("admin", false);
        reversed.put("weight", 1.0);
        reversed.put("age", 41);
        reversed.put("name", "Steve");

        var rebuilt = RecordRebuilder.rebuild(Profile.class, reversed);

        assertTrue(rebuilt.accepted(), () -> String.valueOf(rebuilt.rejection()));
        assertEquals("Steve", rebuilt.value().name());
        assertEquals(41, rebuilt.value().age());
    }

    // ------------------------------------------------------------------
    // 3.1 — a type mismatch is an answer, not a throw
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a value of the wrong type is rejected, naming the component and the type it needed")
    void wrongTypeIsRejected() {
        Map<String, Object> values = new LinkedHashMap<>(RecordRebuilder.componentsOf(
                new Profile("Steve", 41, 82.5, true, List.of())));
        values.put("age", "forty-one");

        var rebuilt = RecordRebuilder.rebuild(Profile.class, values);

        assertFalse(rebuilt.accepted(), "a String cannot be an int");
        assertNull(rebuilt.value(), "a rejected rebuild must hand back nothing to write");
        assertTrue(rebuilt.rejection().contains("age"),
                "the message must name the component a player would go and fix, was: " + rebuilt.rejection());
        assertTrue(rebuilt.rejection().contains("int"),
                "and the type it needed, was: " + rebuilt.rejection());
    }

    @Test
    @DisplayName("null in a primitive component is rejected rather than thrown at the constructor")
    void nullInAPrimitiveIsRejected() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("count", null);

        var rebuilt = RecordRebuilder.rebuild(Counter.class, values);

        assertFalse(rebuilt.accepted(), "an int has no empty value");
        assertTrue(rebuilt.rejection().contains("count"), rebuilt.rejection());
    }

    @Test
    @DisplayName("null in a reference component is accepted, because the record permits it")
    void nullInAReferenceComponentIsAccepted() {
        Map<String, Object> values = new LinkedHashMap<>(RecordRebuilder.componentsOf(
                new Profile("Steve", 41, 82.5, true, List.of())));
        values.put("name", null);

        var rebuilt = RecordRebuilder.rebuild(Profile.class, values);

        assertTrue(rebuilt.accepted(), "the panel must render and rebuild a null the record allows: "
                + rebuilt.rejection());
        assertNull(rebuilt.value().name());
    }

    @Test
    @DisplayName("a missing component is rejected rather than silently defaulted")
    void missingComponentIsRejected() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", "Steve");

        var rebuilt = RecordRebuilder.rebuild(Profile.class, values);

        assertFalse(rebuilt.accepted(),
                "quietly defaulting a component the panel forgot would erase what was on disk");
        assertTrue(rebuilt.rejection().contains("age"), rebuilt.rejection());
    }

    // ------------------------------------------------------------------
    // 3.2 — a compact constructor that refuses
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a compact constructor that throws surfaces its own message, not a reflection wrapper")
    void compactConstructorRejectionCarriesItsCauseMessage() {
        // ParsedEffect refuses a blank name in its compact constructor. That
        // arrives as an InvocationTargetException, whose message is null: what
        // a player must be shown is the cause's.
        var rebuilt = RecordRebuilder.rebuild(Effects.ParsedEffect.class, values(
                "name", "",
                "amplifier", 0,
                "duration", 200));

        assertFalse(rebuilt.accepted(), "a blank effect name must not rebuild");
        assertNull(rebuilt.value());
        assertEquals("an effect needs a name", rebuilt.rejection(),
                "the player must be shown what the record said, not \"InvocationTargetException\"");
    }

    @Test
    @DisplayName("a rejection is a player-facing validation failure carrying the same message")
    void rejectionBecomesAValidationFailure() {
        var rebuilt = RecordRebuilder.rebuild(Effects.ParsedEffect.class, values(
                "name", "",
                "amplifier", 0,
                "duration", 200));

        var validation = rebuilt.validation();

        assertFalse(validation.valid(), "a rejected rebuild must not validate");
        assertEquals(List.of("an effect needs a name"), validation.messages());
    }

    @Test
    @DisplayName("an accepted rebuild validates and has nothing to say")
    void acceptedRebuildValidates() {
        var rebuilt = RecordRebuilder.rebuild(Effects.ParsedEffect.class, values(
                "name", "SPEED",
                "amplifier", 1,
                "duration", 200));

        assertTrue(rebuilt.accepted());
        assertTrue(rebuilt.validation().valid());
        assertEquals(List.of(), rebuilt.validation().messages());
    }

    // ------------------------------------------------------------------
    // 3.2 — a rejected rebuild never reaches ConfigFile.update
    // ------------------------------------------------------------------

    /**
     * A record whose compact constructor refuses a value a panel can produce.
     *
     * <p>Real shape, not contrived: several config records normalise or reject
     * in theirs, and a blank name is exactly what a player clears a text field
     * to.
     */
    record Named(String name, int weight) {
        Named {
            if (name != null && name.isBlank()) {
                throw new IllegalArgumentException("a name cannot be blank");
            }
        }

        Named() {
            this("thing", 1);
        }
    }

    @Test
    @DisplayName("a value the record refuses never reaches the config file")
    void aRefusedValueNeverReachesTheConfig() {
        FakeServer.install();
        FakeServer.reset();
        Configs.releaseAll();
        Plugin plugin = FakeServer.newPlugin("RecordRebuilderPlugin", folder.toFile());
        Player viewer = new FakePlayer("Steve").player();
        FakeServer.online(viewer);

        var prompts = new SettingsControlMappingTest.ScriptedPrompts();
        prompts.textAnswer = "   ";
        PanelPrompts.install(prompts);
        PanelRenderer.DrawSink previous = PanelRenderer.sink((slot, kind, entry) -> {
            if (entry instanceof SettingsEngine.Control control
                    && control.field().name().equals("name")) {
                slots.add(slot);
            }
        });
        try {
            FakeConfigFile<Named> file = FakeConfigFile.of(plugin, "named", new Named());
            SettingsEngine<Named> engine = SettingsEngine.forTests(plugin, viewer, file, null);
            engine.draw();
            engine.activate(slots.get(0));

            // The working copy holds a value the record will not take. Saving it
            // must be refused here, where nothing has been written yet — that is
            // the whole reason the rebuild is pure and happens first.
            assertFalse(engine.save(), "a save whose rebuild is rejected must not be started");
            FakeServer.tick(4);

            assertEquals(0, file.updates(),
                    "ConfigFile.update must never be reached with values the record refuses: "
                            + "the rebuild is checked before a write path exists, so there is "
                            + "nothing half-applied to undo");
            assertEquals("thing", file.get().name(),
                    "and what is on disk must still be what was there when the panel opened");

            // The other way in: a plugin holding the session and calling save on
            // it directly. A guard only on the panel's own button is a guard
            // this caller walks straight past, so it is asserted separately.
            assertTrue(engine.session().save(),
                    "the session's diff is not empty, so it does attempt the write");
            FakeServer.tick(4);
            assertEquals(0, file.updates(),
                    "and the write path must refuse it too, for the same reason");
        } finally {
            PanelRenderer.sink(previous);
            PanelPrompts.install(null);
            PanelRuntime.releaseAll();
            Configs.releaseAll();
            FakeServer.reset();
        }
    }

    @TempDir
    Path folder;

    private final List<Integer> slots = new java.util.ArrayList<>();

    // ------------------------------------------------------------------
    // Purity
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the rebuild does not touch the values it was handed")
    void rebuildDoesNotMutateItsInput() {
        Map<String, Object> values = new LinkedHashMap<>(RecordRebuilder.componentsOf(
                new Profile("Steve", 41, 82.5, true, List.of("staff"))));
        Map<String, Object> before = Map.copyOf(values);

        RecordRebuilder.rebuild(Profile.class, values);
        values.put("age", "not a number");
        RecordRebuilder.rebuild(Profile.class, values);

        values.put("age", 41);
        assertEquals(before, values,
                "the working copy a panel is holding must survive both an accepted and a rejected rebuild");
    }

    @Test
    @DisplayName("reading a record's components gives every one, keyed by its declared name")
    void componentsOfReadsEveryComponent() {
        Map<String, Object> components = RecordRebuilder.componentsOf(
                new Profile("Steve", 41, 82.5, true, List.of("staff")));

        assertEquals(List.of("name", "age", "weight", "admin", "tags"),
                List.copyOf(components.keySet()),
                "in canonical-constructor order, which is what the panel draws in");
        assertEquals("Steve", components.get("name"));
        assertEquals(41, components.get("age"));
        assertEquals(List.of("staff"), components.get("tags"));
    }

    // ------------------------------------------------------------------

    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put((String) pairs[index], pairs[index + 1]);
        }
        return values;
    }
}
