package net.exylia.lib.screen.internal;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatHoldTest {

    private static Component line(String text) {
        return Component.text(text);
    }

    @Test
    void nothingRecentMeansNothingIsPushedOut() {
        ChatHold hold = new ChatHold();
        hold.shown(line("old"), 0);
        assertFalse(hold.begin(ChatHold.RECENT_MILLIS), "faded lines stay in the client's history");
        assertEquals(List.of(), hold.end());
    }

    @Test
    void recentLinesComeBackBeforeHeldOnes() {
        ChatHold hold = new ChatHold();
        hold.shown(line("old"), 0);
        hold.shown(line("recent"), 5_000);
        assertTrue(hold.begin(10_000));
        assertTrue(hold.holding());
        hold.hold(line("while open"));
        assertEquals(List.of(line("recent"), line("while open")), hold.end());
        assertFalse(hold.holding());
        assertEquals(List.of(), hold.end(), "delivered once");
    }

    @Test
    void reopeningDoesNotPushOutAgainOrLoseWhatIsHeld() {
        ChatHold hold = new ChatHold();
        hold.shown(line("recent"), 0);
        assertTrue(hold.begin(1));
        hold.hold(line("held"));
        assertFalse(hold.begin(2), "already holding");
        assertEquals(List.of(line("recent"), line("held")), hold.end());
    }

    @Test
    void theHeldBufferKeepsTheNewest() {
        ChatHold hold = new ChatHold();
        hold.begin(0);
        for (int i = 0; i < 250; i++) hold.hold(line("m" + i));
        List<Component> out = hold.end();
        assertEquals(200, out.size());
        assertEquals(line("m50"), out.get(0));
        assertEquals(line("m249"), out.get(199));
    }
}
