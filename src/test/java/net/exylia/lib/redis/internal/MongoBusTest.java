package net.exylia.lib.redis.internal;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import net.exylia.lib.database.internal.SqlSettings;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The database bus on a real MongoDB. Opt-in:
 * {@code EXYLIA_BUS_MONGO=host:port/database}.
 */
@EnabledIfEnvironmentVariable(named = "EXYLIA_BUS_MONGO", matches = ".+")
class MongoBusTest {

    private static final String TARGET = System.getenv("EXYLIA_BUS_MONGO");

    private final List<MongoBus> open = new ArrayList<>();
    private MongoClient raw;
    private MongoDatabase database;

    @BeforeEach
    void reset() {
        raw = MongoClients.create("mongodb://" + TARGET.substring(0, TARGET.indexOf('/')));
        database = raw.getDatabase(TARGET.substring(TARGET.indexOf('/') + 1));
        database.drop();
    }

    @AfterEach
    void close() {
        open.forEach(MongoBus::close);
        raw.close();
    }

    private MongoBus server() {
        String host = TARGET.substring(0, TARGET.indexOf(':'));
        int port = Integer.parseInt(TARGET.substring(TARGET.indexOf(':') + 1, TARGET.indexOf('/')));
        MongoBus bus = MongoBus.connect(SqlSettings.remote("mongodb", host, port,
                TARGET.substring(TARGET.indexOf('/') + 1), "", ""), "test");
        open.add(bus);
        return bus;
    }

    @Test
    @DisplayName("\"a message published on one server reaches a subscriber on another\"")
    void crossesServers() throws Exception {
        MongoBus lobby = server();
        MongoBus arena = server();
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
    @DisplayName("\"a number stored after a higher one is still delivered, once\"")
    void lateCommitDelivered() throws Exception {
        MongoBus bus = server();
        bus.prime();
        List<String> heard = new ArrayList<>();
        bus.subscribe("c", heard::add);
        long now = System.currentTimeMillis();
        database.getCollection(SqlBus.MESSAGES).insertOne(new Document("_id", 5L).append("channel", "c")
                .append("payload", "five").append("created", now));
        bus.poll(now);
        database.getCollection(SqlBus.MESSAGES).insertOne(new Document("_id", 3L).append("channel", "c")
                .append("payload", "three").append("created", now));
        bus.poll(now + 250);
        bus.poll(now + 500);
        assertEquals(List.of("five", "three"), heard);
    }

    @Test
    @DisplayName("\"keys expire, locks are exclusive until they expire, prefixes are literal\"")
    void keys() {
        MongoBus bus = server();
        bus.set("a:1", "one", 60);
        bus.set("a:1", "uno", 60);
        assertEquals("uno", bus.get("a:1"));
        bus.setUntil("a:2", "gone", System.currentTimeMillis() - 1);
        assertNull(bus.get("a:2"));
        assertTrue(bus.setIfAbsent("lock", "lobby", 60_000));
        assertFalse(bus.setIfAbsent("lock", "arena", 60_000));
        bus.setUntil("old-lock", "lobby", System.currentTimeMillis() - 1);
        assertTrue(bus.setIfAbsent("old-lock", "arena", 60_000));
        bus.set("p.x:1", "gone", 60);
        bus.set("pqx:1", "kept", 60);
        bus.deleteByPrefix("p.x:");
        assertNull(bus.get("p.x:1"));
        assertEquals("kept", bus.get("pqx:1"), ". is a character, not a wildcard");
        bus.delete(List.of("a:1"));
        assertNull(bus.get("a:1"));
    }

    @Test
    @DisplayName("\"a consumer group hands each entry out once, after it settles, and claims work\"")
    void streams() throws Exception {
        MongoBus lobby = server();
        MongoBus arena = server();
        String stream = "exylia:punish:external";
        assertThrows(IllegalStateException.class, () -> lobby.streamRead(stream, "staff", "lobby", 10));
        lobby.streamGroup(stream, "staff");
        arena.streamGroup(stream, "staff");
        for (int i = 0; i < 5; i++) {
            arena.streamAdd(stream, Map.of("json", "{\"n\":" + i + "}"), 1000);
        }
        assertTrue(lobby.streamRead(stream, "staff", "lobby", 3).isEmpty(), "not settled yet");
        assertTrue(arena.streamRead(stream, "staff", "arena", 3).isEmpty(), "not settled yet");
        Thread.sleep(DatabaseBus.LOOKBACK_MILLIS + 200);
        List<Map.Entry<String, Map<String, String>>> first = lobby.streamRead(stream, "staff", "lobby", 3);
        List<Map.Entry<String, Map<String, String>>> second = arena.streamRead(stream, "staff", "arena", 10);
        assertEquals(3, first.size());
        assertEquals(2, second.size());
        Set<String> ids = new HashSet<>();
        first.forEach(entry -> ids.add(entry.getKey()));
        second.forEach(entry -> ids.add(entry.getKey()));
        assertEquals(5, ids.size());
        assertEquals("{\"n\":0}", first.get(0).getValue().get("json"));

        String lost = first.get(0).getKey();
        lobby.streamAck(stream, "staff", first.subList(1, 3).stream().map(Map.Entry::getKey).toList());
        arena.streamAck(stream, "staff", second.stream().map(Map.Entry::getKey).toList());
        List<Map.Entry<String, Long>> pending = arena.streamPending(stream, "staff", 0, 10);
        assertEquals(1, pending.size());
        assertEquals(lost, pending.get(0).getKey());
        assertEquals(1, arena.streamClaim(stream, "staff", "arena", 0, List.of(lost)).size());
        assertEquals(2L, arena.streamPending(stream, "staff", 0, 10).get(0).getValue());
        arena.streamAck(stream, "staff", List.of(lost));
        assertTrue(arena.streamPending(stream, "staff", 0, 10).isEmpty());
    }
}
