package net.exylia.lib.log;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.TestDatabases;
import net.exylia.lib.internal.LibrarySettings;
import net.exylia.lib.log.internal.LogRow;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Entries are queued, written in a batch, read back across plugins, filtered and purged by day. */
class LogsTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();
    private static final long DAY = 86_400_000L;

    private final AtomicLong now = new AtomicLong(1000 * DAY);
    private final UUID steve = UUID.randomUUID();
    private final UUID alex = UUID.randomUUID();
    private Plugin market;
    private Plugin core;
    private Logs marketLogs;
    private Logs coreLogs;

    @BeforeAll
    static void install() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        String database = "logs" + DATABASE.incrementAndGet();
        market = FakeServer.newPlugin("ExyliaMarket");
        core = FakeServer.newPlugin("ExyliaSurvivalCore");
        TestDatabases.memory(market, database);
        TestDatabases.memory(core, database);
        marketLogs = Logs.of(market).action("trade", "CHEST");
        coreLogs = Logs.of(core);
        marketLogs.clockForTests(now::get);
        coreLogs.clockForTests(now::get);
    }

    @AfterEach
    void close() {
        Logs.settingsForTests(null);
        Logs.releaseAll();
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    private void tick() {
        now.addAndGet(1000);
    }

    @Test
    @DisplayName("an entry is written in a batch and read back with its groups")
    void roundTrip() {
        marketLogs.record("Trade", steve, "Steve", alex, "Alex", "16x Diamond for 1x Dirt", null,
                List.of(new Logs.Group("Steve gave", List.of()), new Logs.Group("Alex gave", List.of())));

        assertEquals(0L, Databases.of(market).repository(LogRow.class).count().join(), "only queued");

        List<LogRow> read = Logs.read(null, null, null).join();

        assertEquals(1, read.size());
        LogRow row = read.getFirst();
        assertEquals("trade", row.action());
        assertEquals("ExyliaMarket", row.plugin());
        assertEquals(steve.toString(), row.actor());
        assertEquals("Alex", row.targetName());
        assertEquals("16x Diamond for 1x Dirt", row.detail());
        assertEquals(1000L, row.createdDay());
        assertNull(row.world());
        assertEquals(List.of("Steve gave", "Alex gave"), row.groups().stream().map(Logs.Group::label).toList());
        assertEquals("CHEST", Logs.icon("ExyliaMarket", "trade"));
        assertEquals("PAPER", Logs.icon("ExyliaSurvivalCore", "pay"));
        assertTrue(Logs.find(row.id()).join().isPresent());
    }

    @Test
    @DisplayName("a player's read finds what they did and what was done to them, once each, newest first")
    void playerSides() {
        marketLogs.record("trade", steve, "Steve", alex, "Alex", "first", null);
        tick();
        coreLogs.record("pay", alex, "Alex", steve, "Steve", "second", null);
        tick();
        coreLogs.record("rank-set", steve, "Steve", steve, "Steve", "to himself", null);
        tick();
        coreLogs.record("pay", alex, "Alex", null, null, "nobody", null);

        List<LogRow> mine = Logs.read(steve, null, null).join();

        assertEquals(List.of("to himself", "second", "first"), mine.stream().map(LogRow::detail).toList());
        assertEquals(4, Logs.read(null, null, null).join().size());
    }

    @Test
    @DisplayName("the plugin and action filters narrow the read")
    void filters() {
        marketLogs.record("trade", steve, "Steve", alex, "Alex", "a", null);
        coreLogs.record("pay", steve, "Steve", alex, "Alex", "b", null);
        coreLogs.record("rank-set", steve, "Steve", alex, "Alex", "c", null);

        assertEquals(List.of("a"), details(Logs.read(steve, "exyliamarket", null).join()));
        assertEquals(List.of("b"), details(Logs.read(null, "ExyliaSurvivalCore", "pay").join()));
        assertEquals(List.of("c"), details(Logs.read(alex, null, "rank-set").join()));
        assertEquals(List.of("pay", "rank-set"), Logs.actions("ExyliaSurvivalCore"));
        assertEquals(List.of("pay", "rank-set", "trade"), Logs.actions(null));
        assertEquals(List.of("ExyliaMarket", "ExyliaSurvivalCore"), Logs.plugins());
    }

    @Test
    @DisplayName("a disabled plugin, or one of its actions, is not recorded")
    void disabled() {
        Logs.settingsForTests(new LibrarySettings.Logs(30, 10, 540, 20,
                List.of("ExyliaMarket", "ExyliaSurvivalCore:pay")));

        marketLogs.record("trade", steve, "Steve", alex, "Alex", "a", null);
        coreLogs.record("pay", steve, "Steve", alex, "Alex", "b", null);
        coreLogs.record("rank-set", steve, "Steve", alex, "Alex", "c", null);

        assertEquals(List.of("c"), details(Logs.read(null, null, null).join()));
    }

    @Test
    @DisplayName("a purge deletes only that plugin's days past the retention")
    void purge() {
        long start = now.get();
        now.set(start - 40 * DAY);
        marketLogs.record("trade", steve, "Steve", null, null, "old market", null);
        coreLogs.record("pay", steve, "Steve", null, null, "old core", null);
        now.set(start - 29 * DAY);
        marketLogs.record("trade", steve, "Steve", null, null, "recent market", null);
        now.set(start);
        Logs.read(null, null, null).join();

        assertEquals(1, marketLogs.purge().join());

        assertEquals(List.of("recent market", "old core"), details(Logs.read(null, null, null).join()));
    }

    @Test
    @DisplayName("long text is cut and a console actor is stored as CONSOLE")
    void caps() {
        marketLogs.record("x".repeat(60), null, null, null, null, "d".repeat(5000), null);

        LogRow row = Logs.read(null, null, null).join().getFirst();

        assertEquals(48, row.action().length());
        assertEquals(Logs.CONSOLE, row.actorName());
        assertEquals(Logs.MAX_DETAIL, row.detail().length());
    }

    private static List<String> details(List<LogRow> rows) {
        return rows.stream().map(LogRow::detail).toList();
    }
}
