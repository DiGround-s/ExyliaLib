package net.exylia.lib.shop.restock;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.TestDatabases;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RestockTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();
    private static final long PERIOD = 60_000L;

    private final AtomicLong now = new AtomicLong(10 * PERIOD);
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
        database = "restock" + DATABASE.incrementAndGet();
    }

    @AfterEach
    void close() {
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

    private static List<Offer> pool(int n) {
        List<Offer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Offer("o" + i, 1 + i, 2));
        return out;
    }

    private Restock server(String name, List<Offer> pool, int slots) {
        Plugin plugin = FakeServer.newPlugin(name);
        if (!installed) {
            TestDatabases.memory(plugin, database);
            installed = true;
        }
        Restock shop = Restocks.of(plugin, "seeds").every(Duration.ofMillis(PERIOD)).slots(slots).pool(pool).build();
        shop.clockForTests(now::get);
        return shop;
    }

    @Test
    void drawIsDeterministicDistinctAndIgnoresPoolOrder() {
        List<Offer> pool = pool(10);
        List<Offer> a = Rotation.draw("seeds", 5, 4, pool);
        List<Offer> reversed = new ArrayList<>(pool);
        java.util.Collections.reverse(reversed);
        assertEquals(a, Rotation.draw("seeds", 5, 4, reversed));
        assertEquals(4, new HashSet<>(a).size());
        assertNotEquals(a, Rotation.draw("seeds", 6, 4, pool), "window 6 should differ (fixed seeds)");
        assertEquals(3, Rotation.draw("seeds", 5, 9, pool(3)).size());
    }

    @Test
    void zeroWeightNeverAppearsAndWeightsBias() {
        List<Offer> pool = List.of(new Offer("never", 0, 1), new Offer("heavy", 1000, 1), new Offer("light", 1, 1));
        int heavyFirst = 0;
        for (long w = 0; w < 200; w++) {
            List<Offer> d = Rotation.draw("s", w, 2, pool);
            assertFalse(d.stream().anyMatch(o -> o.id().equals("never")));
            if (d.get(0).id().equals("heavy")) heavyFirst++;
        }
        assertTrue(heavyFirst > 180);
    }

    @Test
    void windowBoundaries() {
        assertEquals(0, Rotation.window(0, PERIOD));
        assertEquals(0, Rotation.window(PERIOD - 1, PERIOD));
        assertEquals(1, Rotation.window(PERIOD, PERIOD));
    }

    @Test
    void timesAndRotation() {
        Restock shop = server("A", pool(10), 3);
        now.set(10 * PERIOD + 1_000);
        assertEquals(Duration.ofMillis(PERIOD - 1_000), shop.untilRestock());
        assertEquals(11 * PERIOD, shop.nextRestock().toEpochMilli());
        List<Offer> before = shop.current();
        now.set(11 * PERIOD);
        assertEquals(Rotation.draw("seeds", 11, 3, pool(10)), shop.current());
        assertNotNull(before);
    }

    @Test
    void limitHoldsAcrossTwoServersAndResetsNextWindow() {
        Restock a = server("A", pool(3), 3);
        Restock b = server("B", pool(3), 3);
        assertEquals(2, await(a.remaining(player, "o0")));
        assertTrue(await(a.take(player, "o0", 1)));
        assertTrue(await(b.take(player, "o0", 1)));
        assertFalse(await(a.take(player, "o0", 1)));
        assertEquals(0, await(b.remaining(player, "o0")));
        assertFalse(await(a.take(player, "missing", 1)));
        now.addAndGet(PERIOD);
        // the new window may not even stock o0; the stored count is void either way
        for (Offer o : a.current()) assertEquals(2, await(a.remaining(player, o.id())));
    }

    @Test
    void concurrentTakesNeverOversell() {
        Restock a = server("A", pool(1), 1);
        Restock b = server("B", pool(1), 1);
        List<CompletableFuture<Boolean>> all = new ArrayList<>();
        for (int i = 0; i < 6; i++) all.add((i % 2 == 0 ? a : b).take(player, "o0", 1));
        long won = all.stream().filter(f -> await(f)).count();
        assertEquals(2, won);
    }

    @Test
    void releaseGivesBack() {
        Restock a = server("A", pool(1), 1);
        assertTrue(await(a.take(player, "o0", 2)));
        await(a.release(player, "o0", 1));
        assertEquals(1, await(a.remaining(player, "o0")));
    }

    @Test
    void restockFiresOncePerWindow() {
        Restock a = server("A", pool(5), 2);
        AtomicInteger fired = new AtomicInteger();
        a.onRestock(stock -> fired.incrementAndGet());
        a.check();
        assertEquals(0, fired.get());
        now.addAndGet(PERIOD);
        a.check();
        a.check();
        assertEquals(1, fired.get());
    }
}
