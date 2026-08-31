package net.exylia.lib.util.loot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a menu row is told to draw and to call each kind of line.
 *
 * <p>No server: the answer is a string in the item module's grammar, and the
 * whole point of that grammar is that deciding it costs nothing.
 */
class LootEntryTest {

    @Test
    @DisplayName("a stored item draws as itself, not as a chest")
    void snapshotDrawsAsTheItem() {
        String snapshot = "bytes:rO0ABXNyABpvcmcuYnVra2l0LmlubmVyLkZha2VJdGVt";

        LootEntry entry = LootEntry.item(snapshot).build();

        // The bug this covers: every custom item in a table drew as a CHEST, so
        // a page of forty lines was forty identical chests.
        assertEquals(snapshot, entry.resolvedIcon());
    }

    @Test
    @DisplayName("a material line draws as its material and a head as its head string")
    void plainSourcesAreUnchanged() {
        assertEquals("DIAMOND_SWORD", LootEntry.item("diamond_sword").build().resolvedIcon());
        assertEquals("basehead-eyJ0ZXh0dXJlcyI6e30",
                LootEntry.item("basehead-eyJ0ZXh0dXJlcyI6e30").build().resolvedIcon());
    }

    @Test
    @DisplayName("a line with no payload draws as its type")
    void emptyLinesDrawAsTheirType() {
        assertEquals(LootType.ITEM.defaultIcon(), LootEntry.of(LootType.ITEM).build().resolvedIcon());
        assertEquals(LootType.COMMAND.defaultIcon(),
                LootEntry.command("say hi").build().resolvedIcon());
    }

    @Test
    @DisplayName("the name still does not decode a snapshot")
    void namesAreNotDecoded() {
        // Deliberate: a label is not worth an NBT read per row, and the row is
        // already drawn as the item itself.
        assertEquals("ITEM", LootEntry.item("bytes:rO0ABXNy").build().displayName());
        assertEquals("say hi", LootEntry.command("say hi").build().displayName());
    }
}
