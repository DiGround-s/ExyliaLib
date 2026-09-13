package net.exylia.lib.config.internal;

import net.exylia.lib.config.internal.DefaultsMerge.Change;
import net.exylia.lib.config.internal.DefaultsMerge.Kind;
import net.exylia.lib.config.internal.DefaultsMerge.Result;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultsMergeTest {

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration yaml = DefaultsMerge.yaml();
        yaml.loadFromString(text);
        return yaml;
    }

    private static Object at(YamlConfiguration yaml, String... path) {
        return yaml.get(String.join(String.valueOf(DefaultsMerge.SEPARATOR), path));
    }

    @Test
    void aServerWithNothingReviewedIsOfferedWhatItLacksAndKeepsTheRest() throws Exception {
        YamlConfiguration disk = yaml("cooldown: 45\nitems:\n  close:\n    slot: 49\n");
        YamlConfiguration shipped = yaml("cooldown: 30\nitems:\n  close:\n    slot: 53\n  info:\n    slot: 4\n");

        Result result = DefaultsMerge.merge(disk, null, shipped);

        assertEquals(45, at(disk, "cooldown"));
        assertEquals(49, at(disk, "items", "close", "slot"));
        assertNull(at(disk, "items", "info"));
        assertTrue(result.added().isEmpty());
        assertEquals(List.of("items.info"), result.pending().stream().map(Change::dotted).toList());
        assertEquals(Kind.ADDED, result.pending().getFirst().kind());
    }

    @Test
    void anOfferedAdditionStaysOfferedUntilDecided() throws Exception {
        YamlConfiguration disk = yaml("cooldown: 30\n");
        YamlConfiguration shipped = yaml("cooldown: 30\nrange: 12\n");
        Result first = DefaultsMerge.merge(disk, null, shipped);

        Result again = DefaultsMerge.merge(disk, first.reviewed(), shipped);
        assertEquals(List.of("range"), again.pending().stream().map(Change::dotted).toList());
        assertNull(at(disk, "range"));

        YamlConfiguration reviewed = again.reviewed();
        DefaultsMerge.keep(reviewed, again.pending().getFirst());
        Result decided = DefaultsMerge.merge(disk, reviewed, shipped);

        assertTrue(decided.pending().isEmpty());
        assertTrue(decided.added().isEmpty());
        assertNull(at(disk, "range"));
    }

    @Test
    void aChangedDefaultOnAnUntouchedValueWaitsForTheOwner() throws Exception {
        YamlConfiguration disk = yaml("cooldown: 30\n");
        YamlConfiguration reviewed = yaml("cooldown: 30\n");
        YamlConfiguration shipped = yaml("cooldown: 35\n");

        Result result = DefaultsMerge.merge(disk, reviewed, shipped);

        assertEquals(30, at(disk, "cooldown"));
        assertEquals(1, result.pending().size());
        Change change = result.pending().getFirst();
        assertEquals(Kind.CHANGED, change.kind());
        assertEquals(30, change.current());
        assertEquals(35, change.shipped());
        assertEquals(30, at(result.reviewed(), "cooldown"));
    }

    @Test
    void applyingWritesTheNewDefaultAndKeepingLeavesTheOwnersValue() throws Exception {
        YamlConfiguration shipped = yaml("cooldown: 35\nrange: 12\n");
        YamlConfiguration disk = yaml("cooldown: 30\nrange: 10\n");
        Result first = DefaultsMerge.merge(disk, yaml("cooldown: 30\nrange: 10\n"), shipped);
        YamlConfiguration reviewed = first.reviewed();

        DefaultsMerge.apply(disk, reviewed, first.pending().get(0));
        DefaultsMerge.keep(reviewed, first.pending().get(1));

        assertEquals(35, at(disk, "cooldown"));
        assertEquals(10, at(disk, "range"));
        assertTrue(DefaultsMerge.merge(disk, reviewed, shipped).pending().isEmpty());
    }

    @Test
    void anEditedValueIsNeverPending() throws Exception {
        YamlConfiguration disk = yaml("cooldown: 45\n");

        Result result = DefaultsMerge.merge(disk, yaml("cooldown: 30\n"), yaml("cooldown: 35\n"));

        assertEquals(45, at(disk, "cooldown"));
        assertTrue(result.pending().isEmpty());
        assertEquals(35, at(result.reviewed(), "cooldown"));
    }

    @Test
    void aDefaultThePluginDroppedIsPendingRemoval() throws Exception {
        YamlConfiguration disk = yaml("items:\n  old:\n    slot: 1\n");

        Result result = DefaultsMerge.merge(disk, yaml("items:\n  old:\n    slot: 1\n"), yaml("items: {}\n"));

        assertEquals(1, at(disk, "items", "old", "slot"));
        assertEquals(Kind.REMOVED, result.pending().getFirst().kind());
        assertEquals("items.old", result.pending().getFirst().dotted());
    }

    @Test
    void aDefaultTheOwnerDeletedIsNotAddedBack() throws Exception {
        YamlConfiguration disk = yaml("items: {}\n");
        YamlConfiguration reviewed = yaml("items:\n  close:\n    slot: 49\n");

        Result result = DefaultsMerge.merge(disk, reviewed, yaml("items:\n  close:\n    slot: 53\n"));

        assertNull(at(disk, "items", "close"));
        assertTrue(result.added().isEmpty());
        assertTrue(result.pending().isEmpty());
    }

    @Test
    void aNewKeyInAReviewedFileIsAddedWithItsComments() throws Exception {
        YamlConfiguration disk = yaml("cooldown: 30\n");
        YamlConfiguration shipped = yaml("cooldown: 30\n# Blocks a player may travel.\nrange: 12\n");

        Result result = DefaultsMerge.merge(disk, yaml("cooldown: 30\n"), shipped);

        assertEquals(12, at(disk, "range"));
        assertEquals(1, result.added().size());
        assertTrue(disk.saveToString().contains("# Blocks a player may travel."), disk.saveToString());
    }

    @Test
    void aKeyWithADotStaysOneKey() throws Exception {
        YamlConfiguration disk = yaml("rates:\n  '1.5': 3\n");

        Result result = DefaultsMerge.merge(disk, yaml("rates:\n  '1.5': 3\n"), yaml("rates:\n  '1.5': 4\n"));

        assertEquals(List.of("rates", "1.5"), result.pending().getFirst().path());
    }

    @Test
    void listsAreOneValue() throws Exception {
        YamlConfiguration disk = yaml("lore:\n- a\n- mine\n");

        Result result = DefaultsMerge.merge(disk, yaml("lore:\n- a\n- b\n"), yaml("lore:\n- a\n- c\n"));

        assertTrue(result.pending().isEmpty());
        assertEquals(List.of("a", "mine"), at(disk, "lore"));
    }

    @Test
    void writingKeepsCommentsAndEmojiAsTheyWere() throws Exception {
        String text = "# Header\ntitle: '{primary}&lDUEL ⏱️'\n";
        YamlConfiguration disk = yaml(text);

        DefaultsMerge.merge(disk, yaml(text), yaml(text + "extra: 1\n"));

        String written = disk.saveToString();
        assertTrue(written.contains("# Header"), written);
        assertTrue(written.contains("⏱️"), written);
        assertFalse(written.contains("\\u"), written);
    }
}
