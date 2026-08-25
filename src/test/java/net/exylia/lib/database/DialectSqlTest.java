package net.exylia.lib.database;

import net.exylia.lib.database.internal.Dialect;
import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.IndexModel;
import net.exylia.lib.database.internal.SqlSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exact SQL each engine is sent.
 *
 * <p>Only H2 can be executed in a build, so the other three are asserted as
 * strings — against the statements a live probe accepted. Assertions are exact
 * and not "contains", on purpose: every rule here is a rule because a nearly
 * right statement either failed to parse or, worse, parsed and did the wrong
 * thing. A test that only checked for {@code ON DUPLICATE KEY} would pass while
 * MySQL logged a deprecation on every write and MariaDB refused to start.
 */
class DialectSqlTest {

    enum Rank { DEFAULT, VIP }

    @Table("player_stats")
    record Stats(
            @Id UUID uuid,
            @Column int elo,
            @Column("kill_streak") int killStreak,
            @Column long playtime,
            @Column double ratio,
            @Column float accuracy,
            @Column boolean banned,
            @Column byte tier,
            @Column short season,
            @Indexed @Column(length = 32) String clan,
            @Column Rank rank,
            @Column BigDecimal balance,
            @Column(length = Column.UNBOUNDED) String notes) {
    }

    /** A table that is nothing but its key: the degenerate upsert. */
    @Table("seen")
    record Seen(@Id UUID uuid) {
    }

    /**
     * The real leaderboard shape, copied from ExyliaPracticeCore.
     *
     * <p>Filter by kit, sort by elo descending. Twelve indexes like this one sit
     * on that table in production, and it is the case a single-column index
     * cannot answer at all: two separate indexes do not combine, a database uses
     * one of them.
     */
    @Table("practice_player_stats")
    @Index(columns = {"kit_id", "elo"}, descending = {"elo"})
    @Index(columns = {"kit_id", "wins"}, descending = {"wins"}, name = "idx_ps_kit_wins")
    @Index(columns = {"season", "kit_id"}, unique = true)
    record KitStats(
            @Id String id,
            @Column("kit_id") String kitId,
            @Column int elo,
            @Column int wins,
            @Indexed @Column int season) {
    }

    private static final EntityModel<Stats> STATS = EntityModel.of(Stats.class);
    private static final EntityModel<Seen> SEEN = EntityModel.of(Seen.class);
    private static final EntityModel<KitStats> KITS = EntityModel.of(KitStats.class);

    /** The single-column index {@code Stats} asks for, via {@code @Indexed}. */
    private static IndexModel clanIndex() {
        return STATS.indexes().get(0);
    }

    /** The composite {@code (kit_id ASC, elo DESC)} leaderboard index. */
    private static IndexModel leaderboardIndex() {
        for (IndexModel index : KITS.indexes()) {
            if (index.columns().equals(List.of("kit_id", "elo"))) {
                return index;
            }
        }
        throw new AssertionError("the leaderboard index was not compiled: " + KITS.indexes());
    }

    private static final Dialect H2 = Dialect.of("h2");
    private static final Dialect MYSQL = Dialect.of("mysql");
    private static final Dialect MARIADB = Dialect.of("mariadb");
    private static final Dialect POSTGRES = Dialect.of("postgres");

    private static final String KEY = "uuid";

    // ------------------------------------------------------------ identifiers

    @Test
    @DisplayName("every identifier is folded to lower case and always quoted")
    void identifiersAreFoldedAndQuoted() {
        assertEquals("displayname", H2.identifier("displayName"));
        assertEquals("displayname", MYSQL.identifier("displayName"));

        assertEquals("\"elo\"", H2.quote("elo"));
        assertEquals("\"elo\"", POSTGRES.quote("elo"));
        // Backticks: a double quote is a string literal on MySQL unless
        // ANSI_QUOTES is in sql_mode, which is not ours to assume.
        assertEquals("`elo`", MYSQL.quote("elo"));
        assertEquals("`elo`", MARIADB.quote("elo"));
    }

    @Test
    @DisplayName("a quote inside an identifier is escaped, not passed through")
    void quotesAreEscaped() {
        assertEquals("\"we\"\"ird\"", H2.quote("we\"ird"));
        assertEquals("`we``ird`", MYSQL.quote("we`ird"));
    }

    @Test
    @DisplayName("an index name is stable and short enough for every engine")
    void indexNames() {
        assertEquals("idx_player_stats_clan", H2.indexName(clanIndex()));
        assertEquals("idx_practice_player_stats_kit_id_elo", H2.indexName(leaderboardIndex()));
        // Every dialect spells the name the same way. It has to be identical, or
        // a model prepared against MySQL and then against Postgres would create
        // two indexes over the same columns.
        for (Dialect dialect : List.of(H2, MYSQL, MARIADB, POSTGRES)) {
            assertEquals("idx_practice_player_stats_kit_id_elo", dialect.indexName(leaderboardIndex()));
        }
    }

