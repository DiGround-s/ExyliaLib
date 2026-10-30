package net.exylia.lib.replay.internal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That an answer is matched to the tick it was asked on, and an attack to the
 * look it was aimed with.
 */
class ClientViewTest {

    @AfterEach
    void clear() {
        ClientView.closeAll();
    }

    @Test
    @DisplayName("a pong outside the range, or for a ping never sent, changes nothing")
    void foreignPongsAreIgnored() throws Exception {
        ClientView view = view();
        view.combat(100);
        view.pong(-5);                                  // an anticheat's short id
        view.pong(ClientView.PING_BASE | 7);            // ours in shape, never sent
        assertEquals(ClientView.UNANSWERED, view.acked);
    }

    @Test
    @DisplayName("an attack takes the look sent right after it, in the same tick")
    void theLookAfterTheAttack() throws Exception {
        ClientView view = view();
        view.look(100, 0f, 0f);
        view.attack(101, 42);
        assertTrue(view.waiting());
        view.look(101, 30f, -5f);
        assertFalse(view.waiting());
        ClientView.Sent sent = ClientView.SENT.poll();
        assertEquals(42, sent.target);
        assertEquals(30f, sent.yaw, 0f);
        assertEquals(-5f, sent.pitch, 0f);
        assertTrue(sent.sent);
        assertEquals(Math.sqrt(30 * 30 + 5 * 5), sent.flick, 1e-3, "the snap onto them");
    }

    @Test
    @DisplayName("a look arriving ticks later is the next tick's: the one before stands")
    void aLateLookIsNotTheAttacks() throws Exception {
        ClientView view = view();
        view.look(100, 10f, 0f);
        view.attack(101, 42);
        view.look(105, 90f, 0f);
        ClientView.Sent sent = ClientView.SENT.poll();
        assertEquals(10f, sent.yaw, 0f);
    }

    @Test
    @DisplayName("an attack with no look at all still gets through, without one")
    void noLookAtAll() throws Exception {
        ClientView view = view();
        view.attack(101, 42);
        view.settleStale(102);
        assertTrue(view.waiting(), "not yet stale");
        view.settleStale(104);
        ClientView.Sent sent = ClientView.SENT.poll();
        assertFalse(sent.sent);
        assertTrue(Float.isNaN(sent.yaw));
        assertNull(ClientView.SENT.poll());
    }

    @Test
    @DisplayName("a new stretch of combat forgets the answer from the last one")
    void combatResets() throws Exception {
        ClientView view = view();
        view.combat(100);
        view.acked = 99;
        view.combat(200);                               // still in combat: kept
        assertEquals(99, view.acked);
        view.combat(200 + ClientView.COMBAT_TICKS + 1); // out and back in
        assertEquals(ClientView.UNANSWERED, view.acked);
    }

    private static ClientView view() throws Exception {
        Constructor<ClientView> make = ClientView.class.getDeclaredConstructor(UUID.class, int.class, Object.class);
        make.setAccessible(true);
        return make.newInstance(UUID.randomUUID(), 1, null);
    }
}
