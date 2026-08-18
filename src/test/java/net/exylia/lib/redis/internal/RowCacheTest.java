package net.exylia.lib.redis.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Table;
import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.redis.RedisSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cache's own contracts, at the level where they are decided.
 *
 * <p>{@link CrossServerTest} proves the module works through two repositories;
 * this proves the individual promises that make it work, including the ones a
 * passing end-to-end test cannot distinguish — an ordering that only matters
 * during the instant a peer is reacting, and a failure mode that only matters
 * when Redis is broken in one specific way.
 */
class RowCacheTest {

    @Table("effects")
    record Effect(@Id UUID uuid, @Column("kill_effect") String killEffect, @Column int level) {
    }

    private static final EntityModel<Effect> MODEL = EntityModel.of(Effect.class);

    private static RedisSettings settings(String serverId) {
        return new RedisSettings(true, "localhost", 6379, "", 0, 8,
                1800, 300, 10_000, "exylia", serverId);
    }

    private static RowCache cacheOn(MemoryClient client, String serverId, List<String> warnings) {
        return new RowCache(client, settings(serverId), serverId, warnings::add);
    }

    // ----------------------------------------------------------- the ordering

    @Test
    @DisplayName("\"a peer reacting to the message finds the new value, not the old one\"")
    void theValueIsStoredBeforeItIsAnnounced() {
        // The one rule the cross-server guarantee rests on, and the one an
        // end-to-end test cannot see: by the time anything observes the
        // message, the write has already completed either way. So this watches
        // the channel and reads Redis at the instant the message lands, which
        // is exactly what a peer's subscriber does.
        MemoryClient.Network network = MemoryClient.network();
        MemoryClient writer = new MemoryClient(network);
        MemoryClient watcher = new MemoryClient(network);

        UUID player = UUID.randomUUID();
        RowCache cache = cacheOn(writer, "lobby-1", new ArrayList<>());
        cache.put(MODEL, player, new Effect(player, "flame", 1));

        String key = "exylia:row:effects:" + player;
        // Every notification, not the last: a publish that fires before the
        // store and again after it would leave a single observation looking
        // correct while peers were still told too early.
        List<String> seenWhenNotified = new ArrayList<>();
        watcher.subscribe("exylia:invalidate", message -> seenWhenNotified.add(watcher.get(key)));

        cache.put(MODEL, player, new Effect(player, "flame", 9));

        assertEquals(1, seenWhenNotified.size(),
                "one write is one announcement: " + seenWhenNotified);
        String seen = seenWhenNotified.get(0);
        assertNotNull(seen, "the peer should have been told at all");
        assertTrue(seen.contains("\"level\":9"),
                "a peer woken by the message and reading immediately must find the new value,"
                        + " which only holds if the store happened before the publish; it saw: " + seen);
    }

    @Test
    @DisplayName("\"a write that could not be stored is not announced\"")
    void aFailedStoreDoesNotPublish() {
        // Announcing it would send every peer to drop its copy and re-read a
        // value that was never written, turning one failed write into a
        // network-wide fallback to the database for the whole TTL.
        MemoryClient.Network network = MemoryClient.network();
        MemoryClient client = new MemoryClient(network);
        RowCache cache = cacheOn(client, "lobby-1", new ArrayList<>());

        // Only the store fails. A client where publishing fails too could not
        // tell "did not announce it" from "could not announce it", which is the
        // whole point of the assertion.
        client.failingWrites(true);
        int before = network.published();
        UUID player = UUID.randomUUID();
        cache.put(MODEL, player, new Effect(player, "flame", 1));

        assertEquals(before, network.published(),
                "a store that failed must not tell anyone to go looking for it");
    }

    // -------------------------------------------------------- the self-filter

    @Test
    @DisplayName("\"a server ignores its own invalidations\"")
    void aServerIgnoresItself() {
        // Every server sees every message, its own included. Acting on its own
        // would drop the copy it just wrote, and the next read would pay a
        // round trip to fetch back what it already had.
        MemoryClient.Network network = MemoryClient.network();
        MemoryClient client = new MemoryClient(network);
        RowCache cache = cacheOn(client, "lobby-1", new ArrayList<>());

        UUID player = UUID.randomUUID();
        cache.put(MODEL, player, new Effect(player, "flame", 4));

        // Still in memory: proven by breaking Redis, so a hit can only be local.
        client.failing(true);
        Effect held = cache.get(MODEL, player);
        assertEquals(4, held == null ? -1 : held.level(),
                "the writer should still hold its own row in memory");
    }

