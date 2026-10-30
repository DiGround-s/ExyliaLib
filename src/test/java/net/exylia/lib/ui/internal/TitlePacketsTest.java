package net.exylia.lib.ui.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A retitle goes only to the window the server has open.
 *
 * <p>Sent in the same tick a window opened, it used to carry the id of the
 * window before it, because the new window's open packet had not gone out yet:
 * the client opened an empty window under the new title and ignored every
 * item sent to the real one.
 */
class TitlePacketsTest {

    @Test
    @DisplayName("a retitle waits while the last window seen is the previous one")
    void waitsForTheNewWindow() {
        assertEquals(TitlePackets.Decision.WAIT, TitlePackets.decide(7, 6));
        assertEquals(TitlePackets.Decision.WAIT, TitlePackets.decide(7, -1));
    }

    @Test
    @DisplayName("a retitle goes out once the server's window has been seen")
    void sendsToTheSeenWindow() {
        assertEquals(TitlePackets.Decision.SEND, TitlePackets.decide(7, 7));
    }

    @Test
    @DisplayName("a retitle is dropped when the server's window cannot be told")
    void dropsWithoutAKnownWindow() {
        assertEquals(TitlePackets.Decision.DROP, TitlePackets.decide(-1, 7));
        assertEquals(TitlePackets.Decision.DROP, TitlePackets.decide(0, 0));
    }
}
