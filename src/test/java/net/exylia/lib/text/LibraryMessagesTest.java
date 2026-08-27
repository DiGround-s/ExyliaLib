package net.exylia.lib.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The library's own wording.
 *
 * <p>What is checked is what a server owner can break by editing the file: a
 * deleted line, a section removed wholesale, and the placeholders the runtime
 * substitutes into. The wording itself is not asserted — that is what the file
 * is for — except where it names a tool, which is the mistake this file was
 * made to stop repeating.
 */
class LibraryMessagesTest {

    @Test
    @DisplayName("a deleted line falls back, so nothing is ever sent empty")
    void blankLinesFallBack() {
        LibraryMessages.Wizard emptied = new LibraryMessages.Wizard("  ", null, "", " ");
        assertEquals(LibraryMessages.Wizard.DEFAULT_STAND, emptied.stand());
        assertEquals(LibraryMessages.Wizard.DEFAULT_POINT, emptied.point());
        assertEquals(LibraryMessages.Wizard.DEFAULT_REGION, emptied.region());
        assertEquals(LibraryMessages.Wizard.DEFAULT_ITEM, emptied.item());

        LibraryMessages.Selection selection = new LibraryMessages.Selection(
                null, "", "  ", null, "", null, " ", "");
        assertEquals(LibraryMessages.Selection.DEFAULT_FIRST_CORNER, selection.firstCorner());
        assertEquals(LibraryMessages.Selection.DEFAULT_GUIDE_CONFIRM, selection.guideConfirm());
    }

    @Test
    @DisplayName("a section deleted wholesale falls back too")
    void missingSectionsFallBack() {
        LibraryMessages messages = new LibraryMessages(null, null);
        assertEquals(LibraryMessages.Wizard.DEFAULT_REGION, messages.wizard().region());
        assertEquals(LibraryMessages.Selection.DEFAULT_VOLUME, messages.selection().volume());
    }

    @Test
    @DisplayName("an owner's own wording is kept")
    void ownWordingIsKept() {
        assertEquals("click it", new LibraryMessages.Wizard("stand", "click it", "box", "hold").point());
    }

    @Test
    @DisplayName("the lines carry the placeholders the runtime fills in")
    void placeholdersArePresent() {
        LibraryMessages.Selection lines = new LibraryMessages.Selection();
        assertTrue(lines.firstCorner().contains("%x%"));
        assertTrue(lines.firstCorner().contains("%y%"));
        assertTrue(lines.firstCorner().contains("%z%"));
        assertTrue(lines.volume().contains("%blocks%"));
        assertTrue(lines.guideConfirm().contains("%blocks%"));
        assertTrue(lines.guideCorners().contains("%selector%"));
    }

    @Test
    @DisplayName("nothing names a tool the selector does not hand out")
    void nothingNamesTheWrongTool() {
        // The material is a setting, so the prompt says %selector% and lets the
        // runtime fill in whatever is actually being handed over. A literal here
        // is the bug: prompts naming a wooden axe outlived the golden one.
        LibraryMessages messages = new LibraryMessages();
        String all = (messages.wizard().region() + messages.selection().guideCorners())
                .toLowerCase(Locale.ROOT);
        assertFalse(all.contains("axe"));
        assertFalse(all.contains("wand"));
    }
}
