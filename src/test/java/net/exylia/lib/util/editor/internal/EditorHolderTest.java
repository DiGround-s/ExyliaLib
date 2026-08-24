package net.exylia.lib.util.editor.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.util.editor.EditorDescriptor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The working copy, the pages, and the one ending.
 *
 * <p>None of this needs a window: what it decides is which element a slot means,
 * what an edit replaces, and who gets told the screen ended. Drawing is the only
 * part that needs a server, and it is the part with nothing to decide.
 *
 * <p>The identity test is the ExyliaCommons bug, kept out on purpose: four of
 * its five editors addressed a row by slot index, so an edit that landed after
 * the list had changed underneath edited a different row.
 */
class EditorHolderTest {

    private FakePlayer viewer;
    private List<String> saved;
    private int cancelled;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        viewer = new FakePlayer("Steve");
        saved = null;
        cancelled = 0;
    }

    @AfterEach
    void tearDown() {
        FakeServer.reset();
    }

    private EditorHolder<String> holder(String... entries) {
        return new EditorHolder<>(plugin(), new Words(), String.class, "{primary}TEST",
                new ArrayList<>(List.of(entries)),
                list -> saved = list,
                () -> cancelled++,
                viewer.player());
    }

    // ------------------------------------------------------------------ pages

    @Test
    @DisplayName("a short list is one page")
    void onePage() {
        assertEquals(1, holder("a", "b").pages());
        assertEquals(1, holder().pages(), "An empty list is still a page, not zero pages");
    }

    @Test
    @DisplayName("a page holds forty-five rows and the next one starts at forty-six")
    void pageSize() {
        EditorHolder<String> holder = holder(many(46));

        assertEquals(2, holder.pages());
        assertEquals("0", holder.at(0));
        assertEquals("44", holder.at(44));
        assertNull(holder.at(45), "Slot 45 is the add button, never a row");

        holder.page(1);
        assertEquals("45", holder.at(0));
        assertNull(holder.at(1), "The second page has one row and forty-four empty slots");
    }

    @Test
    @DisplayName("a page number outside the list is pulled back to one that exists")
    void pageIsClamped() {
        EditorHolder<String> holder = holder("a");

        holder.page(7);
        assertEquals(0, holder.page());
        holder.page(-3);
        assertEquals(0, holder.page());
    }

    @Test
    @DisplayName("deleting the last row of the last page steps back a page")
    void deletingShrinksThePages() {
        EditorHolder<String> holder = holder(many(46));
        holder.page(1);

        holder.remove("45");

        assertEquals(1, holder.pages());
        assertEquals(0, holder.page(), "Nobody should be left looking at a page that is gone");
    }

    // ------------------------------------------------------------- identity

    @Test
    @DisplayName("an edit replaces the row it was opened on, not the row now in its slot")
    void replaceByIdentity() {
        // Two equal strings, deliberately: the editor must not decide by value.
        String first = new String("same");
        String second = new String("same");
        EditorHolder<String> holder = new EditorHolder<>(plugin(), new Words(), String.class,
                "{primary}TEST", new ArrayList<>(List.of(first, second)),
                list -> saved = list, () -> cancelled++, viewer.player());

        holder.replace(second, "edited");

        assertSame(first, holder.entries().get(0), "The other row must be untouched");
        assertEquals("edited", holder.entries().get(1));
    }

    @Test
    @DisplayName("an edit whose row was deleted underneath is added rather than lost")
    void replaceOfAMissingRow() {
        EditorHolder<String> holder = holder("a", "b");

        holder.remove("a");
        holder.replace("a", "edited");

        assertEquals(List.of("b", "edited"), holder.entries());
    }

    @Test
    @DisplayName("adding jumps to the page the new row landed on")
    void addFollowsTheRow() {
        EditorHolder<String> holder = holder(many(45));

        holder.add("new");

        assertEquals(2, holder.pages());
        assertEquals(1, holder.page(), "A row you cannot see was not added as far as you know");
    }

    // -------------------------------------------------------------- endings

    @Test
    @DisplayName("saving tells the caller the list, once")
    void saveOnce() {
        EditorHolder<String> holder = holder("a");

        holder.save();
        holder.save();
        holder.cancel();

        assertEquals(List.of("a"), saved);
        assertEquals(0, cancelled, "A saved editor is not also a cancelled one");
    }

    @Test
    @DisplayName("cancelling tells the caller nothing was kept, once")
    void cancelOnce() {
        EditorHolder<String> holder = holder("a");

        holder.cancel();
        holder.cancel();
        holder.save();

        assertNull(saved);
        assertEquals(1, cancelled);
    }

    @Test
    @DisplayName("the first ending claims it, whichever it was")
    void oneEnding() {
        EditorHolder<String> holder = holder("a");

        assertTrue(holder.finish());
        assertFalse(holder.finish());
        assertTrue(holder.isFinished());
    }

    // ------------------------------------------------------------------

    private static String[] many(int count) {
        String[] values = new String[count];
        for (int index = 0; index < count; index++) {
            values[index] = String.valueOf(index);
        }
        return values;
    }

    /** A descriptor over strings: the engine must not know what it is editing. */
    private static final class Words implements EditorDescriptor<String> {

        @Override
        public String label(String entry) {
            return entry;
        }

        @Override
        public String icon(String entry) {
            return "PAPER";
        }

        @Override
        public List<String> lore(String entry) {
            return List.of();
        }

        @Override
        public String create() {
            return "new";
        }

        @Override
        public String copy(String entry) {
            return entry + "-copy";
        }

        @Override
        public CompletionStage<Optional<String>> edit(Player viewer, String entry) {
            return CompletableFuture.completedFuture(Optional.of(entry));
        }

        @Override
        public String typeKey() {
            return "words";
        }
    }

    private static Plugin plugin() {
        return FakeServer.newPlugin("Events", null);
    }
}