    @Test
    @DisplayName("a generated name too long for an identifier is truncated with a stable hash")
    void longIndexNamesAreTruncated() {
        // Postgres truncates at 63 bytes and MySQL at 64. Truncating alone is
        // not enough: two long column lists on one table would collapse into the
        // same name, and the second CREATE INDEX would then fail as "already
        // exists" — precisely the error the schema code is written to forgive.
        // The index would simply never be created and nothing would say so.
        List<IndexModel.Part> first = List.of(
                IndexModel.Part.asc("a_very_long_column_name_number_one"),
                IndexModel.Part.desc("a_very_long_column_name_number_two"));
        List<IndexModel.Part> second = List.of(
                IndexModel.Part.asc("a_very_long_column_name_number_one"),
                IndexModel.Part.desc("a_very_long_column_name_number_three"));
        String table = "practice_player_statistics_history";

        String one = IndexModel.derivedName(table, first);
        String two = IndexModel.derivedName(table, second);

        assertTrue(one.length() <= 60, one);
        assertTrue(two.length() <= 60, two);
        // The whole point of the suffix: two names that truncate to the same
        // prefix are still two names.
        assertNotEquals(one, two);
        assertEquals(one.substring(0, 40), two.substring(0, 40));
        // Stable across calls, because the schema layer recognises an existing
        // index by the name it would generate. A name that moved between starts
        // would create a second index every time.
        assertEquals(one, IndexModel.derivedName(table, first));
        assertTrue(one.startsWith("idx_" + table));
    }

    @Test
    @DisplayName("a name given on the annotation is used verbatim, however long the columns are")
    void explicitNamesWin() {
        // The reason the annotation has a name at all: matching an index that
        // already exists in a live database, so it is recognised rather than
        // created a second time under a generated name.
        for (IndexModel index : KITS.indexes()) {
            if (index.columns().equals(List.of("kit_id", "wins"))) {
                assertEquals("idx_ps_kit_wins", index.name());
                return;
            }
        }
        throw new AssertionError("the named index was not compiled: " + KITS.indexes());
    }

    // ------------------------------------------------------------------ types

    @Test
    @DisplayName("float is REAL and double is DOUBLE PRECISION, never FLOAT")
    void floatIsNeverFloat() {
        for (Dialect dialect : List.of(H2, MYSQL, MARIADB, POSTGRES)) {
            // FLOAT is 8 bytes on H2 and Postgres and 4 on MySQL, so a schema
            // written with the word silently changes precision on migration.
            assertEquals("REAL", dialect.columnType(STATS.column("accuracy")),
                    dialect.id() + " must store a float as REAL");
            assertEquals("DOUBLE PRECISION", dialect.columnType(STATS.column("ratio")),
                    dialect.id() + " must store a double as DOUBLE PRECISION");
        }
    }

    @Test
    @DisplayName("a UUID is VARCHAR(36) on every engine, including the one with a uuid type")
    void uuidIsAlwaysText() {
        for (Dialect dialect : List.of(H2, MYSQL, MARIADB, POSTGRES)) {
            // Postgres has a native uuid type and it rejects setString; MySQL
            // has none at all. Text is the only shared representation.
            assertEquals("VARCHAR(36)", dialect.columnType(STATS.column("uuid")), dialect.id());
        }
    }

    @Test
    @DisplayName("boolean is BOOLEAN except on MySQL and MariaDB")
    void booleans() {
        assertEquals("BOOLEAN", H2.columnType(STATS.column("banned")));
        assertEquals("BOOLEAN", POSTGRES.columnType(STATS.column("banned")));
        assertEquals("TINYINT(1)", MYSQL.columnType(STATS.column("banned")));
        assertEquals("TINYINT(1)", MARIADB.columnType(STATS.column("banned")));
    }

    @Test
    @DisplayName("unbounded text takes each engine's widest type")
    void unboundedText() {
        assertEquals("TEXT", H2.columnType(STATS.column("notes")));
        assertEquals("TEXT", POSTGRES.columnType(STATS.column("notes")));
        // Not TEXT on MySQL: that is 65535 bytes, and utf8mb4 makes it ~16k
        // characters — a serialised inventory overruns it and is truncated.
        assertEquals("LONGTEXT", MYSQL.columnType(STATS.column("notes")));
        assertEquals("LONGTEXT", MARIADB.columnType(STATS.column("notes")));
    }

    @Test
    @DisplayName("a byte widens to SMALLINT on Postgres, which has no TINYINT")
    void tinyInt() {
        assertEquals("TINYINT", H2.columnType(STATS.column("tier")));
        assertEquals("TINYINT", MYSQL.columnType(STATS.column("tier")));
        assertEquals("SMALLINT", POSTGRES.columnType(STATS.column("tier")));
    }

