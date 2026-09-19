package net.exylia.lib.player;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.TestDatabases;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two accounts from one address, offline and across servers, without ever
 * storing the address.
 */
class AccountsTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();
    private static final long DAY = Duration.ofDays(1).toMillis();

    private Plugin plugin;
    private Accounts accounts;
    private final AtomicLong now = new AtomicLong(1_000L * DAY);
    private final UUID main = UUID.randomUUID();
    private final UUID alt = UUID.randomUUID();

    @BeforeAll
    static void install() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        plugin = FakeServer.newPlugin("Reputation");
        TestDatabases.memory(plugin, "accounts" + DATABASE.incrementAndGet());
        accounts = Accounts.of(plugin);
        accounts.clockForTests(now::get);
    }

    @AfterEach
    void close() {
        Accounts.releaseAll();
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void join(UUID player, String ip) throws Exception {
        await(accounts.record(player, InetAddress.getByName(ip), 0L));
    }

    @Test
    @DisplayName("two accounts that joined from one address are the same person, offline")
    void sharedAddress() throws Exception {
        join(main, "203.0.113.7");
        join(alt, "203.0.113.7");
        UUID stranger = UUID.randomUUID();
        join(stranger, "198.51.100.1");

        assertTrue(await(accounts.sameAddress(main, alt)));
        assertTrue(await(accounts.sameAddress(alt, main)));
        assertFalse(await(accounts.sameAddress(main, stranger)));
        assertTrue(await(accounts.sameAddress(main, main)));
    }

    @Test
    @DisplayName("an address only counts while both used it within the window")
    void window() throws Exception {
        join(main, "203.0.113.7");
        now.addAndGet(40 * DAY);
        join(alt, "203.0.113.7");

        assertFalse(await(accounts.sameAddress(main, alt)));
        assertTrue(await(accounts.window(Duration.ofDays(60)).sameAddress(main, alt)));
    }

    @Test
    @DisplayName("the address is stored as a keyed hash, and one IPv6 household is one address")
    void hashed() throws Exception {
        join(main, "2001:db8:1:2:aaaa::1");
        join(alt, "2001:db8:1:2:bbbb::9");

        String stored = await(accounts.addressesForTests(main)).get(0).address();
        assertFalse(stored.contains("2001"));
        assertEquals(32, stored.length());
        assertTrue(await(accounts.sameAddress(main, alt)));
        byte[] key = new byte[32];
        assertNotEquals(Accounts.hash(key, InetAddress.getByName("203.0.113.7")),
                Accounts.hash(key, InetAddress.getByName("203.0.113.8")));
    }

    @Test
    @DisplayName("two servers on one database hash with the same key")
    void oneKeyPerNetwork() throws Exception {
        join(main, "203.0.113.7");
        Plugin other = FakeServer.newPlugin("Bounties");
        // The test database answers every plugin: one database, the way two
        // servers' plugins point at one MySQL.
        Accounts elsewhere = Accounts.of(other);
        elsewhere.clockForTests(now::get);
        await(elsewhere.record(alt, InetAddress.getByName("203.0.113.7"), 0L));

        assertTrue(await(elsewhere.sameAddress(main, alt)));
    }

    @Test
    @DisplayName("two players online here from one address match before anything is written")
    void onlineHere() {
        FakePlayer first = new FakePlayer("first").from("203.0.113.7");
        FakePlayer second = new FakePlayer("second").from("203.0.113.7");
        FakeServer.online(first.player(), second.player());

        assertTrue(await(accounts.sameAddress(first.player().getUniqueId(), second.player().getUniqueId())));
    }

    @Test
    @DisplayName("first seen is the earliest sighting on any server, including what the server already knew")
    void firstSeen() throws Exception {
        assertEquals(Optional.empty(), await(accounts.firstSeen(main)));

        join(main, "203.0.113.7");
        long first = now.get();
        now.addAndGet(DAY);
        join(main, "203.0.113.7");
        assertEquals(Optional.of(Instant.ofEpochMilli(first)), await(accounts.firstSeen(main)));

        // Another server remembers them from before this was installed.
        await(accounts.record(main, null, first - 500 * DAY));
        assertEquals(Optional.of(Instant.ofEpochMilli(first - 500 * DAY)), await(accounts.firstSeen(main)));
    }
}
