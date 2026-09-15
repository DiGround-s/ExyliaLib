package net.exylia.lib.database;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes that several servers can make at once without losing one.
 *
 * <p>Concurrency is real here: every write goes to the database pool from
 * its own thread, so a read-then-write implementation loses counts and a
 * compare-and-set that is not part of the write hands out two winners.
 */
class AtomicWriteTest {

    private static final long TIMEOUT_SECONDS = 15L;
    private static final AtomicInteger DATABASE = new AtomicInteger();

    @Table("atomic_counters")
    record Counter(@Id String id, @Column String name, @Column long amount) {
    }

    private Plugin plugin;

    @BeforeAll
    static void server() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        plugin = FakeServer.newPlugin("Counters");
        Databases.installForTests(plugin, SqlSettings.memory("h2", "atomic" + DATABASE.incrementAndGet()));
    }

    @AfterEach
    void close() {
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Repository<Counter> counters() {
        return Databases.of(plugin).repository(Counter.class);
    }

    @Test
    @DisplayName("an increment creates the row, then adds to it without touching the rest")
    void incrementCreatesThenAdds() {
        Repository<Counter> counters = counters();

        await(counters.increment(new Counter("kills", "first", 5), "amount"));
        await(counters.increment(new Counter("kills", "second", 3), "amount"));

        Counter stored = await(counters.find("kills")).orElseThrow();
        assertEquals(8, stored.amount());
        assertEquals("first", stored.name(), "only the counted column changes on an existing row");
    }

    @Test
    @DisplayName("concurrent increments of a row that does not exist yet all count")
    void concurrentIncrementsAllCount() {
        Repository<Counter> counters = counters();
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            writes.add(counters.increment(new Counter("blocks", "mined", 1), "amount"));
        }
        await(CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)));

        assertEquals(64, await(counters.find("blocks")).orElseThrow().amount());
    }

    @Test
    @DisplayName("of callers expecting the same value, exactly one wins")
    void updateIfHasOneWinner() {
        Repository<Counter> counters = counters();
        await(counters.save(new Counter("vote", "unclaimed", 0)));

        List<CompletableFuture<Boolean>> claims = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            claims.add(counters.updateIf(new Counter("vote", "claimer-" + index, 1), "amount", 0L));
        }
        await(CompletableFuture.allOf(claims.toArray(CompletableFuture[]::new)));

        long winners = claims.stream().filter(CompletableFuture::join).count();
        assertEquals(1, winners);
        assertEquals(1, await(counters.find("vote")).orElseThrow().amount());
    }

    @Test
    @DisplayName("updateIf never creates a row, and compares null as absent")
    void updateIfEdges() {
        Repository<Counter> counters = counters();
        assertFalse(await(counters.updateIf(new Counter("ghost", "x", 1), "amount", 0L)));
        assertFalse(await(counters.exists("ghost")));

        await(counters.save(new Counter("named", null, 0)));
        assertFalse(await(counters.updateIf(new Counter("named", "late", 0), "name", "someone")));
        assertTrue(await(counters.updateIf(new Counter("named", "first", 0), "name", null)));
        assertEquals("first", await(counters.find("named")).orElseThrow().name());
    }

    @Test
    @DisplayName("a column that cannot be counted or compared is refused at the call")
    void badColumnsThrow() {
        Repository<Counter> counters = counters();
        assertThrows(IllegalArgumentException.class, () -> counters.increment(new Counter("a", "b", 1), "name"));
        assertThrows(IllegalArgumentException.class, () -> counters.increment(new Counter("a", "b", 1), "id"));
        assertThrows(IllegalArgumentException.class, () -> counters.updateIf(new Counter("a", "b", 1), "nope", 1));
    }
}
