package net.exylia.lib.actionbar.internal;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What a player's action bar promises: segments from several plugins share one
 * line, a line that did not change is not spammed, and a line that emptied is
 * cleared exactly once.
 */
class ActionBarLineTest {

    private static final Component SEPARATOR = Component.text(" | ");

    @Test
    @DisplayName("Nothing to show composes to nothing")
    void emptyComposesToNull() {
        assertNull(ActionBarLine.compose(List.of(), SEPARATOR));
    }

    @Test
    @DisplayName("One segment is drawn without a separator")
    void singleSegment() {
        Component combat = Component.text("Combat 10s");
        assertEquals(combat, ActionBarLine.compose(List.of(combat), SEPARATOR));
    }

    @Test
    @DisplayName("Segments are joined in order with the separator between them")
    void joinsInOrder() {
        Component line = ActionBarLine.compose(List.of(Component.text("a"), Component.text("b")), SEPARATOR);
        assertEquals(Component.text().append(Component.text("a"), SEPARATOR, Component.text("b")).build().children(),
                line.children());
    }

    @Test
    @DisplayName("A changed line is sent at once, an unchanged one only after the resend window")
    void resendsUnchangedOncePerWindow() {
        ActionBarLine line = new ActionBarLine();
        Component text = Component.text("Pearl 3s");
        assertEquals(text, line.next(text, 0L));
        assertNull(line.next(text, ActionBarLine.RESEND_AFTER_MS - 1));
        assertEquals(text, line.next(text, ActionBarLine.RESEND_AFTER_MS));
        Component changed = Component.text("Pearl 2s");
        assertEquals(changed, line.next(changed, ActionBarLine.RESEND_AFTER_MS + 1));
    }

    @Test
    @DisplayName("Emptying the line clears it once and then stays quiet")
    void clearsOnce() {
        ActionBarLine line = new ActionBarLine();
        assertNull(line.next(null, 0L));
        line.next(Component.text("x"), 0L);
        assertEquals(Component.empty(), line.next(null, 10L));
        assertNull(line.next(null, 5000L));
    }
}
