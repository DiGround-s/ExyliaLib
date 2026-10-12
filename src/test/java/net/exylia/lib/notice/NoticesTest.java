package net.exylia.lib.notice;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.database.TestDatabases;
import net.exylia.lib.notice.internal.NoticeRow;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Notices are taken exactly once, grouped by category, and never outlive their keep. */
class NoticesTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();
    private static final long DAY = 86_400_000L;

    private Plugin plugin;
    private Notices notices;
    private Repository<NoticeRow> rows;
    private final AtomicLong now = new AtomicLong(100 * DAY);
    private final UUID player = UUID.randomUUID();

    @BeforeAll
    static void install() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        plugin = FakeServer.newPlugin("Market");
        TestDatabases.memory(plugin, "notices" + DATABASE.incrementAndGet());
        notices = Notices.of(plugin);
        notices.clockForTests(now::get);
        rows = Databases.of(plugin).repository(NoticeRow.class);
    }

    @AfterEach
    void close() {
        Notices.releaseAll();
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    private void write(String category, String message, Map<String, String> values, long sentAt) {
        assertTrue(notices.store(notices.rowForTests(player, category, message, values, sentAt)));
    }

    @Test
    @DisplayName("two claims racing for one player split the notices and never share one")
    void claimedOnce() throws Exception {
        int count = 200;
        for (int i = 0; i < count; i++) {
            write("sold", "sold " + i, Map.of(), now.get() - i);
        }
        // A join and a channel arrival racing on one server, or two servers on
        // one database: either way two claims read the same rows.
        Notices elsewhere = notices;

        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
        CompletableFuture<List<Notices.Taken>> here = CompletableFuture.supplyAsync(() -> {
            await(start);
            return notices.claim(player);
        });
        CompletableFuture<List<Notices.Taken>> there = CompletableFuture.supplyAsync(() -> {
            await(start);
            return elsewhere.claim(player);
        });
        List<Notices.Taken> all = new ArrayList<>(here.get(30, TimeUnit.SECONDS));
        all.addAll(there.get(30, TimeUnit.SECONDS));

        Set<String> ids = new HashSet<>();
        all.forEach(taken -> ids.add(taken.row().id()));
        assertEquals(count, all.size());
        assertEquals(count, ids.size());
        assertTrue(notices.claim(player).isEmpty());
        assertEquals(0L, rows.count().join());
    }

    private static void await(java.util.concurrent.CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    @DisplayName("values survive the database and fill the message")
    void valuesRoundTrip() {
        write("sold", "%prefix% Sold %item% for %price%", Map.of("item", "Diamond", "price", "4500"), now.get());

        List<Notices.Taken> taken = notices.claim(player);

        assertEquals(1, taken.size());
        Notice notice = taken.getFirst().notice();
        assertEquals("Diamond", notice.values().get("item"));
        assertEquals(4500.0, notice.number("price"));
        assertTrue(notice.text(plugin).plain().contains("Sold Diamond for 4500"));
    }

    @Test
    @DisplayName("a notice sent to an offline player waits in the database")
    void offlineSendIsStored() throws Exception {
        notices.send(player, "sold", "Sold %item%", Map.of("item", "Diamond"));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (rows.count().join() == 0L) {
            assertTrue(System.nanoTime() < deadline, "never written");
            Thread.sleep(10);
        }
        assertEquals("Diamond", notices.claim(player).getFirst().notice().values().get("item"));
    }

    @Test
    @DisplayName("notices group by category in first-seen order, oldest first inside each")
    void grouping() {
        write("sold", "a", Map.of(), 1 + now.get() - 10);
        write("expired", "b", Map.of(), 2 + now.get() - 10);
        write("sold", "c", Map.of(), 3 + now.get() - 10);

        Map<String, List<Notice>> groups = Notices.group(notices.claim(player).stream()
                .map(Notices.Taken::notice).toList());

        assertEquals(List.of("sold", "expired"), List.copyOf(groups.keySet()));
        assertEquals(List.of("a", "c"), groups.get("sold").stream().map(Notice::message).toList());
    }

    @Test
    @DisplayName("the default summary shows one notice as itself and caps a long list")
    void defaultSummary() {
        Notice one = new Notice("sold", "%prefix% Sold %item%", Map.of("item", "Diamond"), 0);
        List<Text> single = notices.listed(List.of(one));
        assertEquals(1, single.size());
        assertTrue(single.getFirst().plain().contains("Sold Diamond"));

        List<Notice> many = new ArrayList<>();
        for (int i = 0; i < Notices.LISTED + 2; i++) {
            many.add(new Notice("sold", "%prefix% Sold %item%", Map.of("item", "item" + i), i));
        }
        List<Text> lines = notices.listed(many);
        assertEquals(1 + Notices.LISTED + 1, lines.size());
        assertTrue(lines.get(1).plain().contains("Sold item0"));
        assertTrue(lines.getLast().plain().contains("2 more"));
    }

    @Test
    @DisplayName("expired notices are deleted unread, by a claim and by the sweep")
    void expiry() {
        write("sold", "old", Map.of(), now.get() - 31 * DAY);
        write("sold", "new", Map.of(), now.get() - DAY);

        List<Notices.Taken> taken = notices.claim(player);
        assertEquals(List.of("new"), taken.stream().map(t -> t.notice().message()).toList());
        assertEquals(0L, rows.count().join());

        for (int i = 0; i < 250; i++) {
            write("sold", "old" + i, Map.of(), now.get() - 40 * DAY + i);
        }
        write("sold", "fresh", Map.of(), now.get());
        assertEquals(250, notices.sweep());
        assertEquals(1L, rows.count().join());
    }
}