    @Test
    @DisplayName("\"a message from another server drops the local copy only\"")
    void aPeerDropsMemoryButNotRedis() {
        // Deleting the Redis value on the way would throw away the fresh row
        // the sender just wrote and send the whole network to the database.
        MemoryClient.Network network = MemoryClient.network();
        MemoryClient writer = new MemoryClient(network);
        MemoryClient peer = new MemoryClient(network);

        UUID player = UUID.randomUUID();
        RowCache writerCache = cacheOn(writer, "lobby-1", new ArrayList<>());
        RowCache peerCache = cacheOn(peer, "arena-1", new ArrayList<>());

        writerCache.put(MODEL, player, new Effect(player, "flame", 1));
        peerCache.register(MODEL);
        assertEquals(1, peerCache.get(MODEL, player).level());

        writerCache.put(MODEL, player, new Effect(player, "flame", 9));

        assertEquals(9, peerCache.get(MODEL, player).level(),
                "the peer must read the sender's new value rather than its own stale one");
    }

    @Test
    @DisplayName("\"a table nothing here reads costs nothing to be told about\"")
    void anUnknownTableIsIgnored() {
        MemoryClient.Network network = MemoryClient.network();
        MemoryClient peer = new MemoryClient(network);
        List<String> warnings = new ArrayList<>();
        cacheOn(peer, "arena-1", warnings);

        peer.publish("exylia:invalidate", "lobby-1|some_other_plugins_table|abc");

        assertTrue(warnings.isEmpty(), "another plugin's table is not a problem to report");
    }

    // ------------------------------------------------------------- resilience

    @Test
    @DisplayName("\"an unreachable Redis is a miss, reported once\"")
    void anUnreachableRedisIsReportedOnce() {
        MemoryClient client = new MemoryClient();
        List<String> warnings = new ArrayList<>();
        RowCache cache = cacheOn(client, "lobby-1", warnings);

        client.failing(true);
        UUID player = UUID.randomUUID();
        for (int attempt = 0; attempt < 50; attempt++) {
            assertNull(cache.get(MODEL, UUID.randomUUID()), "a broken Redis answers nothing");
        }
        cache.put(MODEL, player, new Effect(player, "flame", 1));

        assertEquals(1, warnings.size(),
                "a Redis that is down should produce one line, not one per lookup");
    }

    @Test
    @DisplayName("\"an unreadable payload is a miss, not a poisoned key\"")
    void anUnreadablePayloadIsDropped() {
        MemoryClient client = new MemoryClient();
        RowCache cache = cacheOn(client, "lobby-1", new ArrayList<>());

        UUID player = UUID.randomUUID();
        client.set("exylia:row:effects:" + player, "{not json at all", 1800);

        assertNull(cache.get(MODEL, player), "a payload that cannot be read is a miss");
        assertNull(client.get("exylia:row:effects:" + player),
                "and it is removed, so the next lookup does not pay for the same failed parse");
    }

    @Test
    @DisplayName("\"an expired row is gone\"")
    void expiryIsHonoured() {
        MemoryClient.Network network = MemoryClient.network();
        MemoryClient client = new MemoryClient(network);
        RowCache cache = cacheOn(client, "lobby-1", new ArrayList<>());

        UUID player = UUID.randomUUID();
        cache.put(MODEL, player, new Effect(player, "flame", 1));

        network.advanceSeconds(2_000);
        assertNull(client.get("exylia:row:effects:" + player),
                "a cache entry must not outlive the process that could invalidate it");
    }

    // -------------------------------------------------------------- the keys

    @Test
    @DisplayName("\"a row is keyed by its table and its stored id\"")
    void theKeyFormatIsStable() {
        // Two promises in one: the table name keeps two plugins' PlayerData
        // apart in a shared keyspace, and the stored id is what a write and a
        // read must agree on or the cache silently never hits.
        MemoryClient client = new MemoryClient();
        RowCache cache = cacheOn(client, "lobby-1", new ArrayList<>());

        UUID player = UUID.randomUUID();
        cache.put(MODEL, player, new Effect(player, "flame", 1));

        assertNotNull(client.get("exylia:row:effects:" + player),
                "the key should be prefix:row:<table>:<stored id>");
    }

    @Test
    @DisplayName("\"a payload written before a column existed still reads\"")
    void anOlderPayloadStillReads() {
        // A plugin update adds a column; every cached row predates it. Failing
        // here would poison every lookup of that type until the TTL expired.
        MemoryClient client = new MemoryClient();
        RowCache cache = cacheOn(client, "lobby-1", new ArrayList<>());

        UUID player = UUID.randomUUID();
        client.set("exylia:row:effects:" + player,
                "{\"v\":1,\"c\":{\"uuid\":\"" + player + "\",\"kill_effect\":\"flame\"}}", 1800);

        Effect read = cache.get(MODEL, player);
        assertNotNull(read, "a payload missing a newer column should still read");
        assertEquals("flame", read.killEffect());
        assertEquals(0, read.level(), "the missing column comes back as its absent value");
    }

    @Test
    @DisplayName("\"a payload from a future format is ignored rather than guessed at\"")
    void anUnknownVersionIsAMiss() {
        MemoryClient client = new MemoryClient();
        RowCache cache = cacheOn(client, "lobby-1", new ArrayList<>());

        UUID player = UUID.randomUUID();
        client.set("exylia:row:effects:" + player, "{\"v\":99,\"c\":{}}", 1800);

        assertNull(cache.get(MODEL, player));
    }
}
