package net.exylia.lib.database;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.internal.LockRow;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lock shared by servers: one holder at a time, and nobody frozen by a crash.
 *
 * <p>Two {@link RowLocks} on one database stand for two servers: each queues
 * its own holds, so only the database keeps them apart.
 */
class RowLocksTest {

    private static final long TIMEOUT_SECONDS = 30L;
    private static final AtomicInteger DATABASE = new AtomicInteger();

    private Plugin plugin;

    @BeforeAll
    static void install() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        plugin = FakeServer.newPlugin("Auctions");
        Databases.installForTests(plugin, SqlSettings.memory("h2", "locks" + DATABASE.incrementAndGet()));
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

    private RowLocks server() {
        RowLocks locks = RowLocks.of(Databases.of(plugin), "auctions");
        locks.delayForTests((millis, task) ->
                CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS).execute(task));
        return locks;
    }

    private static CompletableFuture<Void> later(long millis) {
        return CompletableFuture.runAsync(() -> { },
                CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("two servers never hold the same key at once")
    void oneHolderAcrossServers() {
        RowLocks first = server().attempts(400, Duration.ofMillis(5));
        RowLocks second = server().attempts(400, Duration.ofMillis(5));
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();

        List<CompletableFuture<Optional<Integer>>> holds = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            RowLocks locks = index % 2 == 0 ? first : second;
            holds.add(locks.hold("42", () -> {
                most.accumulateAndGet(inside.incrementAndGet(), Math::max);
                return later(3).thenApply(ignored -> inside.decrementAndGet());
            }));
        }
        await(CompletableFuture.allOf(holds.toArray(CompletableFuture[]::new)));

        assertEquals(1, most.get(), "never two holders");
        assertTrue(holds.stream().allMatch(hold -> hold.join().isPresent()), "every hold got its turn");
        assertEquals(0, first.queuedKeys());
        assertEquals(0, second.queuedKeys());
    }

    @Test
    @DisplayName("a lock whose holder crashed is taken over once its lease ran out")
    void expiredLeaseIsTakenOver() {
        Repository<LockRow> rows = Databases.of(plugin).repository(LockRow.class);
        await(rows.save(new LockRow("auctions:7", "dead-server", System.currentTimeMillis() - 1, 9)));

        Optional<String> done = await(server().attempts(1, Duration.ofMillis(1))
                .hold("7", () -> CompletableFuture.completedFuture("bid")));

        assertEquals(Optional.of("bid"), done);
    }

    @Test
    @DisplayName("a restart does not take a lock another live server holds")
    void liveLeaseIsRespected() {
        Repository<LockRow> rows = Databases.of(plugin).repository(LockRow.class);
        await(rows.save(new LockRow("auctions:7", "live-server", System.currentTimeMillis() + 60_000, 9)));
        AtomicBoolean ran = new AtomicBoolean();

        Optional<String> done = await(server().attempts(2, Duration.ofMillis(1)).hold("7", () -> {
            ran.set(true);
            return CompletableFuture.completedFuture("bid");
        }));

        assertTrue(done.isEmpty());
        assertFalse(ran.get());
        assertEquals("live-server", await(rows.find("auctions:7")).orElseThrow().holder());
    }

    @Test
    @DisplayName("work that fails or throws still gives the lock back")
    void failuresRelease() {
        RowLocks locks = server().attempts(1, Duration.ofMillis(1));

        CompletableFuture<Optional<String>> failed = locks.hold("9",
                () -> CompletableFuture.failedFuture(new IllegalStateException("database down")));
        assertThrows(IllegalStateException.class, () -> await(failed));
        CompletableFuture<Optional<String>> thrown = locks.hold("9", () -> {
            throw new IllegalStateException("bug");
        });
        assertThrows(IllegalStateException.class, () -> await(thrown));

        assertEquals(Optional.of("free"), await(locks.hold("9", () -> CompletableFuture.completedFuture("free"))));
    }

    @Test
    @DisplayName("a renewing hold outlives its lease, and is free once it ends")
    void renewedLeaseOutlivesItself() {
        RowLocks first = server().lease(Duration.ofMillis(300));
        RowLocks second = server().attempts(1, Duration.ofMillis(1));
        AtomicBoolean lost = new AtomicBoolean();

        CompletableFuture<Optional<String>> held = first.holdRenewing("5", lease -> {
            lease.lost().thenRun(() -> lost.set(true));
            return later(1_200).thenApply(ignored -> "closed");
        });
        await(later(900));
        Optional<String> stolen = await(second.hold("5", () -> CompletableFuture.completedFuture("stolen")));

        assertTrue(stolen.isEmpty(), "three leases in, the renewed hold is still held");
        assertEquals(Optional.of("closed"), await(held));
        assertFalse(lost.get());
        assertEquals(Optional.of("free"), await(second.hold("5", () -> CompletableFuture.completedFuture("free"))));
    }

    @Test
    @DisplayName("a lease taken over fails its renewal, reports the loss and is not released over the new holder")
    void takenOverLeaseIsLost() {
        Repository<LockRow> rows = Databases.of(plugin).repository(LockRow.class);
        RowLocks locks = server().lease(Duration.ofMinutes(1));

        Optional<Boolean> renewed = await(locks.holdRenewing("3", lease -> rows.find("auctions:3")
                .thenCompose(row -> rows.save(new LockRow("auctions:3", "thief",
                        System.currentTimeMillis() + 60_000, row.orElseThrow().version() + 7)))
                .thenCompose(ignored -> lease.renew())
                .thenApply(won -> {
                    assertTrue(lease.lost().isDone(), "the loss is reported");
                    return won;
                })));

        assertEquals(Optional.of(false), renewed);
        assertEquals("thief", await(rows.find("auctions:3")).orElseThrow().holder());
    }
}