    @Test
    @DisplayName("a codec column is text, sized by the annotation")
    void codecColumnsAreText() {
        assertEquals("VARCHAR(255)", H2.columnType(STATS.column("rank")));
        assertEquals("VARCHAR(32)", H2.columnType(STATS.column("clan")));
        assertEquals("DECIMAL(38,10)", H2.columnType(STATS.column("balance")));
        assertEquals("SMALLINT", H2.columnType(STATS.column("season")));
        assertEquals("BIGINT", H2.columnType(STATS.column("playtime")));
    }

    // --------------------------------------------------------------- upserts

    @Nested
    @DisplayName("upsert")
    class Upserts {

        @Test
        @DisplayName("H2 merges on an explicit key")
        void h2() {
            assertEquals("MERGE INTO \"player_stats\" (\"uuid\", \"elo\", \"kill_streak\","
                            + " \"playtime\", \"ratio\", \"accuracy\", \"banned\", \"tier\","
                            + " \"season\", \"clan\", \"rank\", \"balance\", \"notes\")"
                            + " KEY (\"uuid\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    H2.upsert(STATS, List.of(KEY)));
        }

        @Test
        @DisplayName("MySQL uses the 8.0.20 row alias, which MariaDB cannot parse")
        void mysql() {
            assertEquals("INSERT INTO `player_stats` (`uuid`, `elo`, `kill_streak`, `playtime`,"
                            + " `ratio`, `accuracy`, `banned`, `tier`, `season`, `clan`, `rank`,"
                            + " `balance`, `notes`)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                            + " AS new ON DUPLICATE KEY UPDATE"
                            + " `elo` = new.`elo`, `kill_streak` = new.`kill_streak`,"
                            + " `playtime` = new.`playtime`, `ratio` = new.`ratio`,"
                            + " `accuracy` = new.`accuracy`, `banned` = new.`banned`,"
                            + " `tier` = new.`tier`, `season` = new.`season`,"
                            + " `clan` = new.`clan`, `rank` = new.`rank`,"
                            + " `balance` = new.`balance`, `notes` = new.`notes`",
                    MYSQL.upsert(STATS, List.of(KEY)));
        }

        @Test
        @DisplayName("MariaDB uses VALUES(col), which MySQL 8.0.20 deprecates")
        void mariadb() {
            assertEquals("INSERT INTO `player_stats` (`uuid`, `elo`, `kill_streak`, `playtime`,"
                            + " `ratio`, `accuracy`, `banned`, `tier`, `season`, `clan`, `rank`,"
                            + " `balance`, `notes`)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                            + " ON DUPLICATE KEY UPDATE"
                            + " `elo` = VALUES(`elo`), `kill_streak` = VALUES(`kill_streak`),"
                            + " `playtime` = VALUES(`playtime`), `ratio` = VALUES(`ratio`),"
                            + " `accuracy` = VALUES(`accuracy`), `banned` = VALUES(`banned`),"
                            + " `tier` = VALUES(`tier`), `season` = VALUES(`season`),"
                            + " `clan` = VALUES(`clan`), `rank` = VALUES(`rank`),"
                            + " `balance` = VALUES(`balance`), `notes` = VALUES(`notes`)",
                    MARIADB.upsert(STATS, List.of(KEY)));
        }

        @Test
        @DisplayName("the two MySQL forks never produce the same statement")
        void mysqlAndMariadbDiffer() {
            // The whole reason there are two classes. If this ever passes by
            // accident, one of the two engines is being sent SQL it cannot run.
            assertFalse(MYSQL.upsert(STATS, List.of(KEY)).equals(MARIADB.upsert(STATS, List.of(KEY))));
            assertFalse(MARIADB.upsert(STATS, List.of(KEY)).contains(" AS new"));
            assertFalse(MYSQL.upsert(STATS, List.of(KEY)).contains("VALUES(`elo`)"));
        }

        @Test
        @DisplayName("Postgres names the conflict target, which is not optional there")
        void postgres() {
            assertEquals("INSERT INTO \"player_stats\" (\"uuid\", \"elo\", \"kill_streak\","
                            + " \"playtime\", \"ratio\", \"accuracy\", \"banned\", \"tier\","
                            + " \"season\", \"clan\", \"rank\", \"balance\", \"notes\")"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                            + " ON CONFLICT (\"uuid\") DO UPDATE SET"
                            + " \"elo\" = EXCLUDED.\"elo\", \"kill_streak\" = EXCLUDED.\"kill_streak\","
                            + " \"playtime\" = EXCLUDED.\"playtime\", \"ratio\" = EXCLUDED.\"ratio\","
                            + " \"accuracy\" = EXCLUDED.\"accuracy\", \"banned\" = EXCLUDED.\"banned\","
                            + " \"tier\" = EXCLUDED.\"tier\", \"season\" = EXCLUDED.\"season\","
                            + " \"clan\" = EXCLUDED.\"clan\", \"rank\" = EXCLUDED.\"rank\","
                            + " \"balance\" = EXCLUDED.\"balance\", \"notes\" = EXCLUDED.\"notes\"",
                    POSTGRES.upsert(STATS, List.of(KEY)));
        }

        @Test
        @DisplayName("every dialect binds one placeholder per column, and never twice")
        void placeholderCount() {
            for (Dialect dialect : List.of(H2, MYSQL, MARIADB, POSTGRES)) {
                long placeholders = dialect.upsert(STATS, List.of(KEY)).chars()
                        .filter(character -> character == '?').count();
                // A form that repeated the values in the update clause would
                // need 25 here, and a caller binding 13 would fail at runtime
                // with a message about parameter counts and nothing else.
                assertEquals(STATS.columns().size(), placeholders, dialect.id());
            }
        }

        @Test
        @DisplayName("a key-only table still produces a legal statement")
        void keyOnlyTable() {
            // An empty SET is a syntax error on MySQL, so the key is assigned
            // to itself; Postgres has DO NOTHING for exactly this.
            assertEquals("INSERT INTO `seen` (`uuid`) VALUES (?)"
                            + " ON DUPLICATE KEY UPDATE `uuid` = `uuid`",
                    MYSQL.upsert(SEEN, List.of(KEY)));
            assertEquals("INSERT INTO `seen` (`uuid`) VALUES (?)"
                            + " ON DUPLICATE KEY UPDATE `uuid` = `uuid`",
                    MARIADB.upsert(SEEN, List.of(KEY)));
            assertEquals("INSERT INTO \"seen\" (\"uuid\") VALUES (?) ON CONFLICT (\"uuid\") DO NOTHING",
                    POSTGRES.upsert(SEEN, List.of(KEY)));
            assertEquals("MERGE INTO \"seen\" (\"uuid\") KEY (\"uuid\") VALUES (?)",
                    H2.upsert(SEEN, List.of(KEY)));
        }

        @Test
        @DisplayName("an upsert without key columns is refused, not guessed")
        void keysAreRequired() {
            for (Dialect dialect : List.of(H2, MYSQL, MARIADB, POSTGRES)) {
                assertThrows(IllegalArgumentException.class,
                        () -> dialect.upsert(STATS, List.of()), dialect.id());
            }
        }
    }

