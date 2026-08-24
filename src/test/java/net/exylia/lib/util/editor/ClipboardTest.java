package net.exylia.lib.util.editor;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Copy here, paste there.
 *
 * <p>The feature admins actually asked for: a loot table configured on one chest
 * and pasted onto the next twelve. What is asserted is that it survives the
 * screen closing, that it does not empty itself when pasted, and that two
 * different kinds of thing can never end up in each other's list.
 */
class ClipboardTest {

    private FakePlayer player;
    private FakePlayer other;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        player = new FakePlayer("Steve");
        other = new FakePlayer("Alex");
    }

    @AfterEach
    void tearDown() {
        Clipboard.forgetAll();
        FakeServer.reset();
    }

    @Test
    @DisplayName("what was copied comes back")
    void copyAndTake() {
        Clipboard.copy(player.player(), "loot", List.of("bread", "apple"));

        assertEquals(List.of("bread", "apple"),
                Clipboard.take(player.player(), "loot", String.class));
        assertEquals(2, Clipboard.size(player.player(), "loot"));
        assertTrue(Clipboard.has(player.player(), "loot"));
    }

    @Test
    @DisplayName("pasting does not empty the clipboard")
    void takeDoesNotConsume() {
        Clipboard.copy(player.player(), "loot", List.of("bread"));

        Clipboard.take(player.player(), "loot", String.class);

        assertEquals(1, Clipboard.size(player.player(), "loot"),
                "Pasting onto twelve chests is twelve presses, not one");
    }

    @Test
    @DisplayName("copying again replaces, so the clipboard is what you copied last")
    void copyReplaces() {
        Clipboard.copy(player.player(), "loot", List.of("bread", "apple"));
        Clipboard.copy(player.player(), "loot", List.of("diamond"));

        assertEquals(List.of("diamond"), Clipboard.take(player.player(), "loot", String.class));
    }

    @Test
    @DisplayName("copying nothing clears the bucket rather than leaving the last copy")
    void copyEmptyClears() {
        Clipboard.copy(player.player(), "loot", List.of("bread"));
        Clipboard.copy(player.player(), "loot", List.of());

        assertFalse(Clipboard.has(player.player(), "loot"));
    }

    @Test
    @DisplayName("two kinds of thing never paste into each other")
    void bucketsAreSeparate() {
        Clipboard.copy(player.player(), "loot", List.of("bread"));
        Clipboard.copy(player.player(), "rewards", List.of("coins"));

        assertEquals(List.of("bread"), Clipboard.take(player.player(), "loot", String.class));
        assertEquals(List.of("coins"), Clipboard.take(player.player(), "rewards", String.class));
    }

    @Test
    @DisplayName("a bucket holding something else answers with nothing, not a class cast")
    void wrongTypeIsSkipped() {
        Clipboard.copy(player.player(), "loot", List.of(1, 2, 3));

        assertEquals(List.of(), Clipboard.take(player.player(), "loot", String.class));
        assertEquals(3, Clipboard.size(player.player(), "loot"));
    }

    @Test
    @DisplayName("a clipboard belongs to one player")
    void perPlayer() {
        Clipboard.copy(player.player(), "loot", List.of("bread"));

        assertFalse(Clipboard.has(other.player(), "loot"));
    }

    @Test
    @DisplayName("a player who leaves takes their clipboard with them")
    void forgotten() {
        Clipboard.copy(player.player(), "loot", List.of("bread"));
        Clipboard.copy(player.player(), "rewards", List.of("coins"));

        Clipboard.forget(player.player().getUniqueId());

        assertFalse(Clipboard.has(player.player(), "loot"));
        assertFalse(Clipboard.has(player.player(), "rewards"));
    }

    @Test
    @DisplayName("clearing one bucket leaves the others alone")
    void clearOne() {
        Clipboard.copy(player.player(), "loot", List.of("bread"));
        Clipboard.copy(player.player(), "rewards", List.of("coins"));

        Clipboard.clear(player.player(), "loot");

        assertFalse(Clipboard.has(player.player(), "loot"));
        assertTrue(Clipboard.has(player.player(), "rewards"));
    }
}
