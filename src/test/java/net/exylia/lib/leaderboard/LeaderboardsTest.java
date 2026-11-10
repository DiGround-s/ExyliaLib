package net.exylia.lib.leaderboard;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.database.TestDatabases;
import net.exylia.lib.leaderboard.internal.LeaderboardRow;
import net.exylia.lib.leaderboard.internal.Ranking;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rows from other servers rank here, and resets start a span over without touching all time. */
class LeaderboardsTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();
    private static final UUID ANNA = new UUID(0, 1);
    private static final UUID BRUNO = new UUID(0, 2);

    private Leaderboards boards;
    private Repository<LeaderboardRow> table;

    @BeforeAll
    static void install() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        Plugin plugin = FakeServer.newPlugin("Boards");
        TestDatabases.memory(plugin, "boards" + DATABASE.incrementAndGet());
        boards = Leaderboards.hostForTests(plugin, "survival");
        table = Databases.of(plugin).repository(LeaderboardRow.class);
    }

    @AfterEach
    void close() {
        Leaderboards.releaseAll();
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    @Test
    void anotherServersRowsAddUpWithOurs() {
        boards.track("level", LeaderboardSource.placeholder("%level%").perServer());
        Leaderboards.syncForTests("level");
        Leaderboards.sampleForTests("level", ANNA, "Anna", 30, 1);
        // The lobby wrote its own row for Anna, and one for Bruno, who never came here.
        String[] keys = keys();
        table.saveAll(java.util.List.of(
                Ranking.sample(null, "level", "lobby", ANNA, "Anna", 5, keys, 1),
                Ranking.sample(null, "level", "lobby", BRUNO, "Bruno", 50, keys, 1))).join();
        Leaderboards.syncForTests("level");

        LeaderboardView all = boards.view("level", Span.ALL_TIME);
        assertEquals(2, all.size());
        assertEquals(BRUNO, all.at(1).orElseThrow().id());
        assertEquals(35, all.valueOf(ANNA).orElseThrow());
        assertEquals(2, all.rankOf(ANNA).orElseThrow());
    }

    @Test
    void ourRowsAreWrittenAndReadBack() {
        boards.track("level", LeaderboardSource.placeholder("%level%"));
        Leaderboards.syncForTests("level");
        Leaderboards.sampleForTests("level", ANNA, "Anna", 10, 1);
        Leaderboards.sampleForTests("level", ANNA, "Anna", 14, 2);
        Leaderboards.syncForTests("level");
        assertEquals(14, table.find(LeaderboardRow.id("level", "*", ANNA)).join().orElseThrow().value());
        assertEquals(4, boards.view("level", Span.WEEKLY).valueOf(ANNA).orElseThrow());
    }

    @Test
    void resettingASpanStartsItOverAndKeepsAllTime() {
        boards.track("level", LeaderboardSource.placeholder("%level%"));
        Leaderboards.syncForTests("level");
        Leaderboards.sampleForTests("level", ANNA, "Anna", 10, 1);
        Leaderboards.sampleForTests("level", ANNA, "Anna", 14, 2);
        Leaderboards.syncForTests("level");

        boards.reset("level", Span.WEEKLY).join();
        Leaderboards.syncForTests("level");
        assertEquals(0, boards.view("level", Span.WEEKLY).size());
        assertEquals(4, boards.view("level", Span.DAILY).valueOf(ANNA).orElseThrow());
        assertEquals(14, boards.view("level", Span.ALL_TIME).valueOf(ANNA).orElseThrow());

        Leaderboards.sampleForTests("level", ANNA, "Anna", 17, 3);
        Leaderboards.syncForTests("level");
        assertEquals(3, boards.view("level", Span.WEEKLY).valueOf(ANNA).orElseThrow());
    }

    @Test
    void resettingTheBoardDeletesItsRows() {
        boards.track("level", LeaderboardSource.placeholder("%level%"));
        Leaderboards.syncForTests("level");
        Leaderboards.sampleForTests("level", ANNA, "Anna", 10, 1);
        Leaderboards.syncForTests("level");
        Leaderboards.leftForTests(ANNA);

        boards.reset("level").join();
        Leaderboards.syncForTests("level");
        assertTrue(table.where("board", "level").find().join().isEmpty());
        assertEquals(0, boards.view("level", Span.ALL_TIME).size());
    }

    private String[] keys() {
        var clock = boards.clock();
        return new String[]{
                clock.key(Span.DAILY.period()) + "#0", clock.key(Span.WEEKLY.period()) + "#0",
                clock.key(Span.MONTHLY.period()) + "#0", clock.key(Span.YEARLY.period()) + "#0"};
    }
}
