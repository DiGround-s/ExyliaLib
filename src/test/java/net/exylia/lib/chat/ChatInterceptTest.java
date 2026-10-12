package net.exylia.lib.chat;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A chat event fired twice for one line is taken once. */
class ChatInterceptTest {

    @Test
    void aRefiredLineIsCancelledWithoutAskingAgain() {
        FakeServer.install();
        FakeServer.reset();
        Player alice = new FakePlayer("Alice").player();
        AtomicInteger asked = new AtomicInteger();
        ChatIntercept intercept = ChatIntercept.register(FakeServer.newPlugin("ExyliaStaff"), EventPriority.LOWEST,
                true, (player, message) -> {
                    asked.incrementAndGet();
                    return !message.equals("public");
                });

        assertTrue(intercept.take(alice, "a", 1_000));
        assertTrue(intercept.take(alice, "a", 1_100));
        assertEquals(1, asked.get());

        assertTrue(intercept.take(alice, "a", 1_000 + ChatIntercept.REPEAT_WINDOW_MILLIS + 1));
        assertEquals(2, asked.get());

        assertFalse(intercept.take(alice, "public", 5_000));
        assertFalse(intercept.take(alice, "public", 5_001));
        assertEquals(4, asked.get());
    }
}
