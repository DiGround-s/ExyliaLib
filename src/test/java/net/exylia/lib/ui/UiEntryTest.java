package net.exylia.lib.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a row of a list carries.
 *
 * <p>The part that mattered enough to add: the thing the row is <em>about</em>.
 * ExyliaCommons had no such place, so a handler worked the kit back out from
 * the item it was drawn as, and menus kept static maps keyed by player to make
 * up the difference.
 */
class UiEntryTest {

    private record Kit(String id) {
    }

    @Test
    @DisplayName("a row carries the value it is about")
    void carriesItsValue() {
        Kit kit = new Kit("boxing");

        UiEntry entry = UiEntry.of(kit).with("kit_name", "Boxing").build();

        assertEquals(kit, entry.value());
        assertEquals(kit, entry.value(Kit.class).orElseThrow());
    }

    @Test
    @DisplayName("asking for the wrong type gets nothing, not a cast failure")
    void wrongTypeIsEmpty() {
        UiEntry entry = UiEntry.of(new Kit("boxing")).build();

        assertTrue(entry.value(String.class).isEmpty());
    }

    @Test
    @DisplayName("a row can be only text")
    void textOnlyRow() {
        UiEntry entry = UiEntry.row().with("label", "Nothing here").build();

        assertNull(entry.value());
        assertTrue(entry.value(Object.class).isEmpty());
    }

    @Test
    @DisplayName("a placeholder name is accepted written either way")
    void nameSpelling() {
        UiEntry entry = UiEntry.row()
                .with("kit_name", "Boxing")
                .with("%kit_icon%", "DIAMOND_SWORD")
                .build();

        assertEquals("Boxing", entry.values().get("kit_name"));
        assertEquals("DIAMOND_SWORD", entry.values().get("kit_icon"),
                "percent signs are stripped, so both spellings are one name");
    }

    @Test
    @DisplayName("a null value is empty text, not the word null")
    void nullValues() {
        UiEntry entry = UiEntry.row().with("clan", null).build();

        assertEquals("", entry.values().get("clan"));
    }

    @Test
    @DisplayName("numbers are written out, so a caller need not convert")
    void nonStringValues() {
        UiEntry entry = UiEntry.row().with("rank", 3).with("kdr", 1.5).build();

        assertEquals("3", entry.values().get("rank"));
        assertEquals("1.5", entry.values().get("kdr"));
    }

    @Test
    @DisplayName("a row names which template draws it")
    void template() {
        assertEquals("selected", UiEntry.row().template("selected").build().template());
        assertNull(UiEntry.row().build().template(), "no name means the ordinary row");
    }

    @Test
    @DisplayName("a row can bring its own item instead of a template")
    void rowWithItsOwnItem() {
        // The kit room case. There is no template, so the row is the item.
        UiEntry entry = UiEntry.row().item(null).build();

        assertFalse(entry.hasItem(), "no item given is no item");
        assertNull(entry.item());
    }

    @Test
    @DisplayName("a value is literal unless the caller asked for formatting")
    void formattedValues() {
        // The distinction is whose value it is. A colour written in a config
        // is formatting; a name somebody typed is data, and parsing it would
        // let a player recolour whatever line they appear on.
        UiEntry entry = UiEntry.row()
                .with("player_name", "{error}Steve")
                .withFormatted("rank", "{highlight}&lMVP")
                .build();

        assertEquals(java.util.Set.of("rank"), entry.formatted());
        assertFalse(entry.formatted().contains("player_name"),
                "a name a player typed stays data");
        assertEquals("{error}Steve", entry.values().get("player_name"),
                "the value itself is kept as written either way");
    }

    @Test
    @DisplayName("a row that never asked for formatting has none")
    void nothingIsFormattedByDefault() {
        // The default has to be the safe one: a plugin that never thought
        // about this must not be the one that opens the hole.
        assertTrue(UiEntry.row().with("kit_name", "Boxing").build().formatted().isEmpty());
    }

    @Test
    @DisplayName("setting a value again decides afresh how it is inserted")
    void reassigningAValueChangesItsKind() {
        // Whichever call came last is the caller's intent. Leaving the old
        // answer behind would make a value's kind depend on a call that is no
        // longer there.
        assertTrue(UiEntry.row()
                .withFormatted("rank", "{highlight}MVP")
                .with("rank", "MVP")
                .build().formatted().isEmpty(), "literal after formatted is literal");

        assertEquals(java.util.Set.of("rank"), UiEntry.row()
                .with("rank", "MVP")
                .withFormatted("rank", "{highlight}MVP")
                .build().formatted(), "formatted after literal is formatted");
    }

