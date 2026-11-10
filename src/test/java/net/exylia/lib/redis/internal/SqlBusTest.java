package net.exylia.lib.redis.internal;

import net.exylia.lib.database.internal.SqlSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The database bus against a real H2: two servers are two buses on one
 * database, exactly as two backends share one MySQL.
 */
class SqlBusTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();

    private String name;
    private final List<SqlBus> open = new ArrayList<>();

    @BeforeEach
    void name() throws Exception {
        name = "bus" + DATABASE.incrementAndGet();
        reset();
    }

    /** Where the two servers meet; overridden to run the same cases on a real server. */
    SqlSettings settings() {
        return SqlSettings.memory("h2", name);
    }

    /** A raw connection to the same database, for rows a test writes by hand. */
    Connection raw() throws Exception {
        return DriverManager.getConnection("jdbc:h2:mem:" + name, "sa", "");
    }

    /** Leaves no tables from an earlier case; a fresh H2 has none. */
    void reset() throws Exception {
    }

    @AfterEach
    void close() {
        open.forEach(SqlBus::close);
    }

    private SqlBus server() {
        SqlBus bus = SqlBus.connect(settings(), "test");
        open.add(bus);
        return bus;
    }

    @Test
    @DisplayName("\"a message published on one server reaches a subscriber on another\"")
    void crossesServers() throws Exception {
        SqlBus lobby = server();
        SqlBus arena = server();
        arena.start();
        CountDownLatch arrived = new CountDownLatch(1);
        List<String> heard = new CopyOnWriteArrayList<>();
        arena.subscribe("exylia:staff:chat", message -> {
            heard.add(message);
            arrived.countDown();
        });
        lobby.publish("exylia:staff:other", "not for you");
        lobby.publish("exylia:staff:chat", "hello");
        assertTrue(arrived.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("hello"), heard);
    }

    @Test
    @DisplayName("\"a number that commits after a higher one is still delivered, once\"")
    void lateCommitDelivered() throws Exception {
        SqlBus bus = server();
        bus.prime();
        List<String> heard = new ArrayList<>();
        bus.subscribe("c", heard::add);
        long now = System.currentTimeMillis();
        insert(5, "c", "five");
        bus.poll(now);
        insert(3, "c", "three");   // handed out before 5, committed after it
        bus.poll(now + 250);
        bus.poll(now + 500);
        assertEquals(List.of("five", "three"), heard);
    }

    @Test
    @DisplayName("\"a backlog longer than one page is read in full\"")
    void pagesBacklog() throws Exception {
        SqlBus bus = server();
        bus.prime();
        AtomicInteger heard = new AtomicInteger();
        bus.subscribe("c", ignored -> heard.incrementAndGet());
        for (int i = 0; i < DatabaseBus.PAGE + 20; i++) {
            bus.publish("c", "m" + i);
        }
        bus.poll(System.currentTimeMillis());
        assertEquals(DatabaseBus.PAGE + 20, heard.get());
    }

    @Test
    @DisplayName("\"keys expire, locks are exclusive until they expire, prefixes are literal\"")
    void keys() throws Exception {
        SqlBus bus = server();
        bus.set("a:1", "one", 60);
        assertEquals("one", bus.get("a:1"));
        bus.set("a:1", "uno", 60);
        assertEquals("uno", bus.get("a:1"));
        bus.setUntil("a:2", "gone", System.currentTimeMillis() - 1);
        assertNull(bus.get("a:2"));
        bus.setUntil("a:3", "forever", 0);
        assertEquals("forever", bus.get("a:3"));

        assertTrue(bus.setIfAbsent("lock", "lobby", 60_000));
        assertFalse(bus.setIfAbsent("lock", "arena", 60_000));
        bus.setUntil("old-lock", "lobby", System.currentTimeMillis() - 1);
        assertTrue(bus.setIfAbsent("old-lock", "arena", 60_000), "an expired holder is gone");

        bus.set("p_x:1", "kept", 60);
        bus.set("p%x:1", "kept", 60);
        bus.set("pqx:1", "kept", 60);
        bus.deleteByPrefix("p_x:");
        assertNull(bus.get("p_x:1"));
        assertEquals("kept", bus.get("p%x:1"));
        assertEquals("kept", bus.get("pqx:1"), "_ is a character, not a wildcard");

        bus.delete(List.of("a:1", "a:3"));
        assertNull(bus.get("a:1"));
        assertNull(bus.get("a:3"));
    }

    @Test
    @DisplayName("\"a consumer group hands each entry out once, keeps it pending until acked, lets it be claimed\"")
    void streams() {
        SqlBus lobby = server();
        SqlBus arena = server();
        String stream = "exylia:punish:external";
        assertThrows(IllegalStateException.class, () -> lobby.streamRead(stream, "staff", "lobby", 10));
        lobby.streamGroup(stream, "staff");
        arena.streamGroup(stream, "staff");   // already there: left alone
        for (int i = 0; i < 5; i++) {
            arena.streamAdd(stream, Map.of("json", "{\"n\":" + i + "}"), 1000);
        }
        List<Map.Entry<String, Map<String, String>>> first = lobby.streamRead(stream, "staff", "lobby", 3);
        List<Map.Entry<String, Map<String, String>>> second = arena.streamRead(stream, "staff", "arena", 10);
        assertEquals(3, first.size());
        assertEquals(2, second.size());
        Set<String> ids = new HashSet<>();
        first.forEach(entry -> ids.add(entry.getKey()));
        second.forEach(entry -> ids.add(entry.getKey()));
        assertEquals(5, ids.size(), "no entry went to both servers");
        assertEquals("{\"n\":0}", first.get(0).getValue().get("json"));
        assertTrue(lobby.streamRead(stream, "staff", "lobby", 10).isEmpty());

        String lost = first.get(0).getKey();
        lobby.streamAck(stream, "staff", first.subList(1, 3).stream().map(Map.Entry::getKey).toList());
        arena.streamAck(stream, "staff", second.stream().map(Map.Entry::getKey).toList());
        List<Map.Entry<String, Long>> pending = arena.streamPending(stream, "staff", 0, 10);
        assertEquals(1, pending.size());
        assertEquals(lost, pending.get(0).getKey());
        assertEquals(1L, pending.get(0).getValue());

        assertTrue(arena.streamClaim(stream, "staff", "arena", 60_000, List.of(lost)).isEmpty(),
                "not idle long enough");
        List<Map.Entry<String, Map<String, String>>> claimed = arena.streamClaim(stream, "staff", "arena", 0,
                List.of(lost));
        assertEquals(1, claimed.size());
        assertEquals(2L, arena.streamPending(stream, "staff", 0, 10).get(0).getValue());
        arena.streamAck(stream, "staff", List.of(lost));
        assertTrue(arena.streamPending(stream, "staff", 0, 10).isEmpty());
    }

    @Test
    @DisplayName("\"a stream is trimmed to about its maximum length\"")
    void trims() throws Exception {
        SqlBus bus = server();
        String stream = "s";
        bus.streamGroup(stream, "g");
        for (int i = 0; i < 10; i++) {
            bus.streamAdd(stream, Map.of("i", Integer.toString(i)), 4);
        }
        List<Map.Entry<String, Map<String, String>>> left = bus.streamRead(stream, "g", "c", 100);
        assertEquals(4, left.size());
        assertEquals("6", left.get(0).getValue().get("i"));
    }

    private void insert(long id, String channel, String payload) throws Exception {
        try (Connection connection = raw(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO " + SqlBus.MESSAGES + " (id, channel, payload, created) VALUES ("
                    + id + ", '" + channel + "', '" + payload + "', " + System.currentTimeMillis() + ")");
        }
    }
}
