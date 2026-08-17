package net.exylia.lib.database;

import net.exylia.lib.database.internal.Dialect;
import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.SqlBackend;
import net.exylia.lib.database.internal.SqlSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backend against a real engine.
 *
 * <p>H2 in memory, not a mock. A mock cannot tell whether a second write
 * inserted a duplicate or updated a row, whether an index was actually created,
 * or whether an unbounded column came back as its text or as
 * {@code JdbcClob@1a2b} — and those are exactly the failures this layer exists
 * to prevent. Every test here would pass against a mock and three of them would
 * still be shipping bugs.
 *
 * <p>Each test gets its own database name, so nothing leaks between them and
 * they can run in any order.
 */
class SqlBackendTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();

    enum Rank { DEFAULT, VIP, MVP }

    @Table("player_stats")
    record Stats(
            @Id UUID uuid,
            @Column int elo,
            @Column("kill_streak") int killStreak,
            @Column long playtime,
            @Column double ratio,
            @Column float accuracy,
            @Column boolean banned,
            @Indexed @Column(length = 32) String clan,
            @Column Rank rank,
            @Column BigDecimal balance,
            @Column(length = Column.UNBOUNDED) String notes,
            @Column List<String> tags) {
    }

    private SqlBackend backend;
    private EntityModel<Stats> model;

    @BeforeEach
    void open() {
        backend = SqlBackend.open(
                SqlSettings.memory("h2", "test" + DATABASE.incrementAndGet()), "tests");
        model = EntityModel.of(Stats.class);
    }

    @AfterEach
    void close() {
        if (backend != null) {
            backend.close();
        }
    }

    private static Stats stats(UUID uuid, int elo, String clan) {
        return new Stats(uuid, elo, 3, 900L, 1.5d, 0.25f, false, clan, Rank.VIP,
                new BigDecimal("12.3400000000"), "a note", List.of("a", "b"));
    }

    // ---------------------------------------------------------------- schema

    @Test
    @DisplayName("the table is created, and creating it again changes nothing")
    void createTableIsIdempotent() throws SQLException {
        assertTrue(backend.ensureTable(model).createdTable());
        // Every server start runs this. A second run that threw, or that
        // dropped and rebuilt, would lose a live server's data on restart.
        assertFalse(backend.ensureTable(model).createdTable());
        assertFalse(backend.ensureTable(model).changed());
    }

    @Test
    @DisplayName("an index is created once and never again")
    void indexCreationIsIdempotent() throws SQLException {
        assertEquals(List.of("idx_player_stats_clan"), backend.ensureTable(model).createdIndexes());
        assertEquals(List.of(), backend.ensureTable(model).createdIndexes());
        assertEquals(List.of(), backend.ensureTable(model).createdIndexes());
    }

    @Test
    @DisplayName("no second index is created over the primary key")
    void primaryKeyIsNotIndexedTwice() throws SQLException {
        backend.ensureTable(model);
        List<String> indexes = new ArrayList<>();
        try (Connection connection = connect();
             ResultSet found = connection.getMetaData()
                     .getIndexInfo(null, null, "player_stats", false, false)) {
            while (found.next()) {
                String name = found.getString("INDEX_NAME");
                if (name != null && name.toLowerCase().startsWith("idx_")) {
                    indexes.add(name.toLowerCase());
                }
            }
        }
        // A redundant index over the key costs a write on every insert and
        // answers nothing the key's own index does not.
        assertEquals(List.of("idx_player_stats_clan"), indexes);
    }

    // ------------------------------------------------------ composite indexes

    /**
     * The real leaderboard shape, copied from ExyliaPracticeCore.
     *
     * <p>Filter by kit, sort by elo descending. Twelve of these sit on one live
     * table, and it is the case two single-column indexes cannot answer: a
     * database uses one index, not both.
     */
    @Table("practice_player_stats")
    @Index(columns = {"kit_id", "elo"}, descending = {"elo"})
    @Index(columns = {"season", "kit_id"}, unique = true)
    record KitStats(
            @Id String id,
            @Column("kit_id") String kitId,
            @Column int elo,
            @Column int season) {
    }

    @Test
    @DisplayName("a composite index is created on a real engine, with the columns in order")
    void compositeIndexIsCreated() throws SQLException {
        EntityModel<KitStats> kits = EntityModel.of(KitStats.class);
        assertTrue(backend.ensureTable(kits).createdTable());

        // Read back through the metadata, one row per column, ordered by
        // ORDINAL_POSITION — which is the same call the schema layer makes to
        // decide whether an index is already there.
        assertEquals(List.of("kit_id", "elo"),
                indexColumns("practice_player_stats", "idx_practice_player_stats_kit_id_elo"));
        assertEquals(List.of("season", "kit_id"),
                indexColumns("practice_player_stats", "idx_practice_player_stats_season_kit_id"));
    }

    @Test
    @DisplayName("a unique composite index is unique on a real engine, and refuses a duplicate pair")
    void uniqueCompositeIsEnforced() throws SQLException {
        EntityModel<KitStats> kits = EntityModel.of(KitStats.class);
        backend.ensureTable(kits);

        backend.save(kits, new KitStats("a", "boxing", 1200, 1));
        // Same season and kit under a different key. Asserted by executing it,
        // not by reading NON_UNIQUE: a unique index that parses and does not
        // enforce is exactly the MariaDB prefix-index failure this module
        // reports, and only a real insert can tell the difference.
        assertThrows(SQLException.class,
                () -> backend.save(kits, new KitStats("b", "boxing", 1300, 1)));
        // A different kit in the same season is fine, which is what makes it a
        // composite constraint rather than one on season alone.
        backend.save(kits, new KitStats("c", "nodebuff", 1300, 1));
    }

    @Test
    @DisplayName("composite indexes are created once and never again")
    void compositeIndexCreationIsIdempotent() throws SQLException {
        EntityModel<KitStats> kits = EntityModel.of(KitStats.class);
        assertEquals(List.of("idx_practice_player_stats_kit_id_elo",
                        "idx_practice_player_stats_season_kit_id"),
                backend.ensureTable(kits).createdIndexes());
        // Every server start runs this. Reporting them again would mean the
        // metadata lookup cannot see a composite index, and on MySQL — which
        // cannot say IF NOT EXISTS — it would mean a failed statement swallowed
        // as a duplicate on every single boot.
        assertEquals(List.of(), backend.ensureTable(kits).createdIndexes());
        assertEquals(List.of(), backend.ensureTable(kits).createdIndexes());
        assertFalse(backend.ensureTable(kits).changed());
    }

    @Test
    @DisplayName("an index already there under an operator's own name is not created again")
    void anExistingIndexUnderAnotherNameIsRecognised() throws SQLException {
        EntityModel<KitStats> kits = EntityModel.of(KitStats.class);
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE \"practice_player_stats\" ("
                    + "\"id\" VARCHAR(64) NOT NULL, \"kit_id\" VARCHAR(255), \"elo\" INTEGER,"
                    + " \"season\" INTEGER, PRIMARY KEY (\"id\"))");
            // What an operator would have written by hand, or an older release
            // under a name that has since changed.
            statement.execute("CREATE INDEX \"leaderboard\""
                    + " ON \"practice_player_stats\" (\"kit_id\" ASC, \"elo\" DESC)");
        }
        // Recognised by its columns, not by its name. A name-only comparison
        // would build a second B-tree over the same two columns, which costs a
        // write on every insert and answers nothing the first does not.
        assertEquals(List.of("idx_practice_player_stats_season_kit_id"),
                backend.ensureTable(kits).createdIndexes());
    }

    @Test
    @DisplayName("a stale index carrying our name but the wrong columns is replaced, not skipped")
    void aStaleIndexUnderOurNameIsNotMistakenForOurs() throws SQLException {
        EntityModel<KitStats> kits = EntityModel.of(KitStats.class);
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE \"practice_player_stats\" ("
                    + "\"id\" VARCHAR(64) NOT NULL, \"kit_id\" VARCHAR(255), \"elo\" INTEGER,"
                    + " \"season\" INTEGER, PRIMARY KEY (\"id\"))");
            // Our generated name, but over the wrong column: a release whose
            // @Index listed something else. Skipping on a name match would leave
            // the table permanently without the index the code now asks for,
            // silently, forever.
            statement.execute("CREATE INDEX \"idx_practice_player_stats_kit_id_elo\""
                    + " ON \"practice_player_stats\" (\"season\" ASC)");
        }
        var report = backend.ensureTable(kits);
        // It cannot be created while the stale one holds the name, and the report
        // says so instead of claiming it. H2's IF NOT EXISTS quietly does nothing
        // here, so a report built on "the statement did not throw" would announce
        // an index that does not exist — on every start, forever.
        assertFalse(report.createdIndexes().contains("idx_practice_player_stats_kit_id_elo"));
        assertEquals(List.of("idx_practice_player_stats_kit_id_elo"), report.blockedIndexes());
        assertTrue(report.blocked().contains("different columns"), report.blocked());
        // The one it can create, it did.
        assertEquals(List.of("idx_practice_player_stats_season_kit_id"), report.createdIndexes());
        assertEquals(List.of("season"),
                indexColumns("practice_player_stats", "idx_practice_player_stats_kit_id_elo"));
    }

    @Test
    @DisplayName("a wider index already there covers a narrower one that was asked for")
    void aWiderIndexCoversANarrowerRequest() throws SQLException {
        @Table("wide_index")
        @Index(columns = {"kit_id", "elo"}, descending = {"elo"})
        record Narrow(
                @Id String id,
                @Column("kit_id") String kitId,
                @Column int elo,
                @Column int wins) {
        }
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE \"wide_index\" ("
                    + "\"id\" VARCHAR(64) NOT NULL, \"kit_id\" VARCHAR(255), \"elo\" INTEGER,"
                    + " \"wins\" INTEGER, PRIMARY KEY (\"id\"))");
            statement.execute("CREATE INDEX \"operator_wide\""
                    + " ON \"wide_index\" (\"kit_id\" ASC, \"elo\" DESC, \"wins\" DESC)");
        }
        // A key on (kit_id, elo, wins) answers a filter on kit_id sorted by elo
        // exactly as well as one on (kit_id, elo) does. Creating the shorter one
        // would pay for a second B-tree on every insert to answer nothing new.
        assertEquals(List.of(), backend.ensureTable(EntityModel.of(Narrow.class)).createdIndexes());
    }

    @Test
    @DisplayName("a unique index is not considered covered by a plain one over the same columns")
    void aPlainIndexDoesNotCoverAUniqueRequest() throws SQLException {
        @Table("needs_unique")
        @Index(columns = {"season", "kit_id"}, unique = true)
        record NeedsUnique(
                @Id String id,
                @Column("kit_id") String kitId,
                @Column int season) {
        }
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE \"needs_unique\" ("
                    + "\"id\" VARCHAR(64) NOT NULL, \"kit_id\" VARCHAR(255), \"season\" INTEGER,"
                    + " PRIMARY KEY (\"id\"))");
            statement.execute("CREATE INDEX \"plain\" ON \"needs_unique\" (\"season\", \"kit_id\")");
        }
        // Uniqueness is a constraint, not an optimisation. Treating a plain index
        // as covering it would silently drop the guarantee the record asked for
        // and let in the duplicate row it was written to refuse.
        assertEquals(List.of("idx_needs_unique_season_kit_id"),
                backend.ensureTable(EntityModel.of(NeedsUnique.class)).createdIndexes());
    }

    @Test
    @DisplayName("the engine actually uses the composite index for a leaderboard query")
    void theCompositeIndexIsUsed() throws SQLException {
        EntityModel<KitStats> kits = EntityModel.of(KitStats.class);
        backend.ensureTable(kits);
        List<KitStats> rows = new ArrayList<>();
        for (int elo = 0; elo < 500; elo++) {
            rows.add(new KitStats("p" + elo, elo % 2 == 0 ? "boxing" : "nodebuff", elo, elo));
        }
        backend.saveAll(kits, rows);

        // EXPLAIN, because the point of the whole feature is not that the answer
        // is right — a table scan gives the right answer too — but that the
        // engine reads the index instead of sorting the table. A test that only
        // checked the rows would pass with no index at all.
        String plan;
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet explained = statement.executeQuery(
                     "EXPLAIN SELECT \"id\" FROM \"practice_player_stats\""
                             + " WHERE \"kit_id\" = 'boxing' ORDER BY \"elo\" DESC LIMIT 10")) {
            assertTrue(explained.next());
            plan = explained.getString(1).toLowerCase();
        }
        assertTrue(plan.contains("idx_practice_player_stats_kit_id_elo"), plan);

        // And the answer is the one the index is ordered for.
        List<KitStats> top = backend.select(kits, List.of("kit_id"), List.of("boxing"),
                List.of(Dialect.Sort.desc("elo")), 10, 0);
        assertEquals(498, top.get(0).elo());
        assertEquals(480, top.get(9).elo());
    }

    /**
     * The columns one index covers, in key order, read from the metadata.
     *
     * <p>{@code getIndexInfo} returns one row per column with an
     * {@code ORDINAL_POSITION}, which is what makes a composite index
     * recognisable as itself rather than as a name.
     */
    private List<String> indexColumns(String table, String index) throws SQLException {
        java.util.SortedMap<Integer, String> columns = new java.util.TreeMap<>();
        try (Connection connection = connect();
             ResultSet found = connection.getMetaData()
                     .getIndexInfo(null, null, table, false, false)) {
            while (found.next()) {
                String name = found.getString("INDEX_NAME");
                String column = found.getString("COLUMN_NAME");
                if (name != null && column != null && name.equalsIgnoreCase(index)) {
                    columns.put(found.getInt("ORDINAL_POSITION"), column.toLowerCase());
                }
            }
        }
        return List.copyOf(columns.values());
    }

    @Test
    @DisplayName("an underscore in a table name is a name, not a LIKE wildcard")
    void underscoreIsNotAWildcard() throws SQLException {
        // getTables and getColumns take patterns, not names, so player_stats
        // also matches playerXstats. A decoy table with the columns the real
        // one is missing: without an exact-name filter, the missing columns
        // look present and are never added, and every later read of them
        // fails on a column that does not exist.
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE \"playerxstats\" ("
                    + "\"uuid\" VARCHAR(36) NOT NULL, \"clan\" VARCHAR(32), \"notes\" TEXT,"
                    + " \"tags\" VARCHAR(255), PRIMARY KEY (\"uuid\"))");
            statement.execute("CREATE TABLE \"player_stats\" ("
                    + "\"uuid\" VARCHAR(36) NOT NULL, \"elo\" INTEGER, \"kill_streak\" INTEGER,"
                    + " \"playtime\" BIGINT, \"ratio\" DOUBLE PRECISION, \"accuracy\" REAL,"
                    + " \"banned\" BOOLEAN, \"rank\" VARCHAR(255), \"balance\" DECIMAL(38,10),"
                    + " PRIMARY KEY (\"uuid\"))");
        }
        assertEquals(List.of("clan", "notes", "tags"), backend.ensureTable(model).addedColumns());

        // And the real table genuinely has them now.
        UUID uuid = UUID.randomUUID();
        backend.save(model, stats(uuid, 1200, "red"));
        assertEquals("red", backend.find(model, uuid).clan());
    }

    @Test
    @DisplayName("the report says nothing when nothing changed")
    void reportSaysNothingWhenNothingChanged() throws SQLException {
        // A server that has been running for months changes nothing on any
        // start; a line per table per boot is how a startup log stops being
        // read at all.
        assertNotNull(backend.ensureTable(model).summary());
        assertNull(backend.ensureTable(model).summary());
    }

    @Test
    @DisplayName("a column the record gained is added to a live table, with its rows intact")
    void addsMissingColumn() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            // The table as an older version of the plugin left it: no clan, no
            // notes, no tags — and a row already in it.
            statement.execute("CREATE TABLE \"player_stats\" ("
                    + "\"uuid\" VARCHAR(36) NOT NULL, \"elo\" INTEGER, \"kill_streak\" INTEGER,"
                    + " \"playtime\" BIGINT, \"ratio\" DOUBLE PRECISION, \"accuracy\" REAL,"
                    + " \"banned\" BOOLEAN, \"rank\" VARCHAR(255), \"balance\" DECIMAL(38,10),"
                    + " PRIMARY KEY (\"uuid\"))");
            statement.execute("INSERT INTO \"player_stats\" VALUES"
                    + " ('00000000-0000-0000-0000-000000000001', 1200, 3, 900, 1.5, 0.25, FALSE, 'VIP', 12.34)");
        }
        var report = backend.ensureTable(model);
        assertFalse(report.createdTable());
        assertEquals(List.of("clan", "notes", "tags"), report.addedColumns());

        // The pre-existing row still reads, with the new columns absent rather
        // than the row being unreadable.
        Stats found = backend.find(model, UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertNotNull(found);
        assertEquals(1200, found.elo());
        assertNull(found.clan());
        assertEquals(List.of(), found.tags());
    }

    // ----------------------------------------------------------------- write

    @Test
    @DisplayName("saving the same key twice updates the row instead of duplicating it")
    void upsertUpdates() throws SQLException {
        backend.ensureTable(model);
        UUID uuid = UUID.randomUUID();

        backend.save(model, stats(uuid, 1200, "red"));
        backend.save(model, stats(uuid, 1450, "blue"));

        // The whole point of an upsert, and the one thing a MERGE written
        // wrong gets wrong: two rows, both half right.
        assertEquals(1L, backend.count(model, List.of(), List.of()));
        Stats found = backend.find(model, uuid);
        assertNotNull(found);
        assertEquals(1450, found.elo());
        assertEquals("blue", found.clan());
    }

    @Test
    @DisplayName("a full record round-trips through every column type")
    void roundTrip() throws SQLException {
        backend.ensureTable(model);
        UUID uuid = UUID.randomUUID();
        Stats written = new Stats(uuid, 1337, 9, 123456789L, 2.75d, 0.125f, true,
                "exylia", Rank.MVP, new BigDecimal("99.9900000000"),
                "a long note", List.of("x", "y", "z"));

        backend.save(model, written);
        Stats read = backend.find(model, uuid);

        assertNotNull(read);
        assertEquals(written, read);
    }

    @Test
    @DisplayName("a float keeps 32-bit precision and a double keeps 64-bit")
    void numericPrecision() throws SQLException {
        backend.ensureTable(model);
        UUID uuid = UUID.randomUUID();
        // Neither value is representable in the other width. A column emitted
        // as FLOAT would be 4 bytes on MySQL and 8 on H2, so a ratio written on
        // one engine and read on the other would not compare equal.
        float accuracy = 0.1234567f;
        double ratio = 0.12345678901234567d;
        backend.save(model, new Stats(uuid, 0, 0, 0L, ratio, accuracy, false, null,
                Rank.DEFAULT, BigDecimal.ZERO.setScale(10), null, List.of()));

        Stats read = backend.find(model, uuid);
        assertNotNull(read);
        assertEquals(accuracy, read.accuracy());
        assertEquals(ratio, read.ratio());
    }

    @Test
    @DisplayName("an unbounded column round-trips a value no VARCHAR would hold")
    void unboundedText() throws SQLException {
        backend.ensureTable(model);
        UUID uuid = UUID.randomUUID();
        // Longer than the 255 a bounded column defaults to and longer than the
        // 768 an index would allow: this is a serialised inventory's size.
        String payload = "inventory:".repeat(20_000);
        assertTrue(payload.length() > 100_000);

        backend.save(model, new Stats(uuid, 0, 0, 0L, 0d, 0f, false, null, Rank.DEFAULT,
                BigDecimal.ZERO.setScale(10), payload, List.of()));

        Stats read = backend.find(model, uuid);
        assertNotNull(read);
        assertEquals(payload, read.notes());
    }

    @Test
    @DisplayName("a text column declared CLOB reads back as its text, not as a wrapper")
    void clobColumnsMaterialise() throws SQLException {
        // The reason reads go through getString and not getObject. H2 2.2.224
        // maps TEXT to CHARACTER VARYING, so the table this library creates
        // would not catch it — but a table created by an older plugin, or by
        // hand, has a real CLOB, and getObject hands that back as a JdbcClob
        // whose toString is "org.h2.jdbc.JdbcClob@1a2b". The row would decode
        // into a record holding that string and nothing would report it.
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE \"player_stats\" ("
                    + "\"uuid\" VARCHAR(36) NOT NULL, \"elo\" INTEGER, \"kill_streak\" INTEGER,"
                    + " \"playtime\" BIGINT, \"ratio\" DOUBLE PRECISION, \"accuracy\" REAL,"
                    + " \"banned\" BOOLEAN, \"clan\" VARCHAR(32), \"rank\" VARCHAR(255),"
                    + " \"balance\" DECIMAL(38,10), \"notes\" CLOB, \"tags\" CLOB,"
                    + " PRIMARY KEY (\"uuid\"))");
        }
        backend.ensureTable(model);
        UUID uuid = UUID.randomUUID();
        String payload = "inventory:".repeat(5_000);
        backend.save(model, new Stats(uuid, 0, 0, 0L, 0d, 0f, false, null, Rank.DEFAULT,
                BigDecimal.ZERO.setScale(10), payload, List.of("a", "b")));

        Stats read = backend.find(model, uuid);
        assertNotNull(read);
        assertEquals(payload, read.notes());
        assertEquals(List.of("a", "b"), read.tags());
    }

    @Test
    @DisplayName("null in a nullable column stays null rather than becoming a zero or a literal")
    void nullsSurvive() throws SQLException {
        backend.ensureTable(model);
        UUID uuid = UUID.randomUUID();
        backend.save(model, new Stats(uuid, 0, 0, 0L, 0d, 0f, false, null, Rank.DEFAULT,
                BigDecimal.ZERO.setScale(10), null, List.of()));

        Stats read = backend.find(model, uuid);
        assertNotNull(read);
        assertNull(read.clan());
        assertNull(read.notes());
        assertEquals(0L, backend.count(model, List.of("clan"), java.util.Collections.singletonList(null)));
    }

    @Test
    @DisplayName("a batch writes every row in one transaction")
    void batch() throws SQLException {
        backend.ensureTable(model);
        List<Stats> rows = new ArrayList<>();
        for (int index = 0; index < 250; index++) {
            rows.add(stats(UUID.randomUUID(), 1000 + index, "clan" + (index % 5)));
        }
        assertEquals(250, backend.saveAll(model, rows));
        assertEquals(250L, backend.count(model, List.of(), List.of()));

        // And a batch of the same keys updates rather than duplicating.
        assertEquals(250, backend.saveAll(model, rows));
        assertEquals(250L, backend.count(model, List.of(), List.of()));
    }

    @Test
    @DisplayName("a missing key finds nothing rather than throwing")
    void findMissing() throws SQLException {
        backend.ensureTable(model);
        assertNull(backend.find(model, UUID.randomUUID()));
    }

    @Test
    @DisplayName("delete removes the row, and says whether there was one")
    void delete() throws SQLException {
        backend.ensureTable(model);
        UUID uuid = UUID.randomUUID();
        backend.save(model, stats(uuid, 1200, "red"));

        assertTrue(backend.delete(model, uuid));
        assertFalse(backend.delete(model, uuid));
        assertNull(backend.find(model, uuid));
    }

    // ------------------------------------------------------------------ read

    @Test
    @DisplayName("pagination returns each page once, with no row seen twice or missed")
    void pagination() throws SQLException {
        backend.ensureTable(model);
        List<Stats> rows = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            rows.add(stats(UUID.randomUUID(), 1000 + index, "red"));
        }
        backend.saveAll(model, rows);

        List<Integer> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            List<Stats> found = backend.select(model, List.of(), List.of(),
                    List.of(Dialect.Sort.desc("elo")), 10, page * 10);
            assertEquals(page == 2 ? 5 : 10, found.size(), "page " + page);
            found.forEach(row -> seen.add(row.elo()));
        }
        assertEquals(25, seen.size());
        assertEquals(25, seen.stream().distinct().count());
        // Descending, so the first page is the top of the leaderboard.
        assertEquals(1024, seen.get(0));
        assertEquals(1000, seen.get(24));
    }

    @Test
    @DisplayName("ordering is by the column's own type, not by its text")
    void ordering() throws SQLException {
        backend.ensureTable(model);
        // 9 sorts after 100 lexicographically and before it numerically. A
        // schema that stored elo as text — which is what happens when every
        // column goes through a codec — would answer this backwards.
        backend.save(model, stats(UUID.randomUUID(), 9, "a"));
        backend.save(model, stats(UUID.randomUUID(), 100, "b"));
        backend.save(model, stats(UUID.randomUUID(), 20, "c"));

        List<Stats> ascending = backend.select(model, List.of(), List.of(),
                List.of(Dialect.Sort.asc("elo")), 0, 0);
        assertEquals(List.of(9, 20, 100), ascending.stream().map(Stats::elo).toList());

        List<Stats> descending = backend.select(model, List.of(), List.of(),
                List.of(Dialect.Sort.desc("elo")), 0, 0);
        assertEquals(List.of(100, 20, 9), descending.stream().map(Stats::elo).toList());
    }

    @Test
    @DisplayName("a filter is bound and encoded through the column that stores it")
    void filtering() throws SQLException {
        backend.ensureTable(model);
        UUID first = UUID.randomUUID();
        backend.save(model, stats(first, 1200, "red"));
        backend.save(model, stats(UUID.randomUUID(), 1300, "blue"));
        backend.save(model, stats(UUID.randomUUID(), 1400, "red"));

        assertEquals(2, backend.select(model, List.of("clan"), List.of("red"),
                List.of(), 0, 0).size());
        assertEquals(2L, backend.count(model, List.of("clan"), List.of("red")));

        // A UUID filter passed as a UUID object: it has to be encoded exactly
        // as its column was, or it matches nothing and reports it as an empty
        // result rather than as an error.
        assertEquals(1, backend.select(model, List.of("uuid"), List.of(first),
                List.of(), 0, 0).size());
    }

    @Test
    @DisplayName("an enum filter matches, because it goes through the same codec as the column")
    void enumFilter() throws SQLException {
        backend.ensureTable(model);
        backend.save(model, stats(UUID.randomUUID(), 1200, "red"));
        assertEquals(1L, backend.count(model, List.of("rank"), List.of(Rank.VIP)));
        assertEquals(0L, backend.count(model, List.of("rank"), List.of(Rank.DEFAULT)));
    }

    @Test
    @DisplayName("a filter can name the record component as well as the column")
    void filterByComponentName() throws SQLException {
        backend.ensureTable(model);
        backend.save(model, stats(UUID.randomUUID(), 1200, "red"));
        // The column is kill_streak; the caller thinks in killStreak.
        assertEquals(1L, backend.count(model, List.of("killStreak"), List.of(3)));
    }

    @Test
    @DisplayName("a value that would inject is stored as a value, not parsed as SQL")
    void injectionIsStructurallyImpossible() throws SQLException {
        backend.ensureTable(model);
        UUID uuid = UUID.randomUUID();
        // Within the column's 32 characters, so what is being tested is the
        // binding and not the length check.
        String hostile = "'; DROP TABLE x; --";
        backend.save(model, stats(uuid, 1200, hostile));

        Stats read = backend.find(model, uuid);
        assertNotNull(read);
        assertEquals(hostile, read.clan());
        assertEquals(1L, backend.count(model, List.of("clan"), List.of(hostile)));
    }

    @Test
    @DisplayName("a filter naming a column the model does not have is refused")
    void unknownFilterColumn() throws SQLException {
        backend.ensureTable(model);
        assertThrows(IllegalArgumentException.class,
                () -> backend.count(model, List.of("nonesuch"), List.of(1)));
    }

    @Test
    @DisplayName("mismatched filter columns and values are refused rather than bound wrong")
    void mismatchedFilter() throws SQLException {
        backend.ensureTable(model);
        assertThrows(IllegalArgumentException.class,
                () -> backend.select(model, List.of("clan", "elo"), List.of("red"), List.of(), 0, 0));
    }

    // ------------------------------------------------------------- lifecycle

    @Test
    @DisplayName("the backend validates a model before it is asked to store it")
    void validation() {
        @Table("wide")
        record Wide(@Id String id, @Indexed @Column(length = 2000) String tag) {
        }
        assertEquals(1, backend.validate(EntityModel.of(Wide.class)).size());
        assertEquals(List.of(), backend.validate(model));
    }

    @Test
    @DisplayName("closing the pool closes it")
    void closing() {
        assertTrue(backend.isOpen());
        backend.close();
        assertFalse(backend.isOpen());
    }

    @Test
    @DisplayName("opening against an unreachable database fails at open, not on the first query")
    void openFailsEarly() {
        // Hikari opens its first connection eagerly. Failing here means a
        // console message at enable rather than an exception on a player join.
        // H2 rather than a networked engine: only H2's driver is on the test
        // classpath, and a missing driver would pass this for the wrong reason.
        // /dev/null is a file, so the database file underneath it cannot be
        // created — H2 creates missing directories, but not inside a device.
        assertThrows(IllegalStateException.class, () -> SqlBackend.open(
                SqlSettings.file("h2", java.nio.file.Path.of("/dev/null/db")), "tests"));
    }

    @Test
    @DisplayName("a missing driver says which one and where it comes from")
    void missingDriverIsExplained() {
        // The postgres driver is compileOnly and is not on the test classpath,
        // which is exactly the shape of the failure an operator hits when the
        // plugin.yml libraries section was not updated.
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> SqlBackend.open(
                        SqlSettings.remote("postgres", "127.0.0.1", 1, "nope", "u", "p"), "tests"));
        assertTrue(failure.getMessage().contains("org.postgresql.Driver"), failure.getMessage());
        assertTrue(failure.getMessage().contains("plugin.yml"), failure.getMessage());
    }

    /** A connection outside the pool's bookkeeping, for setting a table up by hand. */
    private Connection connect() throws SQLException {
        String url = backend.dialect().jdbcUrl(
                SqlSettings.memory("h2", "test" + DATABASE.get()));
        return java.sql.DriverManager.getConnection(url, "sa", "");
    }
}