    @Test
    @DisplayName("a formatted name is accepted written either way, like a literal one")
    void formattedNameSpelling() {
        UiEntry entry = UiEntry.row().withFormatted("%rank%", "{highlight}MVP").build();

        assertEquals(java.util.Set.of("rank"), entry.formatted());
        assertEquals("{highlight}MVP", entry.values().get("rank"));
    }

    @Test
    @DisplayName("values keep the order they were given")
    void valuesKeepOrder() {
        UiEntry entry = UiEntry.row()
                .with("first", 1)
                .with("second", 2)
                .with("third", 3)
                .build();

        assertEquals(java.util.List.of("first", "second", "third"),
                java.util.List.copyOf(entry.values().keySet()));
    }

    @Test
    @DisplayName("a live value is read again, and an unchanged row comes back as itself")
    void liveValuesAreReadAgain() {
        java.util.concurrent.atomic.AtomicInteger seconds = new java.util.concurrent.atomic.AtomicInteger(30);
        UiEntry entry = UiEntry.of(new Kit("boxing"))
                .with("kit_name", "Boxing")
                .liveFormatted("next_reset", () -> seconds.get() + "s")
                .build();

        assertTrue(entry.isLive());
        assertEquals("30s", entry.values().get("next_reset"));
        assertTrue(entry.formatted().contains("next_reset"));
        assertTrue(entry == entry.refreshed(), "nothing moved, so nothing is redrawn");

        seconds.set(29);
        UiEntry fresh = entry.refreshed();

        assertEquals("29s", fresh.values().get("next_reset"));
        assertEquals("Boxing", fresh.values().get("kit_name"));
        assertEquals(entry.value(), fresh.value());
        assertTrue(fresh.isLive());
    }

    @Test
    @DisplayName("a fixed value written over a live one stops it being read again")
    void fixedValueReplacesLiveOne() {
        UiEntry entry = UiEntry.row().live("count", () -> 1).with("count", 2).build();

        assertFalse(entry.isLive());
        assertEquals("2", entry.values().get("count"));
    }

    @Test
    @DisplayName("a value handed over as a lambda is read again on refresh")
    void lambdaIsLive() {
        int[] left = {30};
        UiEntry entry = UiEntry.of(new Kit("boxing"))
                .with("kit_name", "Boxing")
                .withFormatted("left", () -> "{info}" + left[0] + "s")
                .build();

        assertTrue(entry.isLive());
        assertSame(entry, entry.refreshed(), "nothing moved, so the same row");

        left[0] = 29;
        UiEntry later = entry.refreshed();
        assertEquals("{info}29s", later.values().get("left"));
        assertTrue(later.formatted().contains("left"), "stays formatted");
        assertEquals("Boxing", later.values().get("kit_name"));
    }

    @Test
    @DisplayName("a verbatim lambda keeps its letters across refreshes")
    void verbatimLambda() {
        String[] line = {"hi"};
        UiEntry entry = UiEntry.row().withVerbatim("line", () -> line[0]).build();
        line[0] = "bye";

        UiEntry later = entry.refreshed();
        assertEquals("bye", later.values().get("line"));
        assertTrue(later.verbatim().contains("line"));
    }

    @Test
    @DisplayName("with(name, null) still means an empty value, not a live one")
    void nullIsEmpty() {
        UiEntry entry = UiEntry.row().with("name", null).build();

        assertEquals("", entry.values().get("name"));
        assertFalse(entry.isLive());
    }

    @Test
    @DisplayName("a lambda typed as an object is still live, never its toString")
    void lambdaAsObject() {
        java.util.function.Supplier<String> reader = () -> "now";
        Object value = reader;

        UiEntry entry = UiEntry.row().with("when", value).build();

        assertEquals("now", entry.values().get("when"));
        assertTrue(entry.isLive());
    }

    @Test
    @DisplayName("a plain value set over a live one stops it being live")
    void plainReplacesLive() {
        UiEntry entry = UiEntry.row().with("x", () -> "a").with("x", "b").build();

        assertEquals("b", entry.values().get("x"));
        assertFalse(entry.isLive());
    }
}
