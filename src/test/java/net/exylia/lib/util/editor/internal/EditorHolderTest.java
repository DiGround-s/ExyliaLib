package net.exylia.lib.util.editor.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.util.editor.EditorButton;
import net.exylia.lib.util.editor.EditorDescriptor;
import net.exylia.lib.util.editor.EditorView;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        return holder(List.of(), entries);
    }

    private EditorHolder<String> holder(List<EditorButton<String>> buttons, String... entries) {
        return new EditorHolder<>(plugin(), new Words(), String.class, "{primary}TEST",
                new ArrayList<>(List.of(entries)), buttons,
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
                "{primary}TEST", new ArrayList<>(List.of(first, second)), List.of(),
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

    // -------------------------------------------------------------- buttons

    @Test
    @DisplayName("a screen with no buttons of its own keeps all forty-five rows")
    void fullPageWithoutButtons() {
        EditorHolder<String> holder = holder(many(45));

        assertEquals(45, holder.pageSize());
        assertEquals(1, holder.pages());
        assertNull(holder.buttonAt(EditorHolder.BAND),
                "Without buttons the bottom row is still rows");
        assertEquals("36", holder.at(EditorHolder.BAND));
    }

    @Test
    @DisplayName("a screen with buttons gives up its bottom row to hold them")
    void bandTakesTheBottomRow() {
        EditorHolder<String> holder = holder(List.of(button()), many(45));

        assertEquals(36, holder.pageSize());
        assertEquals(2, holder.pages(), "Nine rows moved to the second page");
        assertNull(holder.at(EditorHolder.BAND), "The band is not a row any more");
        assertNotNull(holder.buttonAt(EditorHolder.BAND));
    }

    @Test
    @DisplayName("buttons sit in the order they were added, and nowhere else")
    void bandOrder() {
        EditorButton<String> first = button();
        EditorButton<String> second = button();
        EditorHolder<String> holder = holder(List.of(first, second), "a");

        assertSame(first, holder.buttonAt(EditorHolder.BAND));
        assertSame(second, holder.buttonAt(EditorHolder.BAND + 1));
        assertNull(holder.buttonAt(EditorHolder.BAND + 2), "Two buttons, two slots");
        assertNull(holder.buttonAt(EditorHolder.BAND - 1), "The row above is still a row");
    }

    @Test
    @DisplayName("a button replaces the working copy and nothing else")
    void buttonReplacesTheWorkingCopy() {
        EditorHolder<String> holder = holder(List.of(button()), "a", "b");

        holder.buttonAt(EditorHolder.BAND).click(holder.view(viewer.player()));

        assertEquals(List.of("preset"), holder.entries());
        assertNull(saved, "A button changes the working copy; only save writes");
        assertEquals(0, cancelled);
    }

    @Test
    @DisplayName("a button that shortens the list leaves nobody on a page that is gone")
    void replaceClampsThePage() {
        EditorHolder<String> holder = holder(List.of(button()), many(80));
        holder.page(2);

        holder.buttonAt(EditorHolder.BAND).click(holder.view(viewer.player()));

        assertEquals(1, holder.pages());
        assertEquals(0, holder.page());
    }

    @Test
    @DisplayName("a button sees the list as it stands, unsaved edits included")
    void buttonSeesTheWorkingCopy() {
        List<String> seen = new ArrayList<>();
        EditorButton<String> reader = EditorButton.<String>of("PAPER")
                .onClick(view -> seen.addAll(view.entries()))
                .build();
        EditorHolder<String> holder = holder(List.of(reader), "a");
        holder.add("added since opening");

        holder.buttonAt(EditorHolder.BAND).click(holder.view(viewer.player()));

        assertEquals(List.of("a", "added since opening"), seen);
    }

    @Test
    @DisplayName("what a button reads cannot be changed behind the editor's back")
    void theViewIsACopy() {
        EditorHolder<String> holder = holder(List.of(button()), "a");

        List<String> read = holder.view(viewer.player()).entries();

        assertThrows(UnsupportedOperationException.class, () -> read.add("b"));
        assertEquals(List.of("a"), holder.entries());
    }

    @Test
    @DisplayName("the preset button is one line and loads what it was given")
    void presetButton() {
        EditorButton<String> preset = EditorButton.preset(() -> List.of("one", "two"));
        EditorHolder<String> holder = holder(List.of(preset), "old");

        preset.click(holder.view(viewer.player()));

        assertEquals(List.of("one", "two"), holder.entries());
        assertTrue(preset.isGlowing());
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

    /** A button that loads a one-row preset. */
    private static EditorButton<String> button() {
        return EditorButton.<String>of("CHEST_MINECART")
                .name("{highlight}&lPRESET")
                .onClick(view -> view.replaceAll(List.of("preset")))
                .build();
    }

    // ------------------------------------------------------- adding several

    @Test
    @DisplayName("an import adds every row it brought and lands on the last page")
    void addAll() {
        EditorHolder<String> holder = holder("a");

        holder.addAll(List.of(many(50)));

        assertEquals(51, holder.entries().size());
        assertEquals(2, holder.pages());
        assertEquals(1, holder.page(), "The rows an admin just imported are the ones to show");
    }

    @Test
    @DisplayName("a descriptor that answers with a null row does not poison the page")
    void addAllSkipsNulls() {
        EditorHolder<String> holder = holder("a");

        holder.addAll(java.util.Arrays.asList("b", null, "c"));

        assertEquals(List.of("a", "b", "c"), holder.entries());
    }

    @Test
    @DisplayName("by default one press of add creates exactly one element")
    void createAllWrapsCreate() {
        List<String> created = new Words().createAll(viewer.player())
                .toCompletableFuture().join();

        assertEquals(List.of("new"), created);
    }

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