    // ------------------------------------------------------------------- DDL

    @Test
    @DisplayName("CREATE TABLE, per engine")
    void createTable() {
        assertEquals("CREATE TABLE IF NOT EXISTS \"player_stats\" ("
                        + "\"uuid\" VARCHAR(36) NOT NULL, \"elo\" INTEGER, \"kill_streak\" INTEGER,"
                        + " \"playtime\" BIGINT, \"ratio\" DOUBLE PRECISION, \"accuracy\" REAL,"
                        + " \"banned\" BOOLEAN, \"tier\" TINYINT, \"season\" SMALLINT,"
                        + " \"clan\" VARCHAR(32), \"rank\" VARCHAR(255), \"balance\" DECIMAL(38,10),"
                        + " \"notes\" TEXT, PRIMARY KEY (\"uuid\"))",
                H2.createTable(STATS));

        assertEquals("CREATE TABLE IF NOT EXISTS `player_stats` ("
                        + "`uuid` VARCHAR(36) NOT NULL, `elo` INTEGER, `kill_streak` INTEGER,"
                        + " `playtime` BIGINT, `ratio` DOUBLE PRECISION, `accuracy` REAL,"
                        + " `banned` TINYINT(1), `tier` TINYINT, `season` SMALLINT,"
                        + " `clan` VARCHAR(32), `rank` VARCHAR(255), `balance` DECIMAL(38,10),"
                        + " `notes` LONGTEXT, PRIMARY KEY (`uuid`))"
                        + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
                MYSQL.createTable(STATS));

        assertEquals("CREATE TABLE IF NOT EXISTS \"player_stats\" ("
                        + "\"uuid\" VARCHAR(36) NOT NULL, \"elo\" INTEGER, \"kill_streak\" INTEGER,"
                        + " \"playtime\" BIGINT, \"ratio\" DOUBLE PRECISION, \"accuracy\" REAL,"
                        + " \"banned\" BOOLEAN, \"tier\" SMALLINT, \"season\" SMALLINT,"
                        + " \"clan\" VARCHAR(32), \"rank\" VARCHAR(255), \"balance\" DECIMAL(38,10),"
                        + " \"notes\" TEXT, PRIMARY KEY (\"uuid\"))",
                POSTGRES.createTable(STATS));
    }

