package net.exylia.lib.database;

import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.SqlBackend;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.database.internal.SqlStorage;
import net.exylia.lib.database.internal.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The row-level seam against a real engine.
 *
 * <p>H2 in memory, not a mock. Everything worth asserting here is a property of
 * the engine rather than of the library: whether keyset pagination actually
 * sees every row exactly once, whether an explicit id survives a write into a
 * table whose key is generated, and — the one nothing else in the repo covers —
 * whether the identity counter has to be moved afterwards or moves itself. A
 * mock answers all three the way the test author expected, which is precisely
 * how ExyliaCommons shipped a paginated export that silently dropped rows.
 *
 * <p>Each test gets its own database name, so nothing leaks between them.
 */
class RowScanTest {

    private static final long TIMEOUT_SECONDS = 15L;
    private static final AtomicInteger DATABASE = new AtomicInteger();

    enum Rank { DEFAULT, VIP, MVP }

    /**
     * The source table, carrying one column of every kind that needs a codec.
     *
     * <p>A {@code UUID}, an enum, a {@code List} and an unbounded text column
     * are the four whose stored form is a {@code String} produced by something
     * other than the driver. If a scan ever decoded and re-encoded a row, this
     * is where it would show: a round trip through the codec is only lossless
     * as long as nothing in the middle reinterprets the text.
     */
    @Table("scan_source")
    record Source(
            @Id UUID uuid,
            @Column int elo,
            @Column long playtime,
            @Column double ratio,
            @Column boolean banned,
            @Column Rank rank,
            @Column BigDecimal balance,
            @Column(length = Column.UNBOUNDED) String notes,
            @Column List<String> tags) {
    }

    /** The same shape under another name: where a scanned row is written back. */
    @Table("scan_target")
    record Target(
            @Id UUID uuid,
            @Column int elo,
            @Column long playtime,
            @Column double ratio,
            @Column boolean banned,
            @Column Rank rank,
            @Column BigDecimal balance,
            @Column(length = Column.UNBOUNDED) String notes,
            @Column List<String> tags) {
    }

    /** A row whose key the engine hands out, which is what needs resequencing. */
    @Table("scan_designs")
    record Design(
            @Id(generated = true) long id,
            @Column("owner_uuid") UUID owner,
            @Column("design_json") String json) {
    }

    private SqlBackend backend;
    private EntityModel<Source> source;
    private EntityModel<Target> target;
    private EntityModel<Design> designs;

    @BeforeEach
    void open() {
        backend = SqlBackend.open(
                SqlSettings.memory("h2", "scan" + DATABASE.incrementAndGet()), "tests");
        source = EntityModel.of(Source.class);
        target = EntityModel.of(Target.class);
        designs = EntityModel.of(Design.class);
    }

    @AfterEach
    void close() {
        if (backend != null) {
            backend.close();
        }
    }

    private static Source row(UUID uuid, int elo) {
        return new Source(uuid, elo, 900L + elo, 1.5d, elo % 2 == 0, Rank.values()[elo % 3],
                new BigDecimal(elo + ".2500000000"), "note " + elo,
                List.of("a" + elo, "b" + elo));
    }

    // ------------------------------------------------------------------ scan

    @Test
    @DisplayName("a scan over more rows than one batch sees every row exactly once")
    void everyRowExactlyOnce() throws SQLException {
        backend.ensureTable(source);
        Set<UUID> written = new HashSet<>();
        List<Source> rows = new ArrayList<>();
        for (int index = 0; index < 2500; index++) {
            UUID uuid = UUID.randomUUID();
            written.add(uuid);
            rows.add(row(uuid, index));
        }
        backend.saveAll(source, rows);

        List<UUID> seen = new ArrayList<>();
        long counted = backend.scan(source, 1000,
                batch -> batch.forEach(values -> seen.add(UUID.fromString((String) values[0]))));

        // The three assertions that separate keyset from OFFSET-without-order.
        // Commons paged with LIMIT ? OFFSET ? and no ORDER BY: the engine is
        // free to return the rows in a different order per page, so a row lands
        // on two pages and another on none — and the total still looks right if
        // only the count is checked.
        assertEquals(2500L, counted, "the scan counts what it handed over");
        assertEquals(2500, seen.size(), "no row is handed over twice");
        assertEquals(written, new HashSet<>(seen), "and none is missed");
    }

