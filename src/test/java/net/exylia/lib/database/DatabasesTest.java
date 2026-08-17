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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The public facade, end to end, against a real engine.
 *
 * <p>H2 in memory rather than a mock, for the same reason the backend's own
 * tests use it: the failures this layer exists to prevent — a filter that
 * matches nothing because it was encoded twice, a sort handed over inverted, a
 * write that lands before its table exists — all pass against a mock by
 * construction.
 *
 * <p>{@link FakeServer#runAsyncForReal()} is what makes it a real test of the
 * threading: every future here is completed by another thread, exactly as it
 * would be on a server, and every assertion waits for it with a timeout rather
 * than driving a tick.
 */
class DatabasesTest {

    /** How long any single operation is given before the test is called broken. */
    private static final long TIMEOUT_SECONDS = 15L;

    private static final AtomicInteger DATABASE = new AtomicInteger();

    @Table("stats")
    record Stats(
            @Id UUID uuid,
            @Column int elo,
            @Column("kill_streak") int killStreak,
            @Indexed @Column(length = 32) String clan) {
    }

    @Table("kits")
    record Kit(@Id String id, @Column int slots) {
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
        plugin = FakeServer.newPlugin("Practice");
        Databases.installForTests(plugin,
                SqlSettings.memory("h2", "facade" + DATABASE.incrementAndGet()));
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
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for a database operation", interrupted);
        } catch (ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new AssertionError("A database operation did not complete", failure);
        }
    }

    private static Stats stats(UUID uuid, int elo, String clan) {
        return new Stats(uuid, elo, 3, clan);
    }

    // -------------------------------------------------------------- round trip

    @Test
    @DisplayName("a record saved through a repository comes back as itself")
    void roundTrip() {
        Repository<Stats> repository = Databases.of(plugin).repository(Stats.class);
        UUID uuid = UUID.randomUUID();
        Stats written = stats(uuid, 1200, "red");

        await(repository.save(written));

        assertEquals(Optional.of(written), await(repository.find(uuid)));
        assertEquals(Optional.empty(), await(repository.find(UUID.randomUUID())));
    }

    @Test
    @DisplayName("a write issued in the same breath as the registration still lands")
    void writesQueuedBeforeTheTableExistsStillWork() {
        // The point of the whole gating design. repository() returns before the
        // CREATE TABLE has run — it must, since it is called from onEnable on
        // the main thread — so a plugin that saves immediately would otherwise
        // hit "table not found" on exactly the servers whose database is
        // slowest to answer.
        Repository<Stats> repository = Databases.of(plugin).repository(Stats.class);
        UUID uuid = UUID.randomUUID();

        CompletableFuture<Void> save = repository.save(stats(uuid, 1500, "blue"));
        await(save);

        assertEquals(1500, await(repository.find(uuid)).orElseThrow().elo());
    }

    @Test
    @DisplayName("registering the same record type twice hands back the same repository")
    void repositoriesAreCached() {
        PluginDatabase database = Databases.of(plugin);
        assertSame(database.repository(Stats.class), database.repository(Stats.class));
        assertNotSame(database.repository(Stats.class), database.repository(Kit.class));
        assertEquals(2, database.registered());
    }

    @Test
    @DisplayName("saving the same key twice updates the row rather than duplicating it")
    void saveUpserts() {
        Repository<Stats> repository = Databases.of(plugin).repository(Stats.class);
        UUID uuid = UUID.randomUUID();

        await(repository.save(stats(uuid, 1200, "red")));
        await(repository.save(stats(uuid, 1450, "blue")));

        assertEquals(1L, await(repository.count()));
        assertEquals("blue", await(repository.find(uuid)).orElseThrow().clan());
    }

    // ------------------------------------------------------------------- query

    @Test
    @DisplayName("a filter narrows, and it is encoded through the column that stores it")
    void queryFilters() {
        Repository<Stats> repository = Databases.of(plugin).repository(Stats.class);
        UUID first = UUID.randomUUID();
        await(repository.saveAll(List.of(
                stats(first, 1200, "red"),
                stats(UUID.randomUUID(), 1300, "blue"),
                stats(UUID.randomUUID(), 1400, "red"))));

        assertEquals(2, await(repository.where("clan", "red").find()).size());
        // A UUID handed over as a UUID: encoded once, through its column. Twice
        // and it matches nothing, and reports that as an empty result rather
        // than as an error.
        assertEquals(1, await(repository.where("uuid", first).find()).size());
        // And the caller may name the record component rather than the column.
        assertEquals(3, await(repository.where("killStreak", 3).find()).size());
    }

    @Test
    @DisplayName("orderByDescending puts the leaderboard the right way up")
    void queryOrders() {
        // The one conversion this layer has to get right: Query.Sort carries
        // "ascending" and Dialect.Sort carries "descending". Confusing them
        // compiles, runs, and hands back the worst player as champion.
        Repository<Stats> repository = Databases.of(plugin).repository(Stats.class);
        await(repository.saveAll(List.of(
                stats(UUID.randomUUID(), 9, "a"),
                stats(UUID.randomUUID(), 100, "b"),
                stats(UUID.randomUUID(), 20, "c"))));

        assertEquals(List.of(100, 20, 9), elos(repository.all().orderByDescending("elo")));
        assertEquals(List.of(9, 20, 100), elos(repository.all().orderBy("elo")));
    }

    @Test
    @DisplayName("a limit and a skip page through the rows without repeating one")
    void queryPages() {
        Repository<Stats> repository = Databases.of(plugin).repository(Stats.class);
        List<Stats> rows = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            rows.add(stats(UUID.randomUUID(), 1000 + index, "red"));
        }
        await(repository.saveAll(rows));

        List<Integer> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            List<Stats> found = await(repository.all()
                    .orderByDescending("elo")
                    .limit(10)
                    .skip(page * 10)
                    .find());
            assertEquals(page == 2 ? 5 : 10, found.size(), "page " + page);
            found.forEach(row -> seen.add(row.elo()));
        }
        assertEquals(25, seen.size());
        assertEquals(25, seen.stream().distinct().count());
        assertEquals(1024, seen.getFirst());
    }

    @Test
    @DisplayName("findFirst takes one row without reading the rest")
    void queryFindsFirst() {
        Repository<Stats> repository = Databases.of(plugin).repository(Stats.class);
        await(repository.saveAll(List.of(
                stats(UUID.randomUUID(), 1200, "red"),
                stats(UUID.randomUUID(), 1400, "red"))));

        assertEquals(1400, await(repository.all().orderByDescending("elo").findFirst())
                .orElseThrow().elo());
        assertEquals(Optional.empty(), await(repository.where("clan", "nobody").findFirst()));
    }

    @Test
    @DisplayName("count answers from the database, filtered or not")
    void counts() {
        Repository<Stats> repository = Databases.of(plugin).repository(Stats.class);
        await(repository.saveAll(List.of(
                stats(UUID.randomUUID(), 1200, "red"),
                stats(UUID.randomUUID(), 1300, "blue"),
                stats(UUID.randomUUID(), 1400, "red"))));

        assertEquals(3L, await(repository.count()));
        assertEquals(2L, await(repository.where("clan", "red").count()));
        assertEquals(0L, await(repository.where("clan", "green").count()));
    }

    @Test
    @DisplayName("exists answers without reading the row")
    void exists() {
        Repository<Stats> repository = Databases.of(plugin).repository(Stats.class);
        UUID uuid = UUID.randomUUID();
        await(repository.save(stats(uuid, 1200, "red")));

        assertTrue(await(repository.exists(uuid)));
        assertFalse(await(repository.exists(UUID.randomUUID())));
    }

    @Test
    @DisplayName("findAll reads every row")
    void findsAll() {
        Repository<Stats> repository = Databases.of(plugin).repository(Stats.class);
        await(repository.saveAll(List.of(
                stats(UUID.randomUUID(), 1200, "red"),
                stats(UUID.randomUUID(), 1300, "blue"))));

        assertEquals(2, await(repository.findAll()).size());
    }

    // ------------------------------------------------------------------ write

    @Test
    @DisplayName("saveAll writes every row, and an empty batch does nothing at all")
    void savesInBatches() {
        Repository<Stats> repository = Databases.of(plugin).repository(Stats.class);
        List<Stats> rows = new ArrayList<>();
        for (int index = 0; index < 250; index++) {
            rows.add(stats(UUID.randomUUID(), 1000 + index, "clan" + (index % 5)));
        }

        await(repository.saveAll(rows));
        assertEquals(250L, await(repository.count()));

        // The same keys again: an upsert batch, not 250 duplicates.
        await(repository.saveAll(rows));
        assertEquals(250L, await(repository.count()));

        // A save-on-quit sweep that finds nothing to write runs constantly on a
        // busy server, and must not cost a connection.
        await(repository.saveAll(List.of()));
        assertEquals(250L, await(repository.count()));
    }

    @Test
    @DisplayName("delete removes the row and says whether there was one")
    void deletesById() {
        Repository<Stats> repository = Databases.of(plugin).repository(Stats.class);
        UUID uuid = UUID.randomUUID();
        await(repository.save(stats(uuid, 1200, "red")));

        assertTrue(await(repository.delete(uuid)));
        assertFalse(await(repository.delete(uuid)));
        assertEquals(Optional.empty(), await(repository.find(uuid)));
    }

    @Test
    @DisplayName("a filtered delete removes what it matched and leaves the rest")
    void deletesByFilter() {
        Repository<Stats> repository = Databases.of(plugin).repository(Stats.class);
        await(repository.saveAll(List.of(
                stats(UUID.randomUUID(), 1200, "red"),
                stats(UUID.randomUUID(), 1300, "blue"),
                stats(UUID.randomUUID(), 1400, "red"))));

        assertEquals(2, await(repository.where("clan", "red").delete()));
        assertEquals(1L, await(repository.count()));
        assertEquals("blue", await(repository.findAll()).getFirst().clan());
    }

    @Test
    @DisplayName("a filtered delete honours a limit, for a retention sweep in batches")
    void deletesUpToALimit() {
        Repository<Stats> repository = Databases.of(plugin).repository(Stats.class);
        List<Stats> rows = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            rows.add(stats(UUID.randomUUID(), 1000 + index, "red"));
        }
        await(repository.saveAll(rows));

        assertEquals(4, await(repository.where("clan", "red").limit(4).delete()));
        assertEquals(6L, await(repository.count()));
    }

    @Test
    @DisplayName("an unfiltered delete clears the table")
    void deletesEverything() {
        Repository<Stats> repository = Databases.of(plugin).repository(Stats.class);
        await(repository.saveAll(List.of(
                stats(UUID.randomUUID(), 1200, "red"),
                stats(UUID.randomUUID(), 1300, "blue"))));

        // Allowed, because Query offers it and clearing a table is a real
        // operation — a season reset, a wiped arena. It goes row by row rather
        // than as one statement, so it is honest about being expensive on a
        // large table; that is what the limit is for.
        assertEquals(2, await(repository.all().delete()));
        assertEquals(0L, await(repository.count()));
    }

    @Test
    @DisplayName("many operations in flight at once all complete, on background threads")
    void concurrentOperationsAllLand() {
        Repository<Stats> repository = Databases.of(plugin).repository(Stats.class);
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            writes.add(repository.save(stats(UUID.randomUUID(), 1000 + index, "red")));
        }
        // Issued from this thread without waiting between them, exactly as a
        // join handler would: none of them may have run on this thread, and all
        // of them must land.
        await(CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)));
        assertEquals(60L, await(repository.count()));
    }

    // ---------------------------------------------------------------- failure

    @Test
    @DisplayName("a filter naming a column nothing has fails the future rather than the thread")
    void unknownColumnFailsTheFuture() {
        Repository<Stats> repository = Databases.of(plugin).repository(Stats.class);
        await(repository.save(stats(UUID.randomUUID(), 1200, "red")));

        // Not thrown from the call: the call returned before the query ran. A
        // failure that escaped onto the background thread instead would land in
        // the scheduler's log with no indication of who asked for it.
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> repository.where("nonesuch", 1).find().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    @Test
    @DisplayName("a record that cannot be stored is refused at registration, not at the first write")
    void badRecordFailsAtRegistration() {
        record NotAnEntity(String id) {
        }
        // Loud, synchronous, at enable, where the developer is watching — rather
        // than inside a background write three days later, where nobody is.
        assertThrows(IllegalArgumentException.class,
                () -> Databases.of(plugin).repository(NotAnEntity.class));
    }

    @Test
    @DisplayName("a repository's view of the store refuses to close the shared connection")
    void repositoriesDoNotOwnTheConnection() {
        // One pool serves the whole server. A view that closed it would take
        // every other plugin's database with it, and doing nothing instead would
        // let the caller believe it had.
        Databases.of(plugin).repository(Stats.class);
        var view = new net.exylia.lib.database.internal.GatedStorage(
                net.exylia.lib.database.internal.DatabaseRuntime.storage());
        assertThrows(UnsupportedOperationException.class, view::close);
    }

    @Test
    @DisplayName("a failure to prepare fails every operation rather than answering empty")
    void aBrokenTableFailsLoudly() {
        // Pointed at a database file that cannot exist: /dev/null is a device,
        // and H2 creates missing directories but not inside one.
        Databases.installForTests(plugin,
                SqlSettings.file("h2", java.nio.file.Path.of("/dev/null/db")));
        Repository<Stats> repository = Databases.of(plugin).repository(Stats.class);

        // A read answered with an empty list would be indistinguishable from a
        // database that is simply new, which is how a plugin comes to overwrite
        // everything it could not read.
        assertThrows(ExecutionException.class,
                () -> repository.findAll().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class,
                () -> repository.save(stats(UUID.randomUUID(), 1, "red"))
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertFalse(Databases.isReady());
    }

    // --------------------------------------------------------------- isolation

    @Test
    @DisplayName("two plugins share the connection and nothing else")
    void twoPluginsAreIsolated() {
        Plugin other = FakeServer.newPlugin("Survival");
        PluginDatabase practice = Databases.of(plugin);
        PluginDatabase survival = Databases.of(other);

        assertNotSame(practice, survival);
        assertSame(plugin, practice.plugin());
        assertSame(other, survival.plugin());

        Repository<Stats> theirs = practice.repository(Stats.class);
        Repository<Kit> ours = survival.repository(Kit.class);

        await(theirs.save(stats(UUID.randomUUID(), 1200, "red")));
        await(ours.save(new Kit("boxing", 36)));

        // Different tables on one intentionally shared target: neither plugin
        // can see the other's rows and neither had to configure a connection.
        assertEquals(1L, await(theirs.count()));
        assertEquals(1L, await(ours.count()));
        assertEquals("stats", theirs.table());
        assertEquals("kits", ours.table());

        // Registering a class one plugin already registered gives the asking
        // plugin its own repository, not the other's.
        assertNotSame(theirs, survival.repository(Stats.class));
        assertFalse(practice.has(Kit.class));
        assertTrue(survival.has(Kit.class));
    }

    @Test
    @DisplayName("the same record type in two plugins reads the same rows")
    void twoPluginsSeeOneTable() {
        // The other half of "one connection for the server": two plugins that
        // declare the same table are looking at the same data, which is what
        // makes a shared player-stats table possible at all.
        Plugin other = FakeServer.newPlugin("Survival");
        Repository<Stats> mine = Databases.of(plugin).repository(Stats.class);
        Repository<Stats> theirs = Databases.of(other).repository(Stats.class);

        UUID uuid = UUID.randomUUID();
        await(mine.save(stats(uuid, 1200, "red")));

        assertEquals(1200, await(theirs.find(uuid)).orElseThrow().elo());
    }

    // --------------------------------------------------------------- lifecycle

    @Test
    @DisplayName("releasing a plugin drops its repositories and leaves everyone else alone")
    void releaseDropsOnePlugin() {
        Plugin other = FakeServer.newPlugin("Survival");
        Repository<Stats> mine = Databases.of(plugin).repository(Stats.class);
        Repository<Kit> theirs = Databases.of(other).repository(Kit.class);
        await(mine.save(stats(UUID.randomUUID(), 1200, "red")));
        await(theirs.save(new Kit("boxing", 36)));
        assertEquals(2, Databases.registered());

        Databases.release(plugin.getName());

        assertEquals(1, Databases.registered());
        // Their shared target stays alive while the surviving plugin holds it.
        assertTrue(Databases.isReady());
        assertEquals(1L, await(theirs.count()));
        // And the released plugin gets a fresh view rather than the old one.
        assertEquals(0, Databases.of(plugin).registered());
    }

    @Test
    @DisplayName("releasing a plugin leaves its table and its rows exactly where they were")
    void releaseKeepsTheData() {
        Repository<Stats> before = Databases.of(plugin).repository(Stats.class);
        UUID uuid = UUID.randomUUID();
        await(before.save(stats(uuid, 1200, "red")));

        Databases.release(plugin.getName());

        // A release drops the objects that address the table, never the table:
        // a plugin reloaded mid-session must find its data.
        Repository<Stats> after = Databases.of(plugin).repository(Stats.class);
        assertEquals(1200, await(after.find(uuid)).orElseThrow().elo());
    }

    @Test
    @DisplayName("releasing everything closes the connection")
    void releaseAllClosesTheConnection() {
        Repository<Stats> repository = Databases.of(plugin).repository(Stats.class);
        await(repository.save(stats(UUID.randomUUID(), 1200, "red")));
        assertTrue(Databases.isReady());

        Databases.releaseAll();

        // A pool that outlives its plugin holds threads and sockets nothing will
        // ever close.
        assertFalse(Databases.isReady());
        assertEquals(0, Databases.registered());
    }

    @Test
    @DisplayName("nothing connects until a plugin asks for a repository")
    void nothingConnectsOnItsOwn() {
        // A server whose plugins store nothing should not create a database
        // file, nor contact a MySQL host that may not be up yet.
        assertFalse(Databases.isReady());
        Databases.of(plugin);
        assertFalse(Databases.isReady());

        await(Databases.of(plugin).repository(Stats.class).count());
        assertTrue(Databases.isReady());
    }

    @Test
    @DisplayName("the engine is reported for a diagnostics command")
    void reportsTheEngine() {
        assertEquals("h2", Databases.engine());
    }

    @Test
    @DisplayName("a Mongo URI never exposes its credentials in diagnostics")
    void mongoUriIsRedactedInSettingsDiagnostics() {
        SqlSettings settings = SqlSettings.remote("mongodb", "mongodb://admin:secret@cluster.example/app",
                0, "app", "ignored", "ignored");

        assertFalse(settings.toString().contains("admin:secret"));
        assertTrue(settings.toString().contains("***@cluster.example"));
    }

    @Test
    @DisplayName("equal settings reuse one target and survive the first release")
    void equalSettingsShareATarget() {
        Plugin other = FakeServer.newPlugin("Survival");
        Databases.installForTests(plugin, java.util.Map.of(
                plugin.getName(), SqlSettings.memory("h2", "shared" + DATABASE.incrementAndGet()),
                other.getName(), SqlSettings.memory("h2", "shared" + DATABASE.get())));

        Repository<Stats> mine = Databases.of(plugin).repository(Stats.class);
        Repository<Stats> theirs = Databases.of(other).repository(Stats.class);
        UUID uuid = UUID.randomUUID();
        await(mine.save(stats(uuid, 1200, "red")));

        assertEquals(1, Databases.targetsForTests());
        assertEquals(1200, await(theirs.find(uuid)).orElseThrow().elo());
        Databases.release(plugin.getName());

        assertEquals(1, Databases.targetsForTests());
        assertTrue(Databases.isReady());
        assertEquals(1200, await(theirs.find(uuid)).orElseThrow().elo());
    }

    @Test
    @DisplayName("different settings isolate targets")
    void differentSettingsUseDifferentTargets() {
        Plugin other = FakeServer.newPlugin("Survival");
        Databases.installForTests(plugin, java.util.Map.of(
                plugin.getName(), SqlSettings.memory("h2", "practice" + DATABASE.incrementAndGet()),
                other.getName(), SqlSettings.memory("h2", "survival" + DATABASE.incrementAndGet())));

        Repository<Stats> mine = Databases.of(plugin).repository(Stats.class);
        Repository<Stats> theirs = Databases.of(other).repository(Stats.class);
        await(mine.save(stats(UUID.randomUUID(), 1200, "red")));

        assertEquals(2, Databases.targetsForTests());
        assertEquals(0L, await(theirs.count()));
        assertEquals("multiple", Databases.engine());
    }

    @Test
    @DisplayName("default H2 settings resolve inside each consumer folder")
    void defaultH2PathsArePerPlugin() throws java.io.IOException {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("exylia-database");
        Plugin practice = FakeServer.newPlugin("Practice", root.resolve("Practice").toFile());
        Plugin survival = FakeServer.newPlugin("Survival", root.resolve("Survival").toFile());
        Databases.releaseAll();
        Databases.init(plugin);

        PluginDatabase practiceDatabase = Databases.of(practice);
        PluginDatabase survivalDatabase = Databases.of(survival);
        assertEquals("h2", practiceDatabase.engine());
        assertEquals("h2", survivalDatabase.engine());
        await(practiceDatabase.repository(Stats.class).count());
        await(survivalDatabase.repository(Stats.class).count());
        assertEquals(2, Databases.targetsForTests());

        Databases.release(practice.getName());
        Databases.release(survival.getName());
        java.nio.file.Files.walk(root)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        java.nio.file.Files.delete(path);
                    } catch (java.io.IOException failure) {
                        throw new AssertionError("Could not remove test directory", failure);
                    }
                });
    }

    @Test
    @DisplayName("the final release closes and removes its target")
    void finalReleaseClosesTarget() {
        Repository<Stats> repository = Databases.of(plugin).repository(Stats.class);
        await(repository.save(stats(UUID.randomUUID(), 1200, "red")));
        assertEquals(1, Databases.targetsForTests());

        Databases.release(plugin.getName());

        assertEquals(0, Databases.targetsForTests());
        assertFalse(Databases.isReady());
    }

    // ------------------------------------------------------- composite indexes

    /** The leaderboard shape, through the public facade this time. */
    @Table("leaderboards")
    @Index(columns = {"kit_id", "elo"}, descending = {"elo"})
    record KitStats(
            @Id String id,
            @Column("kit_id") String kitId,
            @Column int elo,
            @Column int wins) {
    }

    @Test
    @DisplayName("a leaderboard query through the facade is answered in the index's order")
    void leaderboardThroughTheFacade() {
        Repository<KitStats> repository = Databases.of(plugin).repository(KitStats.class);
        List<KitStats> rows = new ArrayList<>();
        for (int elo = 0; elo < 50; elo++) {
            rows.add(new KitStats("p" + elo, elo % 2 == 0 ? "boxing" : "nodebuff", elo, elo));
        }
        await(repository.saveAll(rows));

        List<KitStats> top = await(repository.where("kit_id", "boxing")
                .orderByDescending("elo")
                .limit(3)
                .find());
        assertEquals(List.of(48, 46, 44), top.stream().map(KitStats::elo).toList());
    }

    @Test
    @DisplayName("a query no index covers is warned about once, through Debug")
    void theMissingIndexWarningReachesTheConsole() {
        // The whole reason the diagnostic exists: a missing index is invisible
        // until the table is large. And the reason it fires once: a leaderboard
        // menu every player opens would otherwise print it on every click, and a
        // warning printed a thousand times is one nobody reads.
        net.exylia.lib.database.internal.IndexCoverageAccess.forgetAll();
        List<String> lines = net.exylia.lib.debug.DebugCapture.start();
        try {
            Repository<KitStats> repository = Databases.of(plugin).repository(KitStats.class);
            for (int repeat = 0; repeat < 5; repeat++) {
                await(repository.where("wins", 3).find());
            }
            List<String> warned = lines.stream()
                    .filter(line -> line.contains("no index covers"))
                    .toList();
            assertEquals(1, warned.size(), lines::toString);
            assertTrue(warned.get(0).contains("leaderboards"), warned.get(0));
            assertTrue(warned.get(0).contains("@Index"), warned.get(0));
        } finally {
            net.exylia.lib.debug.DebugCapture.stop();
            net.exylia.lib.database.internal.IndexCoverageAccess.forgetAll();
        }
    }

    // ----------------------------------------------------------------- codecs

    @Test
    @DisplayName("a registered codec is what stores a type the library did not know")
    void codecs() {
        record Coordinate(int x, int z) {
        }
        Databases.codec(Coordinate.class, Codec.of(
                value -> value.x() + ":" + value.z(),
                stored -> new Coordinate(
                        Integer.parseInt(stored.split(":")[0]),
                        Integer.parseInt(stored.split(":")[1]))));

        @Table("spawns")
        record Spawn(@Id String world, @Column Coordinate at) {
        }

        Repository<Spawn> repository = Databases.of(plugin).repository(Spawn.class);
        Spawn written = new Spawn("arena", new Coordinate(120, -40));
        await(repository.save(written));

        assertEquals(Optional.of(written), await(repository.find("arena")));
    }

    private static List<Integer> elos(Query<Stats> query) {
        return await(query.find()).stream().map(Stats::elo).toList();
    }
}
