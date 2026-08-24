package net.exylia.lib.panel;

import net.exylia.lib.panel.internal.Diff;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What changed, and the rule that an empty diff writes nothing.
 *
 * <p>Pure value machinery: a diff compares two maps of names to values and
 * touches no Bukkit API, so it is tested without a server.
 */
class PanelDiffTest {

    @Test
    @DisplayName("one added, one removed and one changed key report exactly one each")
    void reportsOneOfEach() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("kept", "same");
        before.put("changed", 1);
        before.put("removed", true);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("kept", "same");
        after.put("changed", 2);
        after.put("added", "new");

        PanelDiff diff = Diff.between(before, after);

        assertEquals(List.of("added"), diff.added());
        assertEquals(List.of("removed"), diff.removed());
        assertEquals(List.of("changed"), diff.changed());
        assertFalse(diff.isEmpty(), "a diff with three differences is not empty");
    }

    @Test
    @DisplayName("an unchanged copy is an empty diff")
    void unchangedCopyIsEmpty() {
        Map<String, Object> before = Map.of("a", 1, "b", "two", "c", false);
        Map<String, Object> after = new LinkedHashMap<>(before);

        PanelDiff diff = Diff.between(before, after);

        assertEquals(List.of(), diff.added());
        assertEquals(List.of(), diff.removed());
        assertEquals(List.of(), diff.changed());
        assertTrue(diff.isEmpty());
    }

    @Test
    @DisplayName("an empty diff writes nothing to the store")
    void emptyDiffWritesNothing() {
        AtomicInteger writes = new AtomicInteger();
        Map<String, Object> unchanged = Map.of("volume", 1.0);

        boolean wrote = Diff.saveIfChanged(unchanged, new LinkedHashMap<>(unchanged),
                values -> writes.incrementAndGet());

        assertFalse(wrote, "save must report that it did not write");
        assertEquals(0, writes.get(),
                "no write may reach the store when nothing changed");
    }

    @Test
    @DisplayName("a real change does reach the store, with the new values")
    void changeReachesTheStore() {
        AtomicInteger writes = new AtomicInteger();
        Map<String, Object> written = new LinkedHashMap<>();

        boolean wrote = Diff.saveIfChanged(Map.of("volume", 1.0), Map.of("volume", 0.5),
                values -> {
                    writes.incrementAndGet();
                    written.putAll(values);
                });

        assertTrue(wrote, "a changed value must be written");
        assertEquals(1, writes.get(), "exactly one write, not one per changed key");
        assertEquals(0.5, written.get("volume"));
    }

    @Test
    @DisplayName("a null on one side is a change, not a crash")
    void nullIsComparedRatherThanThrown() {
        PanelDiff appeared = Diff.between(nullValued("name"), Map.of("name", "Steve"));
        assertEquals(List.of("name"), appeared.changed(),
                "a component that had no value and now has one has changed");

        PanelDiff vanished = Diff.between(Map.of("name", "Steve"), nullValued("name"));
        assertEquals(List.of("name"), vanished.changed(),
                "a component whose value was cleared has changed");

        PanelDiff both = Diff.between(nullValued("name"), nullValued("name"));
        assertTrue(both.isEmpty(), "still absent is still unchanged");
    }

    @Test
    @DisplayName("the diff lists names in a stable order, so two runs read the same")
    void orderIsStable() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("zulu", 1);
        before.put("alpha", 1);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("zulu", 2);
        after.put("alpha", 2);

        assertEquals(List.of("alpha", "zulu"), Diff.between(before, after).changed(),
                "names are sorted, so a diff shown to a player does not reshuffle between opens");
    }

    @Test
    @DisplayName("the diff is a value: its lists cannot be modified by whoever receives it")
    void diffIsImmutable() {
        PanelDiff diff = Diff.between(Map.of(), Map.of("added", 1));

        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> diff.added().add("sneaked-in"));
    }

    /** A map with one key explicitly holding no value. {@code Map.of} refuses null. */
    private static Map<String, Object> nullValued(String key) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(key, null);
        return values;
    }
}
