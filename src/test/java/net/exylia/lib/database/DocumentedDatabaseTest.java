package net.exylia.lib.database;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.internal.Dialect;
import net.exylia.lib.database.internal.EntityModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claims {@code docs/database.md} makes, checked against what the code does.
 *
 * <p>Documentation about a database is a promise somebody designs a schema
 * around. The table of stored types, the dialect traps and the engine
 * differences are the parts that would be expensive to get wrong, so they are
 * executed rather than asserted in prose.
 */
class DocumentedDatabaseTest {

    private static final String[] ENGINES = {"h2", "mysql", "mariadb", "postgresql"};

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
    }

    @Table("doc_types")
    record Types(
            @Id UUID id,
            @Column int anInt,
            @Column long aLong,
            @Column double aDouble,
            @Column float aFloat,
            @Column short aShort,
            @Column byte aByte,
            @Column boolean aBoolean,
            @Column String bounded,
            @Column(length = Column.UNBOUNDED) String unbounded,
            @Column java.math.BigDecimal money,
            @Column Mode mode) {

        enum Mode { FIRST, SECOND }
    }

    @Test
    @DisplayName("\"every type in the table is stored, and unsupported ones are a registration error\"")
    void theTypeTable() {
        // The doc's own table. A type silently stored as toString() is the
        // failure this replaces: it reads back as a String and the record
        // constructor throws days later.
        EntityModel<Types> model = EntityModel.of(Types.class);
        assertEquals(12, model.columns().size(), "every documented type is a column");

        assertThrows(IllegalArgumentException.class,
                () -> EntityModel.of(Unsupported.class),
                "the doc says an unsupported type fails at registration");
    }

    @Table("doc_unsupported")
    record Unsupported(@Id String id, @Column Thread notStorable) {
    }

    @Test
    @DisplayName("\"an enum is stored by its name, never the ordinal\"")
    void enumsByName() {
        // Reordering an enum's constants is a normal refactor; with ordinals it
        // reinterprets every stored row as a different value.
        assertEquals("SECOND", EntityModel.of(Types.class)
                .valuesByName(sample(Types.Mode.SECOND)).get("mode"));
    }

    @Test
    @DisplayName("\"a UUID is stored as VARCHAR(36) on every engine\"")
    void uuidStorage() {
        // Postgres has a native uuid type and setString against it throws;
        // MySQL has no uuid type at all. VARCHAR(36) is also what every row
        // already in a database holds.
        for (String engine : ENGINES) {
            String create = Dialect.of(engine).createTable(EntityModel.of(Types.class));
            assertTrue(create.contains("VARCHAR(36)"),
                    engine + " should store a UUID as VARCHAR(36): " + create);
        }
    }

    @Test
    @DisplayName("\"FLOAT is 4 bytes on MySQL and 8 elsewhere, so REAL and DOUBLE PRECISION are emitted\"")
    void neverFloat() {
        // The trap that truncates a double on MySQL only, so it passes every
        // test run against H2 or Postgres.
        for (String engine : ENGINES) {
            String create = Dialect.of(engine).createTable(EntityModel.of(Types.class));
            assertFalse(create.contains("FLOAT"), engine + " must not emit FLOAT: " + create);
            assertTrue(create.contains("REAL"), engine + " should emit REAL for a float");
            assertTrue(create.contains("DOUBLE PRECISION"),
                    engine + " should emit DOUBLE PRECISION for a double");
        }
    }

    @Test
    @DisplayName("\"money is DECIMAL, because a double cannot hold a balance\"")
    void moneyIsDecimal() {
        for (String engine : ENGINES) {
            assertTrue(Dialect.of(engine).createTable(EntityModel.of(Types.class))
                            .contains("DECIMAL(38,10)"),
                    engine + " should store a BigDecimal as DECIMAL");
        }
    }

    @Test
    @DisplayName("\"MySQL cannot parse MariaDB's upsert, and the other way round\"")
    void upsertsDiverge() {
        // The doc claims these two cannot share a string. MySQL 8.0.20+
        // deprecates VALUES() and wants AS new; MariaDB refuses to parse AS new.
        EntityModel<Types> model = EntityModel.of(Types.class);
        List<String> key = List.of(model.id().name());

        String mysql = Dialect.of("mysql").upsert(model, key);
        String mariadb = Dialect.of("mariadb").upsert(model, key);

        assertTrue(mysql.contains("AS new"), "MySQL: " + mysql);
        assertFalse(mariadb.contains("AS new"), "MariaDB cannot parse it: " + mariadb);
        assertTrue(mariadb.contains("VALUES("), "MariaDB: " + mariadb);
    }

    @Test
    @DisplayName("\"Postgres needs a conflict target; omitting it is a hard error\"")
    void postgresConflictTarget() {
        EntityModel<Types> model = EntityModel.of(Types.class);
        String sql = Dialect.of("postgresql").upsert(model, List.of(model.id().name()));

        assertTrue(sql.contains("ON CONFLICT (\"id\")"), sql);
        assertTrue(sql.contains("EXCLUDED"), "Postgres names the new row EXCLUDED: " + sql);
    }

    @Test
    @DisplayName("\"H2 upserts with MERGE INTO ... KEY\"")
    void h2Merge() {
        EntityModel<Types> model = EntityModel.of(Types.class);
        String sql = Dialect.of("h2").upsert(model, List.of(model.id().name()));

        assertTrue(sql.startsWith("MERGE INTO"), sql);
        assertTrue(sql.contains("KEY (\"id\")"), sql);
    }

    @Test
    @DisplayName("\"MySQL rejects CREATE INDEX IF NOT EXISTS; the other three accept it\"")
    void indexIfNotExists() {
        assertFalse(Dialect.of("mysql").supportsCreateIndexIfNotExists(),
                "MySQL 8 does not support it — verified syntax error");
        for (String engine : new String[] {"h2", "mariadb", "postgresql"}) {
            assertTrue(Dialect.of(engine).supportsCreateIndexIfNotExists(), engine);
        }
    }

    @Test
    @DisplayName("\"everything is quoted lowercase, so one name works on all five engines\"")
    void identifiersAreQuoted() {
        // H2 uppercases unquoted identifiers and Postgres lowercases them, so an
        // unquoted name refers to a different column depending on the engine.
        assertEquals("\"player_stats\"", Dialect.of("h2").quote("player_stats"));
        assertEquals("\"player_stats\"", Dialect.of("postgresql").quote("player_stats"));
        assertEquals("`player_stats`", Dialect.of("mysql").quote("player_stats"));
        assertEquals("`player_stats`", Dialect.of("mariadb").quote("player_stats"));
    }

    @Test
    @DisplayName("\"an indexed VARCHAR above 768 characters is refused\"")
    void wideIndexIsRefused() {
        // Above it MySQL errors and MariaDB silently degrades to a prefix index,
        // where a UNIQUE constraint then stops enforcing uniqueness.
        for (String engine : new String[] {"mysql", "mariadb"}) {
            assertFalse(Dialect.of(engine).validate(EntityModel.of(WideIndex.class)).isEmpty(),
                    engine + " should refuse an index on a 1000-character column");
        }
    }

    @Table("doc_wide")
    record WideIndex(@Id String id, @Indexed @Column(length = 1000) String wide) {
    }

    @Test
    @DisplayName("\"a composite index is one index, in order, not two separate ones\"")
    void compositeIndex() {
        // (kit_id, elo DESC) is already in a leaderboard's order, so the
        // database reads ten rows and stops. Two single-column indexes are not
        // the same thing: a database uses one of them.
        EntityModel<Leaderboard> model = EntityModel.of(Leaderboard.class);
        assertEquals(1, model.indexes().size());

        String sql = Dialect.of("h2").createIndex(model.table(), model.indexes().getFirst());
        assertTrue(sql.contains("\"kit_id\" ASC"), sql);
        assertTrue(sql.contains("\"elo\" DESC"), sql);
        assertTrue(sql.indexOf("kit_id") < sql.indexOf("elo"), "the order is the whole point");
    }

    @Table("doc_leaderboard")
    @Index(columns = {"kit_id", "elo"}, descending = {"elo"})
    record Leaderboard(@Id String id,
                       @Column("kit_id") String kitId,
                       @Column int elo) {
    }

    @Test
    @DisplayName("\"@Indexed and @Index produce one list, not two mechanisms\"")
    void oneIndexList() {
        // The schema layer iterates exactly one thing; a second mechanism is how
        // one of them ends up unimplemented on an engine.
        EntityModel<BothKinds> withIndexed = EntityModel.of(BothKinds.class);
        assertEquals(2, withIndexed.indexes().size(),
                "one from @Indexed and one from @Index");
    }

    @Table("doc_both")
    @Index(columns = {"a", "b"})
    record BothKinds(@Id String id, @Indexed @Column String a, @Column String b) {
    }

    @Test
    @DisplayName("\"a limited page is LIMIT ? OFFSET ?, bound rather than inlined\"")
    void pagination() {
        // MySQL rejects OFFSET..FETCH and Postgres rejects LIMIT ?,?, so one
        // portable form is the only option. Bound so page 1 and page 90 reuse
        // the same prepared statement.
        for (String engine : ENGINES) {
            String page = Dialect.of(engine).page(10, 20);
            assertTrue(page.contains("LIMIT ?"), engine + ": " + page);
            assertTrue(page.contains("OFFSET ?"), engine + ": " + page);
        }
    }

    private static Types sample(Types.Mode mode) {
        return new Types(UUID.randomUUID(), 1, 2L, 3.0, 4.0f, (short) 5, (byte) 6, true,
                "bounded", "unbounded", java.math.BigDecimal.ONE, mode);
    }
}
