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
    void heldLinesComeBackOnceInOrder() {
        ChatHold hold = new ChatHold();
        assertTrue(hold.begin());
        assertTrue(hold.holding());
        hold.hold(line("a"));
        hold.hold(line("b"));
        assertEquals(List.of(line("a"), line("b")), hold.end());
        assertFalse(hold.holding());
        assertEquals(List.of(), hold.end(), "delivered once");
    }

    @Test
    void reopeningDoesNotLoseWhatIsHeld() {
        ChatHold hold = new ChatHold();
        assertTrue(hold.begin());
        hold.hold(line("held"));
        assertFalse(hold.begin(), "already holding");
        assertEquals(List.of(line("held")), hold.end());
    }

    @Test
    void theHeldBufferKeepsTheNewest() {
        ChatHold hold = new ChatHold();
        hold.begin();
        for (int i = 0; i < 250; i++) hold.hold(line("m" + i));
        List<Component> out = hold.end();
        assertEquals(200, out.size());
        assertEquals(line("m50"), out.get(0));
        assertEquals(line("m249"), out.get(199));
    }

    @Test
    void notHoldingKeepsNothing() {
        ChatHold hold = new ChatHold();
        assertFalse(hold.hold(line("late")), "the caller shows it");
        assertEquals(List.of(), hold.end());
    }
}