    @Test
    @DisplayName("CREATE INDEX carries IF NOT EXISTS everywhere except MySQL")
    void createIndex() {
        assertEquals("CREATE INDEX IF NOT EXISTS \"idx_player_stats_clan\""
                        + " ON \"player_stats\" (\"clan\" ASC)",
                H2.createIndex("player_stats", clanIndex()));
        assertEquals("CREATE INDEX IF NOT EXISTS \"idx_player_stats_clan\""
                        + " ON \"player_stats\" (\"clan\" ASC)",
                POSTGRES.createIndex("player_stats", clanIndex()));
        assertEquals("CREATE INDEX IF NOT EXISTS `idx_player_stats_clan`"
                        + " ON `player_stats` (`clan` ASC)",
                MARIADB.createIndex("player_stats", clanIndex()));
        // MySQL: IF NOT EXISTS on an index is a syntax error, verified. The
        // guard has to be a metadata lookup or a swallowed 1061 instead.
        assertEquals("CREATE INDEX `idx_player_stats_clan` ON `player_stats` (`clan` ASC)",
                MYSQL.createIndex("player_stats", clanIndex()));

        assertFalse(MYSQL.supportsCreateIndexIfNotExists());
        assertTrue(H2.supportsCreateIndexIfNotExists());
        assertTrue(MARIADB.supportsCreateIndexIfNotExists());
        assertTrue(POSTGRES.supportsCreateIndexIfNotExists());
    }

    @Test
    @DisplayName("a composite index names every column in order, each with its direction")
    void createCompositeIndex() {
        // The statement the whole feature exists for. All four engines accept a
        // per-column ASC | DESC in the key part — checked against each one's own
        // grammar — and the direction is what makes this index answer "top ten
        // of this kit by elo" by reading ten rows instead of sorting the kit.
        assertEquals("CREATE INDEX IF NOT EXISTS \"idx_practice_player_stats_kit_id_elo\""
                        + " ON \"practice_player_stats\" (\"kit_id\" ASC, \"elo\" DESC)",
                H2.createIndex("practice_player_stats", leaderboardIndex()));
        assertEquals("CREATE INDEX IF NOT EXISTS \"idx_practice_player_stats_kit_id_elo\""
                        + " ON \"practice_player_stats\" (\"kit_id\" ASC, \"elo\" DESC)",
                POSTGRES.createIndex("practice_player_stats", leaderboardIndex()));
        assertEquals("CREATE INDEX IF NOT EXISTS `idx_practice_player_stats_kit_id_elo`"
                        + " ON `practice_player_stats` (`kit_id` ASC, `elo` DESC)",
                MARIADB.createIndex("practice_player_stats", leaderboardIndex()));
        assertEquals("CREATE INDEX `idx_practice_player_stats_kit_id_elo`"
                        + " ON `practice_player_stats` (`kit_id` ASC, `elo` DESC)",
                MYSQL.createIndex("practice_player_stats", leaderboardIndex()));
    }

    @Test
    @DisplayName("the column order is the annotation's, and reversing it is a different statement")
    void compositeColumnOrderIsPreserved() {
        // Not a stylistic assertion. An index on (kit_id, elo) answers a filter
        // on kit_id sorted by elo; one on (elo, kit_id) answers neither, because
        // a B-tree is ordered by its first column. Sorting the columns for
        // tidiness — or reading them off the record — would build the wrong index
        // and the only symptom would be a slow server.
        IndexModel reversed = new IndexModel("idx_reversed",
                List.of(IndexModel.Part.desc("elo"), IndexModel.Part.asc("kit_id")), false);
        assertEquals("CREATE INDEX IF NOT EXISTS \"idx_reversed\""
                        + " ON \"practice_player_stats\" (\"elo\" DESC, \"kit_id\" ASC)",
                H2.createIndex("practice_player_stats", reversed));
    }

    @Test
    @DisplayName("a unique composite index is CREATE UNIQUE INDEX")
    void createUniqueCompositeIndex() {
        IndexModel unique = null;
        for (IndexModel index : KITS.indexes()) {
            if (index.unique() && index.composite()) {
                unique = index;
            }
        }
        assertNotNull(unique, "the unique composite index was not compiled: " + KITS.indexes());

        assertEquals("CREATE UNIQUE INDEX IF NOT EXISTS \"idx_practice_player_stats_season_kit_id\""
                        + " ON \"practice_player_stats\" (\"season\" ASC, \"kit_id\" ASC)",
                H2.createIndex("practice_player_stats", unique));
        assertEquals("CREATE UNIQUE INDEX IF NOT EXISTS \"idx_practice_player_stats_season_kit_id\""
                        + " ON \"practice_player_stats\" (\"season\" ASC, \"kit_id\" ASC)",
                POSTGRES.createIndex("practice_player_stats", unique));
        assertEquals("CREATE UNIQUE INDEX IF NOT EXISTS"
                        + " `idx_practice_player_stats_season_kit_id`"
                        + " ON `practice_player_stats` (`season` ASC, `kit_id` ASC)",
                MARIADB.createIndex("practice_player_stats", unique));
        assertEquals("CREATE UNIQUE INDEX `idx_practice_player_stats_season_kit_id`"
                        + " ON `practice_player_stats` (`season` ASC, `kit_id` ASC)",
                MYSQL.createIndex("practice_player_stats", unique));
    }

