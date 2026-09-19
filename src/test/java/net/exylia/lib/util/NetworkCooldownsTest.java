package net.exylia.lib.util;

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

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cooldown that a relog or a server hop does not reset, whatever its length,
 * and that refuses while it is not known yet.
 */
class NetworkCooldownsTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();

    private Plugin plugin;
    private NetworkCooldowns cooldowns;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final UUID player = UUID.randomUUID();

    @BeforeAll
    static void install() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        plugin = FakeServer.newPlugin("Shop");
        TestDatabases.memory(plugin, "cooldowns" + DATABASE.incrementAndGet());
        cooldowns = NetworkCooldowns.of(plugin);
        cooldowns.clockForTests(now::get);
    }

    @AfterEach
    void close() {
        NetworkCooldowns.releaseAll();
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    private static void await(CompletableFuture<?> future) {
        try {
            future.get(30, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Joins and waits for the read, the way a player waits for their first command. */
    private void join(NetworkCooldowns on, UUID who) {
        on.open(who);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!on.isLoaded(who)) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("never loaded");
            }
            Thread.onSpinWait();
        }
    }

    /** Leaves once every write is in, the way a hop takes longer than a write. */
    private void leave(NetworkCooldowns on, UUID who) {
        await(on.writesForTests(who));
        NetworkCooldowns.left(who);
    }

    @Test
    @DisplayName("a short cooldown survives a relog")
    void survivesRelog() {
        join(cooldowns, player);
        assertTrue(cooldowns.tryStart(player, "heal", Duration.ofSeconds(30)));
        assertFalse(cooldowns.tryStart(player, "heal", Duration.ofSeconds(30)));

        leave(cooldowns, player);
        join(cooldowns, player);

        assertTrue(cooldowns.isActive(player, "heal"));
        assertEquals(Duration.ofSeconds(30), cooldowns.remaining(player, "heal"));
        assertFalse(cooldowns.tryStart(player, "heal", Duration.ofSeconds(30)));
    }

    @Test
    @DisplayName("a cooldown follows the player to another server on the same database")
    void followsServerHop() {
        join(cooldowns, player);
        cooldowns.start(player, "repair", Duration.ofSeconds(10));
        leave(cooldowns, player);

        Plugin other = FakeServer.newPlugin("Shop");
        NetworkCooldowns elsewhere = NetworkCooldowns.of(other);
        elsewhere.clockForTests(now::get);
        join(elsewhere, player);

        assertTrue(elsewhere.isActive(player, "repair"));
        now.addAndGet(10_001L);
        assertFalse(elsewhere.isActive(player, "repair"));
        assertTrue(elsewhere.tryStart(player, "repair", Duration.ofSeconds(10)));
    }

    @Test
    @DisplayName("before the read comes back, the answer is wait")
    void unknownRefuses() {
        UUID stranger = UUID.randomUUID();

        assertFalse(cooldowns.isLoaded(stranger));
        assertTrue(cooldowns.isActive(stranger, "heal"));
        assertFalse(cooldowns.tryStart(stranger, "heal", Duration.ofSeconds(5)));
        assertEquals(Duration.ZERO, cooldowns.remaining(stranger, "heal"));
    }

    @Test
    @DisplayName("a clear reaches the network, and an ended row is gone on the next join")
    void clearAndExpiry() {
        join(cooldowns, player);
        cooldowns.start(player, "near", Duration.ofSeconds(5));
        cooldowns.clear(player, "near");
        cooldowns.start(player, "feed", Duration.ofSeconds(5));
        leave(cooldowns, player);

        join(cooldowns, player);
        assertFalse(cooldowns.isActive(player, "near"));
        assertTrue(cooldowns.isActive(player, "feed"));

        leave(cooldowns, player);
        now.addAndGet(6_000L);
        join(cooldowns, player);
        assertFalse(cooldowns.isActive(player, "feed"));
        assertTrue(cooldowns.tryStart(player, "feed", Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("a cooldown started for somebody who is not here waits for their join")
    void offlineStart() {
        UUID absent = UUID.randomUUID();
        cooldowns.start(absent, "bounty", Duration.ofMinutes(1));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do {
            NetworkCooldowns.left(absent);
            join(cooldowns, absent);
        } while (!cooldowns.isActive(absent, "bounty") && System.nanoTime() < deadline);

        assertTrue(cooldowns.isActive(absent, "bounty"));
    }

    @Test
    @DisplayName("each plugin keeps its own keys")
    void namespaced() {
        join(cooldowns, player);
        cooldowns.start(player, "heal", Duration.ofSeconds(30));
        leave(cooldowns, player);

        Plugin other = FakeServer.newPlugin("Arena");
        NetworkCooldowns arena = NetworkCooldowns.of(other);
        join(arena, player);

        assertFalse(arena.isActive(player, "heal"));
    }
}
