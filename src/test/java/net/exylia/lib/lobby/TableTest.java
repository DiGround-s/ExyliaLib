package net.exylia.lib.lobby;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableTest {

    private static final Seat HOST = new Seat(UUID.randomUUID(), "Host");
    private static final Seat ANN = new Seat(UUID.randomUUID(), "Ann");
    private static final Seat BOB = new Seat(UUID.randomUUID(), "Bob");
    private static final Seat CY = new Seat(UUID.randomUUID(), "Cy");

    private static TableRules rules(int min, int max) {
        return new TableRules("test", "game", min, max, Duration.ofMinutes(1), Duration.ofSeconds(30));
    }

    /** A table whose host was admitted and that is waiting for players. */
    private static Table<String> open(int min, int max, UUID invitee) {
        Table<String> table = new Table<>("chess", rules(min, max), HOST, invitee, 0);
        assertTrue(table.admitted(HOST.player()));
        assertTrue(table.advance(TableState.CREATING, TableState.OPEN));
        return table;
    }

    @Test
    @DisplayName("rules need at least two seats, min under max, and positive expiries; the command gets its slash")
    void rules() {
        assertThrows(IllegalArgumentException.class, () -> rules(1, 2));
        assertThrows(IllegalArgumentException.class, () -> rules(3, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new TableRules("t", "/g", 2, 2, Duration.ZERO, Duration.ofSeconds(1)));
        assertEquals("/game", rules(2, 2).command());
        assertFalse(rules(2, 2).startsEarly());
        assertTrue(rules(2, 4).startsEarly());
    }

    @Test
    @DisplayName("a seat is taken only while open, once per player; the last one moves the table to starting")
    void reserve() {
        Table<String> table = open(2, 3, null);
        assertFalse(table.reserve(HOST), "the host already sits there");
        assertTrue(table.reserve(ANN));
        assertFalse(table.reserve(ANN));
        assertEquals(TableState.OPEN, table.state());
        assertTrue(table.reserve(BOB));
        assertEquals(TableState.STARTING, table.state());
        assertTrue(table.isFull());
        assertFalse(table.reserve(CY), "full");
    }

    @Test
    @DisplayName("an invitation seats only its invitee, then opens to everyone")
    void invitation() {
        Table<String> table = open(2, 3, ANN.player());
        assertFalse(table.isPublic());
        assertFalse(table.reserve(BOB));
        assertTrue(table.reserve(ANN));
        assertTrue(table.isPublic());
        assertTrue(table.reserve(BOB));
    }

    @Test
    @DisplayName("a table starts only once every seat is admitted, and only once")
    void startWaitsForAdmissions() {
        Table<String> table = open(2, 3, null);
        table.reserve(ANN);
        table.reserve(BOB);
        assertFalse(table.beginStart(), "two admissions still running");
        assertTrue(table.admitted(BOB.player()));
        assertFalse(table.beginStart(), "Ann's still runs");
        assertTrue(table.admitted(ANN.player()));
        assertTrue(table.beginStart());
        assertFalse(table.beginStart(), "answered once");
        assertNull(table.release(ANN.player()), "a seat is not given back once the table is handed over");
    }

    @Test
    @DisplayName("a failed admission gives the seat back and reopens a full table")
    void releaseReopens() {
        Table<String> table = open(2, 2, null);
        table.reserve(ANN);
        assertEquals(TableState.STARTING, table.state());
        Table.Released released = table.release(ANN.player());
        assertEquals(ANN, released.seat());
        assertFalse(released.admitted(), "its admission had not gone through");
        assertEquals(TableState.OPEN, table.state());
        assertFalse(table.admitted(ANN.player()), "the late answer finds the seat gone");
        assertNull(table.release(HOST.player()), "the host's seat is never given back");
    }

    @Test
    @DisplayName("the host starts early only with the fewest seated and nobody being admitted")
    void startEarly() {
        Table<String> table = open(3, 4, null);
        table.reserve(ANN);
        table.admitted(ANN.player());
        assertFalse(table.startEarly(), "two of three");
        table.reserve(BOB);
        assertFalse(table.startEarly(), "Bob is still being admitted");
        table.admitted(BOB.player());
        assertTrue(table.startEarly());
        assertEquals(TableState.STARTING, table.state());
        assertTrue(table.beginStart());
    }

    @Test
    @DisplayName("a player leaving a table being played keeps their seat number, marked as left")
    void drop() {
        Table<String> table = open(2, 3, null);
        table.reserve(ANN);
        table.reserve(BOB);
        table.admitted(ANN.player());
        table.admitted(BOB.player());
        assertEquals(-1, table.drop(ANN.player()), "not before it is played");
        table.beginStart();
        assertTrue(table.advance(TableState.STARTING, TableState.PLAYING));
        assertTrue(table.startedAt() > 0);
        assertEquals(1, table.drop(ANN.player()));
        assertEquals(-1, table.drop(ANN.player()), "once");
        assertTrue(table.hasLeft(1));
        assertFalse(table.hasLeft(2));
        assertEquals(3, table.seats().size());
        assertEquals(java.util.List.of(HOST, BOB), table.present());
        assertEquals(1, table.seatOf(ANN.player()));
    }

    @Test
    @DisplayName("a table ends once, from any state, and a late admission finds it ended")
    void endsOnce() {
        Table<String> table = open(2, 2, null);
        table.reserve(ANN);
        table.expireAt(5_000);
        assertEquals(TableState.STARTING, table.end());
        assertNull(table.end());
        assertEquals(0, table.expiresAt(), "an ended table no longer expires");
        assertFalse(table.advance(TableState.STARTING, TableState.PLAYING));
        assertFalse(table.admitted(ANN.player()));
    }
}