    @Test
    @DisplayName("a truncated index name is quoted and folded exactly as it will be stored")
    void createIndexWithATruncatedName() {
        List<IndexModel.Part> parts = List.of(
                IndexModel.Part.asc("a_very_long_column_name_number_one"),
                IndexModel.Part.desc("a_very_long_column_name_number_two"));
        String table = "practice_player_statistics_history";
        IndexModel index = new IndexModel(IndexModel.derivedName(table, parts), parts, false);

        String sql = MYSQL.createIndex(table, index);
        assertEquals("CREATE INDEX `" + index.name() + "` ON `" + table + "`"
                        + " (`a_very_long_column_name_number_one` ASC,"
                        + " `a_very_long_column_name_number_two` DESC)",
                sql);
        // The name in the statement must be the same one the schema layer looks
        // for in the metadata, or every start creates the index again.
        assertEquals(index.name(), MYSQL.indexName(index));
    }

    @Test
    @DisplayName("ALTER TABLE ADD COLUMN never emits NOT NULL")
    void addColumn() {
        // A table with rows cannot gain a non-null column without a default:
        // Postgres refuses the statement and MySQL invents 0 or '' for every
        // existing row, which is the same corruption without the warning.
        @Table("t")
        record Required(@Id String id, @Column(nullable = false) String name) {
        }
        EntityModel<Required> model = EntityModel.of(Required.class);
        assertEquals("ALTER TABLE \"t\" ADD COLUMN \"name\" VARCHAR(255)",
                H2.addColumn("t", model.column("name")));
        assertEquals("ALTER TABLE `t` ADD COLUMN `name` VARCHAR(255)",
                MYSQL.addColumn("t", model.column("name")));
        // ...while a table created from scratch does get the constraint.
        assertTrue(H2.createTable(model).contains("\"name\" VARCHAR(255) NOT NULL"));
    }

    // ------------------------------------------------------------------- DML

    @Test
    @DisplayName("pagination is LIMIT ? OFFSET ? on all four, with both bound")
    void pagination() {
        for (Dialect dialect : List.of(H2, MYSQL, MARIADB, POSTGRES)) {
            String sql = dialect.select(STATS, List.of(), List.of(Dialect.Sort.desc("elo")), 10, 40);
            // MySQL rejects OFFSET .. FETCH and Postgres rejects LIMIT ?,? —
            // this is the one form all four parse.
            assertTrue(sql.endsWith(" LIMIT ? OFFSET ?"), dialect.id() + ": " + sql);
            // The page number is bound, so page 1 and page 90 are the same
            // string and the driver's statement cache actually hits.
            assertFalse(sql.contains("40"), dialect.id());
            assertFalse(sql.contains("10"), dialect.id());
        }
    }

    @Test
    @DisplayName("SELECT lists columns in model order, filters and orders")
    void select() {
        assertEquals("SELECT \"uuid\", \"elo\" FROM \"totals\" WHERE \"clan\" = ?"
                        + " AND \"banned\" = ? ORDER BY \"elo\" DESC, \"uuid\" LIMIT ? OFFSET ?",
                H2.select(EntityModel.of(Totals.class), List.of("clan", "banned"),
                        List.of(Dialect.Sort.desc("elo"), Dialect.Sort.asc("uuid")), 25, 0));
    }

    @Table("totals")
    record Totals(@Id UUID uuid, @Column int elo) {
    }

    @Test
    @DisplayName("an offset without a limit is refused rather than faked")
    void offsetNeedsLimit() {
        // MySQL has no bare OFFSET, and the usual workaround is asking for
        // 18446744073709551615 rows. Better to say no.
        assertThrows(IllegalArgumentException.class,
                () -> H2.select(STATS, List.of(), List.of(), 0, 10));
    }

    @Test
    @DisplayName("INSERT, DELETE and COUNT")
    void otherStatements() {
        EntityModel<Totals> model = EntityModel.of(Totals.class);
        assertEquals("INSERT INTO \"totals\" (\"uuid\", \"elo\") VALUES (?, ?)", H2.insert(model));
        assertEquals("DELETE FROM \"totals\" WHERE \"uuid\" = ?", H2.delete(model, List.of("uuid")));
        assertEquals("SELECT COUNT(*) FROM \"totals\"", H2.count(model, List.of()));
        assertEquals("SELECT COUNT(*) FROM \"totals\" WHERE \"elo\" = ?", H2.count(model, List.of("elo")));
    }

    @Test
    @DisplayName("a DELETE with no condition is refused, not emitted")
    void deleteNeedsCondition() {
        assertThrows(IllegalArgumentException.class,
                () -> H2.delete(EntityModel.of(Totals.class), List.of()));
    }

    // ------------------------------------------------------------ validation

