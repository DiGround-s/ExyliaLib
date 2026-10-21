package net.exylia.lib.playtime;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.database.TestDatabases;
import net.exylia.lib.playtime.internal.LegacyPlaytimeRow;
import net.exylia.lib.playtime.internal.PlaytimeRow;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Time added by two servers on one row, idle time dropped, and the one-time legacy copy. */
class PlaytimeTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();
    private static final long MINUTE = 60_000L;

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final UUID player = UUID.randomUUID();
    private String database;
    private boolean installed;

    @BeforeAll
    static void install() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        database = "playtime" + DATABASE.incrementAndGet();
    }

    @AfterEach
    void close() {
        Playtime.releaseAll();
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

    /** A server: its own plugin and tracker, on the one database the test installed. */
    private Playtime server(String name) {
        Plugin plugin = FakeServer.newPlugin(name);
        if (!installed) {
            TestDatabases.memory(plugin, database);
            installed = true;
        }
        Playtime tracker = new Playtime(Databases.of(plugin).repository(PlaytimeRow.class));
        tracker.clockForTests(now::get);
        return tracker;
    }

    @Test
    void twoServersAddToOneRow() {
        Playtime lobby = server("Lobby");
        Playtime survival = server("Survival");

        lobby.start(player);
        now.addAndGet(MINUTE);
        await(lobby.write(player, true));
        assertEquals(MINUTE, lobby.online(player));

        // Moves to survival; the lobby still adds its last stretch after survival started counting.
        survival.start(player);
        now.addAndGet(MINUTE);
        await(CompletableFuture.allOf(lobby.end(player, true), survival.write(player, true)));
        await(survival.write(player, true));

        assertEquals(3 * MINUTE, survival.online(player));
        assertEquals(3 * MINUTE, await(lobby.total(player)));
        assertEquals(-1L, lobby.online(player));
    }

    @Test
    void idleTimeIsDropped() {
        Playtime tracker = server("Survival");
        tracker.start(player);

        now.addAndGet(MINUTE);
        await(tracker.write(player, false));
        assertEquals(0L, tracker.online(player));

        now.addAndGet(MINUTE);
        await(tracker.write(player, true));
        now.addAndGet(MINUTE);
        await(tracker.end(player, false));
        assertEquals(MINUTE, await(tracker.total(player)));
    }

    @Test
    void survivalCoreTotalsAreCopiedOnce() {
        Plugin plugin = FakeServer.newPlugin("ExyliaSurvivalCore");
        TestDatabases.memory(plugin, database);
        Repository<LegacyPlaytimeRow> legacy = Databases.of(plugin).repository(LegacyPlaytimeRow.class);
        await(legacy.save(new LegacyPlaytimeRow(player.toString(), 5 * MINUTE, "first", 1L, 1L)));

        assertEquals(5 * MINUTE, await(Playtime.of(plugin).total(player)));
    }
}