    @Test
    @DisplayName("a scan over an empty table completes with zero and never calls the block")
    void emptyTableNeverCallsTheBlock() throws SQLException {
        backend.ensureTable(source);
        AtomicInteger calls = new AtomicInteger();

        assertEquals(0L, backend.scan(source, 100, batch -> calls.incrementAndGet()));

        // An empty batch handed over is not the same as no batch: a caller
        // opening a file on the first batch would create an empty one.
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("no batch is ever larger than the batch size, whatever the table holds")
    void batchesAreBounded() throws SQLException {
        backend.ensureTable(source);
        List<Source> rows = new ArrayList<>();
        for (int index = 0; index < 1001; index++) {
            rows.add(row(UUID.randomUUID(), index));
        }
        backend.saveAll(source, rows);

        List<Integer> sizes = new ArrayList<>();
        long counted = backend.scan(source, 250, batch -> sizes.add(batch.size()));

        assertEquals(1001L, counted);
        // The bound is the whole point: constant memory regardless of the
        // table. A scan that read everything and chunked it afterwards would
        // pass every other test here and still be a heap of serialised
        // inventories on a real table.
        for (int size : sizes) {
            assertTrue(size <= 250, "a batch of " + size + " exceeds the bound: " + sizes);
        }
        assertEquals(1001, sizes.stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    @DisplayName("a scan of a table whose size is an exact multiple of the batch terminates")
    void anExactMultipleTerminates() throws SQLException {
        backend.ensureTable(source);
        List<Source> rows = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            rows.add(row(UUID.randomUUID(), index));
        }
        backend.saveAll(source, rows);

        // The case a >= cursor never escapes: the last full batch re-reads its
        // own final row forever. Asserted by the count rather than by a timeout,
        // because a wrong comparison also duplicates rows before it hangs.
        assertEquals(200L, backend.scan(source, 100, batch -> {
        }));
    }

    @Test
    @DisplayName("a block that throws ends the scan rather than being swallowed or hanging")
    void aThrowingBlockEndsTheScan() throws SQLException {
        backend.ensureTable(source);
        List<Source> rows = new ArrayList<>();
        for (int index = 0; index < 300; index++) {
            rows.add(row(UUID.randomUUID(), index));
        }
        backend.saveAll(source, rows);
        AtomicInteger calls = new AtomicInteger();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> backend.scan(source, 100, batch -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("the file is full");
                }));

        assertEquals("the file is full", failure.getMessage());
        // Exactly one batch: the walk stops rather than carrying on with the
        // rest of the table after the consumer has already failed.
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("a batch size of zero or less is refused, synchronously")
    void refusesAnEmptyBatch() throws SQLException {
        backend.ensureTable(source);
        assertThrows(IllegalArgumentException.class, () -> backend.scan(source, 0, batch -> {
        }));
        assertThrows(IllegalArgumentException.class, () -> backend.scan(source, -1, batch -> {
        }));
    }

    @Test
    @DisplayName("Storage refuses an empty batch on the calling thread, not through the future")
    void storageRefusesAnEmptyBatchWithoutQueueingIt() {
        // Directly on the caller's thread: a bad argument is a bug at the call
        // site, and arriving as a failed future minutes later points at the
        // background thread rather than at whoever wrote the zero.
        Storage storage = new SqlStorage(backend, Runnable::run, warning -> {
        });
        assertThrows(IllegalArgumentException.class, () -> storage.scan(source, 0, batch -> {
        }));
    }

    @Test
    @DisplayName("a block that throws completes the Storage future exceptionally")
    void storageFailsTheFutureWhenTheBlockThrows() throws SQLException {
        backend.ensureTable(source);
        backend.saveAll(source, List.of(row(UUID.randomUUID(), 1)));
        Storage storage = new SqlStorage(backend, Runnable::run, warning -> {
        });

        CompletableFuture<Long> future = storage.scan(source, 10, batch -> {
            throw new IllegalStateException("no room left");
        });

        // Not a throw from the caller's thread and not a future that never
        // completes: both would leave an export hanging with no error anywhere.
        assertTrue(future.isCompletedExceptionally());
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("no room left", failure.getCause().getMessage());
    }

    @Test
    @DisplayName("rows come back in storage form, so no codec ran on the way out")
    void rowsAreStorageForm() throws SQLException {
        backend.ensureTable(source);
        UUID uuid = UUID.randomUUID();
        backend.saveAll(source, List.of(row(uuid, 4)));

        List<Object[]> captured = new ArrayList<>();
        backend.scan(source, 10, captured::addAll);

        assertEquals(1, captured.size());
        Object[] values = captured.get(0);
        assertEquals(source.columns().size(), values.length);
        // A UUID is its text, an enum is its name, a list is its JSON. If any of
        // these came back as the Java type, a codec ran — which on an ItemStack
        // column would need a running server and would rebuild a Bukkit object
        // only to serialise it again.
        assertEquals(uuid.toString(), values[0]);
        assertEquals("VIP", values[5]);
        assertEquals("[\"a4\",\"b4\"]", values[8]);
    }

    // ------------------------------------------------------------- round trip

    @Test
    @DisplayName("scan then writeRows reproduces every record, codec columns included")
    void roundTripThroughAnotherTable() throws SQLException {
        backend.ensureTable(source);
        backend.ensureTable(target);
        List<Source> written = new ArrayList<>();
        for (int index = 0; index < 750; index++) {
            written.add(row(UUID.randomUUID(), index));
        }
        backend.saveAll(source, written);

        // Exactly what an import would do: read a batch, write that batch, keep
        // nothing. Nothing here decodes a row, so nothing could have corrupted
        // one by decoding it wrong.
        long moved = backend.scan(source, 200, batch -> {
            try {
                assertEquals(batch.size(), backend.writeRows(target, batch));
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        });

        assertEquals(750L, moved);
        assertEquals(750L, backend.count(target, List.of(), List.of()));
        for (Source original : written) {
            Target stored = backend.find(target, original.uuid());
            assertNotNull(stored, "every row arrived: " + original.uuid());
            assertSame(original, stored);
        }
    }

    @Test
    @DisplayName("a null in a nullable column stays null across the round trip")
    void nullsSurviveTheRoundTrip() throws SQLException {
        backend.ensureTable(source);
        backend.ensureTable(target);
        UUID uuid = UUID.randomUUID();
        backend.saveAll(source, List.of(new Source(uuid, 0, 0L, 0d, false, Rank.DEFAULT,
                BigDecimal.ZERO.setScale(10), null, List.of())));

        backend.scan(source, 10, batch -> {
            try {
                backend.writeRows(target, batch);
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        });

        Target stored = backend.find(target, uuid);
        assertNotNull(stored);
        // A null that became "null" or an empty string on the way through would
        // read back as a four-character note nobody wrote.
        assertNull(stored.notes());
        assertEquals(List.of(), stored.tags());
    }

    @Test
    @DisplayName("an unbounded column survives a value no VARCHAR would hold")
    void unboundedTextSurvivesTheRoundTrip() throws SQLException {
        backend.ensureTable(source);
        backend.ensureTable(target);
        UUID uuid = UUID.randomUUID();
        // A serialised inventory's size, which is the reason this path must not
        // hold a whole table in memory.
        String payload = "inventory:".repeat(20_000);
        backend.saveAll(source, List.of(new Source(uuid, 0, 0L, 0d, false, Rank.DEFAULT,
                BigDecimal.ZERO.setScale(10), payload, List.of())));

        backend.scan(source, 10, batch -> {
            try {
                backend.writeRows(target, batch);
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        });

        assertEquals(payload, backend.find(target, uuid).notes());
    }

    @Test
    @DisplayName("writeRows of nothing is no round trip and no rows")
    void writingNothingWritesNothing() throws SQLException {
        backend.ensureTable(target);
        assertEquals(0, backend.writeRows(target, List.of()));
        assertEquals(0L, backend.count(target, List.of(), List.of()));
    }

    // ------------------------------------------------------- generated keys

    @Test
    @DisplayName("an explicitly written generated id is the id that comes back")
    void explicitGeneratedIdSurvives() throws SQLException {
        backend.ensureTable(designs);
        UUID owner = UUID.randomUUID();
        List<Object[]> rows = List.of(
                designs.values(new Design(41L, owner, "{\"a\":1}")),
                designs.values(new Design(97L, owner, "{\"a\":2}")));

        assertEquals(2, backend.writeRows(designs, rows));

        // The engine does not get to renumber them. An import that let it would
        // break every row anywhere else that referenced the old id.
        Design first = backend.find(designs, 41L);
        assertNotNull(first, "the row is stored under the id it was given");
        assertEquals(41L, first.id());
        assertEquals("{\"a\":1}", first.json());
        assertEquals(97L, backend.find(designs, 97L).id());
    }

    @Test
    @DisplayName("after writeRows and resequence, a generated insert does not collide")
    void resequenceMovesTheCounterPastTheImportedKeys() throws SQLException {
        backend.ensureTable(designs);
        UUID owner = UUID.randomUUID();
        List<Object[]> rows = new ArrayList<>();
        for (long id = 1L; id <= 500L; id++) {
            rows.add(designs.values(new Design(id, owner, "row" + id)));
        }
        backend.writeRows(designs, rows);

        // The whole point. H2 does not advance its identity counter for a row
        // that supplied its own key, so without this the next insert asks for 1
        // — a key the table already holds — and fails on the primary key.
        assertEquals(501L, backend.resequence(designs));

        long fresh = backend.insert(designs, new Design(0L, owner, "after"));
        assertTrue(fresh > 500L, "the new key must be past every imported one, got " + fresh);
        assertEquals(501L, backend.count(designs, List.of(), List.of()));
        assertEquals("after", backend.find(designs, fresh).json());
    }

    @Test
    @DisplayName("resequence is a no-op for a record that brings its own key")
    void resequenceIgnoresANaturalKey() throws SQLException {
        backend.ensureTable(source);
        backend.saveAll(source, List.of(row(UUID.randomUUID(), 1)));
        // There is no counter to move, and an ALTER against a VARCHAR key is a
        // statement no engine would accept.
        assertEquals(0L, backend.resequence(source));
    }

    @Test
    @DisplayName("resequence on an empty table does nothing")
    void resequenceOnAnEmptyTable() throws SQLException {
        backend.ensureTable(designs);
        assertEquals(0L, backend.resequence(designs));
        // And the counter is untouched, so the first insert still starts at one.
        assertEquals(1L, backend.insert(designs, new Design(0L, UUID.randomUUID(), "first")));
    }

    /** Every component of a source row against the target row it became. */
    private static void assertSame(Source original, Target stored) {
        assertEquals(original.uuid(), stored.uuid());
        assertEquals(original.elo(), stored.elo());
        assertEquals(original.playtime(), stored.playtime());
        assertEquals(original.ratio(), stored.ratio());
        assertEquals(original.banned(), stored.banned());
        // The codec columns. An enum written as its ordinal, a UUID re-encoded,
        // or a list re-serialised would all show up here and nowhere else.
        assertEquals(original.rank(), stored.rank());
        assertEquals(original.balance(), stored.balance());
        assertEquals(original.notes(), stored.notes());
        assertEquals(original.tags(), stored.tags());
        assertFalse(stored.tags().isEmpty());
    }
}