    @Test
    @DisplayName("an indexed text column above 768 characters is reported")
    void indexedTextIsLimited() {
        @Table("wide")
        record Wide(@Id String id, @Indexed @Column(length = 1000) String tag) {
        }
        EntityModel<Wide> model = EntityModel.of(Wide.class);
        for (Dialect dialect : List.of(H2, MYSQL, MARIADB, POSTGRES)) {
            List<String> problems = dialect.validate(model);
            // MySQL refuses the index outright; MariaDB builds a 768-character
            // prefix index instead and says nothing.
            assertEquals(1, problems.size(), dialect.id() + ": " + problems);
            assertTrue(problems.get(0).contains("wide.tag"), problems.get(0));
            assertTrue(problems.get(0).contains("768"), problems.get(0));
        }
    }

    @Test
    @DisplayName("a unique text column above the limit says uniqueness stops being enforced")
    void uniqueTextSaysWhatBreaks() {
        @Table("wide_unique")
        record WideUnique(@Id String id, @Column(length = 900, unique = true) String tag) {
        }
        String problem = MARIADB.validate(EntityModel.of(WideUnique.class)).get(0);
        assertTrue(problem.contains("uniqueness"), problem);
    }

    @Test
    @DisplayName("an indexed unbounded text column is reported")
    void indexedUnboundedIsReported() {
        @Table("blobby")
        record Blobby(@Id String id, @Indexed @Column(length = Column.UNBOUNDED) String payload) {
        }
        List<String> problems = MYSQL.validate(EntityModel.of(Blobby.class));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("LONGTEXT"), problems.get(0));
    }

    @Test
    @DisplayName("two columns that differ only in case are reported, not silently merged")
    void caseOnlyClash() {
        @Table("clashing")
        record Clashing(@Id String id, @Column("Name") String upper, @Column("name") String lower) {
        }
        List<String> problems = H2.validate(EntityModel.of(Clashing.class));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("differ only in case"), problems.get(0));
    }

    @Test
    @DisplayName("a model within the limits reports nothing")
    void cleanModelIsClean() {
        for (Dialect dialect : List.of(H2, MYSQL, MARIADB, POSTGRES)) {
            assertEquals(List.of(), dialect.validate(STATS), dialect.id());
        }
    }

    // -------------------------------------------------------------- JDBC URLs

    @Test
    @DisplayName("MySQL's URL carries the parameters that are not optional")
    void mysqlUrl() {
        String url = MYSQL.jdbcUrl(SqlSettings.remote("mysql", "db.local", 0, "practice", "u", "p"));
        assertEquals("jdbc:mysql://db.local:3306/practice"
                        + "?rewriteBatchedStatements=true"      // 8.8x on a batch, off by default
                        + "&characterEncoding=UTF-8"            // a Java encoding name; utf8mb4 is not one
                        + "&connectionTimeZone=SERVER"          // serverTimezone is deprecated
                        + "&sslMode=PREFERRED"                  // useSSL is deprecated
                        + "&allowPublicKeyRetrieval=true",      // caching_sha2_password needs it
                url);
    }

    @Test
    @DisplayName("MariaDB's URL is not MySQL's, because its driver rejects some of them")
    void mariadbUrl() {
        String url = MARIADB.jdbcUrl(SqlSettings.remote("mariadb", "db.local", 3307, "practice", "u", "p"));
        assertEquals("jdbc:mariadb://db.local:3307/practice"
                        + "?rewriteBatchedStatements=true&characterEncoding=UTF-8", url);
        assertFalse(url.contains("allowPublicKeyRetrieval"));
        assertFalse(url.contains("sslMode"));
    }

    @Test
    @DisplayName("Postgres batching is spelled with a capital W or it is silently ignored")
    void postgresUrl() {
        String url = POSTGRES.jdbcUrl(SqlSettings.remote("postgres", "db.local", 0, "practice", "u", "p"));
        assertTrue(url.startsWith("jdbc:postgresql://db.local:5432/practice?"), url);
        // reWriteBatchedInserts. A lower-case "rewrite" is accepted, ignored,
        // and produces no warning: the batching win simply disappears.
        assertTrue(url.contains("reWriteBatchedInserts=true"), url);
    }

    @Test
    @DisplayName("H2's URL uses DB_CLOSE_DELAY and never the flag that conflicts with AUTO_SERVER")
    void h2Url() {
        String url = H2.jdbcUrl(SqlSettings.file("h2", Path.of("/srv/plugins/Practice/data")));
        assertTrue(url.startsWith("jdbc:h2:file:/srv/plugins/Practice/data;"), url);
        assertTrue(url.contains("DB_CLOSE_DELAY=-1"), url);
        // AUTO_SERVER=TRUE and DB_CLOSE_ON_EXIT=FALSE are mutually exclusive:
        // H2 throws at connect, so a server configured with both never starts.
        assertFalse(url.contains("AUTO_SERVER"), url);
        assertFalse(url.contains("DB_CLOSE_ON_EXIT"), url);
    }

    @Test
    @DisplayName("an operator's own URL parameter wins over the library's")
    void operatorParametersWin() {
        String url = MYSQL.jdbcUrl(SqlSettings.remote("mysql", "h", 0, "d", "u", "p")
                .property("sslMode", "REQUIRED"));
        assertTrue(url.contains("sslMode=REQUIRED"), url);
        assertFalse(url.contains("sslMode=PREFERRED"), url);
    }

    @Test
    @DisplayName("the driver class is named rather than discovered")
    void driverClasses() {
        // DriverManager's service loader walks the thread context classloader,
        // which under plugin classloaders is whoever happens to be calling.
        assertEquals("org.h2.Driver", H2.driverClassName());
        assertEquals("com.mysql.cj.jdbc.Driver", MYSQL.driverClassName());
        assertEquals("org.mariadb.jdbc.Driver", MARIADB.driverClassName());
        assertEquals("org.postgresql.Driver", POSTGRES.driverClassName());
    }

    @Test
    @DisplayName("H2 gets a tiny pool that never recycles; a networked engine does not")
    void poolProfiles() {
        Dialect.PoolProfile h2 = H2.poolProfile(SqlSettings.memory("h2", "x"));
        assertTrue(h2.maximumPoolSize() <= 4, "embedded pools stay small");
        // Recycling an embedded connection can tear down and re-open the
        // database file, and there is no network in between to time out.
        assertEquals(0L, h2.maxLifetimeMillis());

        Dialect.PoolProfile mysql = MYSQL.poolProfile(SqlSettings.remote("mysql", "h", 0, "d", "u", "p"));
        assertTrue(mysql.maxLifetimeMillis() > 0, "a networked connection must expire before a firewall kills it");
        assertEquals(16, MYSQL.poolProfile(
                SqlSettings.remote("mysql", "h", 0, "d", "u", "p").poolSize(16)).maximumPoolSize());
    }

    @Test
    @DisplayName("an unknown engine names the ones that exist")
    void unknownEngine() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Dialect.of("sqlite"));
        assertTrue(failure.getMessage().contains("mariadb"), failure.getMessage());
    }

    // ------------------------------------------------------- generated keys

    /** A row whose key the engine hands out. */
    @Table("designs")
    record Design(@Id(generated = true) long id, @Column("owner_uuid") UUID owner) {
    }

    private static final EntityModel<Design> DESIGNS = EntityModel.of(Design.class);

    @Test
    @DisplayName("each engine declares a generated key the way that engine spells it")
    void generatedKeyDdl() {
        // Postgres has never had AUTO_INCREMENT, and SERIAL leaves a sequence
        // behind when the column is dropped. Getting this wrong is a table that
        // never gets created, on one engine only.
        assertTrue(H2.createTable(DESIGNS).contains("AUTO_INCREMENT"), H2.createTable(DESIGNS));
        assertTrue(MYSQL.createTable(DESIGNS).contains("AUTO_INCREMENT"), MYSQL.createTable(DESIGNS));
        assertTrue(MARIADB.createTable(DESIGNS).contains("AUTO_INCREMENT"), MARIADB.createTable(DESIGNS));

        String postgres = POSTGRES.createTable(DESIGNS);
        assertTrue(postgres.contains("GENERATED BY DEFAULT AS IDENTITY"), postgres);
        assertFalse(postgres.contains("AUTO_INCREMENT"), postgres);
    }

    @Test
    @DisplayName("only the generated column is declared generated")
    void onlyTheKeyIsGenerated() {
        // One occurrence, not one per column: appending the keyword to every
        // column parses on no engine at all.
        String sql = H2.createTable(DESIGNS);
        assertEquals(1, sql.split("AUTO_INCREMENT", -1).length - 1, sql);
        assertFalse(H2.createTable(STATS).contains("AUTO_INCREMENT"),
                "a record that brings its own key declares nothing");
    }

    @Test
    @DisplayName("the insert of a generated key leaves the key column out")
    void generatedInsertOmitsTheKey() {
        String sql = H2.insertGenerated(DESIGNS);

        assertTrue(sql.contains("owner_uuid"), sql);
        // The key must not be bound at all. Binding it as null is accepted by
        // MySQL and refused by Postgres against a NOT NULL identity column, so
        // it would work until the day a server moved between them.
        assertFalse(sql.contains("\"id\""), sql);
        assertEquals(1, sql.split("\\?", -1).length - 1, "one placeholder per written column");
    }

    @Test
    @DisplayName("a plain insert still writes every column")
    void plainInsertIsUnchanged() {
        // The existing statement is untouched: a record that brings its own key
        // writes it, and that is most tables in the ecosystem.
        String sql = H2.insert(STATS);
        assertTrue(sql.contains("uuid"), sql);
        assertEquals(STATS.columns().size(), sql.split("\\?", -1).length - 1);
    }
}
