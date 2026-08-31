package net.exylia.lib.database;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.database.transfer.TableTransfer;
import net.exylia.lib.database.transfer.TransferOutcome;
import net.exylia.lib.database.transfer.TransferReport;
import net.exylia.lib.database.transfer.Transfers;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A plugin's whole database, out to a file and back in, against a real engine.
 *
 * <p>H2 in memory, never a mock. Every property worth asserting here belongs to
 * the round trip through a real driver and a real gzip stream: whether a
 * {@code BigDecimal} that no {@code double} can hold survives, whether a null
 * stays distinguishable from an empty string once it has been through JSON and
 * back through a column that reads empty text as absent, and whether an
 * identity counter that was never moved actually collides. A mock answers all
 * three the way the test author expected, which is exactly how ExyliaCommons
 * shipped an importer that reported success while losing rows.
 *
 * <p>Each test gets its own database name and its own temporary folder, so they
 * run in any order and leave nothing behind.
 */
class TransferTest {

    private static final long TIMEOUT_SECONDS = 30L;
    private static final AtomicInteger DATABASE = new AtomicInteger();

    enum Rank { DEFAULT, VIP, MVP }

    /**
     * The real shape of a player table: one column of every kind whose stored
     * form is text produced by something other than the driver, plus a decimal
     * and a column that is genuinely null on some rows.
     */
    @Table("transfer_stats")
    record Stats(
            @Id UUID uuid,
            @Column int elo,
            @Column long playtime,
            @Column double ratio,
            @Column boolean banned,
            @Column Rank rank,
            @Column BigDecimal balance,
            @Column(length = 32) String clan,
            @Column(length = Column.UNBOUNDED) String notes,
            @Column List<String> tags) {
    }

    /** A second table, so a dump carries more than one and their order matters. */
    @Table("transfer_kits")
    record Kit(@Id String id, @Column("display") String display, @Column int cost) {
    }

    /** A table whose key the engine hands out, which is what needs resequencing. */
    @Table("transfer_designs")
    record Design(
            @Id(generated = true) long id,
            @Column("owner_uuid") UUID owner,
            @Column("design_json") String json) {
    }

    private Plugin plugin;
    private Repository<Stats> stats;
    private Repository<Kit> kits;

    @TempDir
    Path folder;

    @BeforeAll
    static void server() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        // The transfer runs on a real background thread, exactly as it does on
        // a server: a test that ran it inline would never see the future's
        // ordering, which is half of what makes an import safe.
        FakeServer.runAsyncForReal();
        plugin = FakeServer.newPlugin("Practice");
        Databases.installForTests(plugin,
                SqlSettings.memory("h2", "transfer" + DATABASE.incrementAndGet()));
        Transfers.init(plugin);
        stats = Databases.of(plugin).repository(Stats.class);
        kits = Databases.of(plugin).repository(Kit.class);
    }

    @AfterEach
    void close() {
        Transfers.releaseAll();
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for a transfer", interrupted);
        } catch (ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new AssertionError("A transfer did not complete", failure);
        }
    }

    private static Stats row(int index) {
        return new Stats(UUID.randomUUID(), 1000 + index, 900L + index, 1.5d + index,
                index % 2 == 0, Rank.values()[index % 3],
                new BigDecimal(index + ".2500000000"), "clan" + (index % 7),
                "note ".repeat(index % 5 + 1), List.of("a" + index, "b" + index));
    }

    // ------------------------------------------------------------ round trip

    @Test
    @DisplayName("every record survives an export and an import, across several batches")
    void theRoundTrip() {
        List<Stats> written = new ArrayList<>();
        // More than two batches of a thousand: a walk that only ever saw one
        // page would pass every other assertion here.
        for (int index = 0; index < 2500; index++) {
            written.add(row(index));
        }
        await(stats.saveAll(written));
        await(kits.saveAll(List.of(new Kit("boxing", "{primary}Boxing", 0),
                new Kit("nodebuff", "{primary}NoDebuff", 250))));

        TransferReport export = await(Transfers.of(plugin).export(folder));
        assertEquals(TransferOutcome.SUCCESS, export.outcome(), export.problems().toString());
        assertEquals(2502L, export.rows());
        assertEquals(List.of("transfer_kits", "transfer_stats"), export.tableNames());
        assertNotNull(export.file());
        assertTrue(Files.exists(export.file()));

        await(stats.all().delete());
        await(kits.all().delete());
        assertEquals(0L, await(stats.count()));

        TransferReport imported = await(Transfers.of(plugin).importFrom(export.file()));
        assertEquals(TransferOutcome.SUCCESS, imported.outcome(), imported.problems().toString());
        assertEquals(2502L, imported.rows());

        assertEquals(2500L, await(stats.count()));
        // Every record, not a sample: a codec that re-encoded on the way
        // through would only show on the rows whose column it touched.
        for (Stats original : written) {
            Stats back = await(stats.find(original.uuid())).orElse(null);
            assertNotNull(back, "row missing: " + original.uuid());
            assertEquals(original, back);
        }
        assertEquals("{primary}NoDebuff", await(kits.find("nodebuff")).orElseThrow().display());
    }

    @Test
    @DisplayName("a decimal no double can hold comes back exactly")
    void bigDecimalPrecisionSurvives() {
        // Not representable in a binary double: through one it becomes
        // 123456789.12345679. This is the ExyliaCommons bug in one line — its
        // importer bound every JSON number through Gson's default, which is
        // Double, so every balance it ever imported was changed.
        BigDecimal exact = new BigDecimal("123456789.123456789");
        assertNotEquals(exact, new BigDecimal(Double.toString(exact.doubleValue())),
                "the fixture must be a value a double genuinely cannot hold");
        UUID uuid = UUID.randomUUID();
        await(stats.save(new Stats(uuid, 0, 0L, 0d, false, Rank.DEFAULT, exact,
                null, null, List.of())));

        // What the column itself gives back, before any transfer. The
        // comparison is against this and not against the literal, so the test
        // measures the transfer rather than the column's own scale — H2 stores
        // it as DECIMAL(38,10) and hands it back at that scale whatever anyone
        // does with it.
        BigDecimal stored = await(stats.find(uuid)).orElseThrow().balance();

        TransferReport export = await(Transfers.of(plugin).export(folder));
        await(stats.all().delete());
        await(Transfers.of(plugin).importFrom(export.file()));

        BigDecimal back = await(stats.find(uuid)).orElseThrow().balance();
        // Exactly equal — same digits and same scale — not merely close. A
        // value that went out as a JSON number comes back as the double's own
        // rendering, 123456789.12345679, and compareTo alone would still catch
        // that; equals also catches a scale quietly changing.
        assertEquals(stored, back, "expected " + stored + " but got " + back);
        assertEquals(0, exact.compareTo(back), "the digits themselves must be untouched");
    }

    @Test
    @DisplayName("null and empty string stay different things through the round trip")
    void nullIsNotEmptyString() {
        UUID nulled = UUID.randomUUID();
        UUID empty = UUID.randomUUID();
        await(stats.save(new Stats(nulled, 0, 0L, 0d, false, Rank.DEFAULT,
                BigDecimal.ZERO.setScale(10), null, null, List.of())));
        await(stats.save(new Stats(empty, 0, 0L, 0d, false, Rank.DEFAULT,
                BigDecimal.ZERO.setScale(10), "", "", List.of())));

        TransferReport export = await(Transfers.of(plugin).export(folder));
        await(stats.all().delete());
        await(Transfers.of(plugin).importFrom(export.file()));

        Stats wasNull = await(stats.find(nulled)).orElseThrow();
        assertNull(wasNull.clan(), "a null that came back as \"\" is a value nobody wrote");
        assertNull(wasNull.notes());

        // The other direction, and the honest half: ColumnModel.decode reads an
        // empty string as absent on a codec column, and a plain String column
        // keeps it. What must not happen is the two rows becoming
        // indistinguishable in the file — which is what an omitted JSON entry
        // would do, since a row is positional.
        Stats wasEmpty = await(stats.find(empty)).orElseThrow();
        assertEquals("", wasEmpty.clan());
        assertEquals("", wasEmpty.notes());
        assertNotEquals(wasNull.clan(), wasEmpty.clan());
    }

    // --------------------------------------------------------------- refusal

    @Test
    @DisplayName("an import into a table that already holds rows is refused and writes nothing")
    void refusesANonEmptyTable() {
        await(stats.save(row(1)));
        TransferReport export = await(Transfers.of(plugin).export(folder));

        // A different row already in the table when the import is attempted.
        await(stats.all().delete());
        Stats sitting = row(2);
        await(stats.save(sitting));

        TransferReport refused = await(Transfers.of(plugin).importFrom(export.file()));

        assertEquals(TransferOutcome.FAILED, refused.outcome());
        assertEquals(0L, refused.rows(), "a refusal writes nothing at all");
        assertTrue(refused.problems().get(0).startsWith("Refused:"), refused.problems().toString());
        // Which table blocked it, and how many rows — without that, an owner
        // has to guess which of their tables is in the way.
        assertTrue(refused.problems().get(0).contains("transfer_stats"),
                refused.problems().toString());
        assertTrue(refused.problems().get(0).contains("1 rows"), refused.problems().toString());
        // And it says what force actually does, at the moment it is offered.
        assertTrue(refused.problems().get(0).contains("MERGES"), refused.problems().toString());

        // The table is exactly as it was: the refusal did not half-apply.
        assertEquals(1L, await(stats.count()));
        assertEquals(sitting, await(stats.find(sitting.uuid())).orElseThrow());
    }

    @Test
    @DisplayName("force merges: a matching key is overwritten and a row not in the dump survives")
    void forceMerges() {
        Stats exported = row(1);
        await(stats.save(exported));
        TransferReport export = await(Transfers.of(plugin).export(folder));

        // The same key with different values, plus a row the dump has never
        // heard of.
        Stats changed = new Stats(exported.uuid(), 9999, 1L, 0d, true, Rank.MVP,
                BigDecimal.ONE.setScale(10), "other", "other", List.of("z"));
        Stats untouched = row(2);
        await(stats.all().delete());
        await(stats.saveAll(List.of(changed, untouched)));

        TransferReport merged = await(Transfers.of(plugin).importFrom(export.file(), true));
        assertNotEquals(TransferOutcome.FAILED, merged.outcome(), merged.problems().toString());

        // Overwritten, because its key is in the dump.
        assertEquals(exported, await(stats.find(exported.uuid())).orElseThrow());
        // Left alone, because its key is not — this is the sentence "force
        // merges, it does not replace", asserted rather than documented.
        assertEquals(untouched, await(stats.find(untouched.uuid())).orElseThrow());
        assertEquals(2L, await(stats.count()));
    }

    // ------------------------------------------------------------------ wipe

    @Test
    @DisplayName("wipeAll empties every registered table and reports what went")
    void wipeAllEmptiesEverything() {
        await(stats.saveAll(List.of(row(1), row(2), row(3))));
        await(kits.save(new Kit("sharp", "Sharpness", 10)));

        TransferReport report = await(Transfers.of(plugin).wipeAll());

        assertEquals(TransferOutcome.SUCCESS, report.outcome(), report.problems().toString());
        assertEquals(4L, report.rows());
        assertEquals(0L, await(stats.count()));
        assertEquals(0L, await(kits.count()));
        // Per table, not only in total: an owner wiping five tables has to see
        // which of them actually held anything.
        assertTrue(report.tableNames().containsAll(List.of("transfer_stats", "transfer_kits")),
                report.tableNames().toString());
    }

    @Test
    @DisplayName("wiping one table leaves the others exactly as they were")
    void wipeOneTableOnly() {
        await(stats.saveAll(List.of(row(1), row(2))));
        Kit kit = new Kit("sharp", "Sharpness", 10);
        await(kits.save(kit));

        TransferReport report = await(Transfers.of(plugin).wipe("transfer_stats"));

        assertEquals(TransferOutcome.SUCCESS, report.outcome(), report.problems().toString());
        assertEquals(2L, report.rows());
        assertEquals(0L, await(stats.count()));
        assertEquals(1L, await(kits.count()));
        assertEquals(kit, await(kits.find("sharp")).orElseThrow());
    }

    @Test
    @DisplayName("a table name that does not exist refuses the whole wipe, deleting nothing")
    void wipeRefusesAnUnknownTable() {
        await(stats.save(row(1)));
        await(kits.save(new Kit("sharp", "Sharpness", 10)));

        // The typo is what this is for: skipping the name and emptying the
        // rest is how "wipe the stats" empties the kits and reports success.
        TransferReport refused = await(Transfers.of(plugin).wipe("transfer_stats", "transfer_stast"));

        assertEquals(TransferOutcome.FAILED, refused.outcome());
        assertEquals(0L, refused.rows());
        assertTrue(refused.problems().get(0).startsWith("Refused:"), refused.problems().toString());
        assertTrue(refused.problems().get(0).contains("transfer_kits"),
                "the names it does have are listed: " + refused.problems());
        assertEquals(1L, await(stats.count()), "nothing may go before every name resolves");
        assertEquals(1L, await(kits.count()));
    }

    @Test
    @DisplayName("a wiped table is still usable: the dump it came from imports straight back")
    void wipeIsRecoverableFromADump() {
        Stats written = row(1);
        await(stats.save(written));
        TransferReport export = await(Transfers.of(plugin).export(folder));

        await(Transfers.of(plugin).wipeAll());
        assertEquals(0L, await(stats.count()));

        TransferReport restored = await(Transfers.of(plugin).importFrom(export.file()));
        assertNotEquals(TransferOutcome.FAILED, restored.outcome(), restored.problems().toString());
        assertEquals(written, await(stats.find(written.uuid())).orElseThrow());
    }

    // --------------------------------------------------------- generated ids

    @Test
    @DisplayName("generated ids are preserved, and the next insert does not collide")
    void generatedIdsSurviveAndTheCounterMoves() {
        Repository<Design> designs = Databases.of(plugin).repository(Design.class);
        UUID owner = UUID.randomUUID();
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            ids.add(await(designs.insert(new Design(0L, owner, "design " + index))));
        }

        TransferReport export = await(Transfers.of(plugin).export(folder));
        assertEquals(TransferOutcome.SUCCESS, export.outcome(), export.problems().toString());
        await(designs.all().delete());
        assertEquals(0L, await(designs.count()));

        TransferReport imported = await(Transfers.of(plugin).importFrom(export.file()));
        assertEquals(TransferOutcome.SUCCESS, imported.outcome(), imported.problems().toString());

        // The ids themselves, not merely the row count: an engine that
        // renumbered them would break every row anywhere else that referenced
        // the old id, and the count would still be right.
        for (long id : ids) {
            Design back = await(designs.find(id)).orElse(null);
            assertNotNull(back, "design " + id + " did not come back under its own id");
            assertEquals(id, back.id());
        }

        // The assertion the resequence exists for. H2 does not advance its
        // counter for a row that supplied its key, so without the resequence
        // this insert asks for 1 — a key the table already holds — and fails on
        // the primary key.
        long fresh = await(designs.insert(new Design(0L, owner, "after the import")));
        assertFalse(ids.contains(fresh), "the new key collides with an imported one: " + fresh);
        assertEquals(26L, await(designs.count()));
        assertEquals("after the import", await(designs.find(fresh)).orElseThrow().json());
    }

    // ------------------------------------------------------------ the format

    @Test
    @DisplayName("a dump whose header is missing a column still imports, with that column null")
    void layoutDriftBindsByName() throws IOException {
        UUID uuid = UUID.randomUUID();
        await(stats.save(row(1)));
        TransferReport export = await(Transfers.of(plugin).export(folder));

        // The dump as an older version of the record would have written it:
        // one fewer column, in the header and in every row. Hand-crafted rather
        // than produced by an older library, because that is the only way to
        // have both versions in one test.
        Path drifted = folder.resolve("drifted" + net.exylia.lib.database.transfer.internal
                .DumpFormatAccess.extension());
        List<String> lines = readLines(export.file());
        List<String> rewritten = new ArrayList<>(lines.size());
        int removed = dropColumn(lines, "clan", rewritten);
        assertTrue(removed >= 0, "the fixture must actually remove a column");
        writeLines(drifted, rewritten);

        await(stats.all().delete());
        TransferReport imported = await(Transfers.of(plugin).importFrom(drifted));

        // It imports — a record that gained a component since the dump must
        // still be readable — and it says so rather than looking clean.
        assertEquals(TransferOutcome.PARTIAL, imported.outcome(), imported.problems().toString());
        assertEquals(1L, imported.rows());
        assertTrue(imported.problems().stream().anyMatch(line -> line.contains("Layout drift")
                && line.contains("clan")), imported.problems().toString());
        assertTrue(imported.tables().stream().anyMatch(TableTransfer::drifted),
                imported.tables().toString());

        Stats back = await(stats.all().find()).get(0);
        assertNull(back.clan(), "the column the dump has no value for is null");
        // And nothing shifted: binding positionally would have put the notes
        // into the clan column and reported success.
        assertEquals(1001, back.elo());
        assertEquals(Rank.VIP, back.rank());
        assertNotNull(back.notes());
    }

    @Test
    @DisplayName("a table in the dump that nothing claims is skipped and reported, not fatal")
    void unknownTableIsSkipped() throws IOException {
        await(stats.save(row(1)));
        await(kits.save(new Kit("boxing", "Boxing", 0)));
        TransferReport export = await(Transfers.of(plugin).export(folder));

        // Renaming a table in the dump is the same thing as a plugin that has
        // not been migrated yet: the rows are there, nothing here stores them.
        Path renamed = folder.resolve("renamed" + net.exylia.lib.database.transfer.internal
                .DumpFormatAccess.extension());
        writeLines(renamed, readLines(export.file()).stream()
                .map(line -> line.replace("transfer_kits", "someone_elses_table")).toList());

        await(stats.all().delete());
        await(kits.all().delete());
        TransferReport imported = await(Transfers.of(plugin).importFrom(renamed));

        // Partial, not failed: a partial ecosystem migration is the normal way
        // this is used, and the rows that did have a home arrived.
        assertEquals(TransferOutcome.PARTIAL, imported.outcome());
        assertEquals(1L, imported.rows());
        assertEquals(1L, await(stats.count()));
        assertEquals(0L, await(kits.count()));
        assertTrue(imported.problems().stream().anyMatch(line ->
                line.contains("Skipped") && line.contains("someone_elses_table")),
                imported.problems().toString());
        assertTrue(imported.tables().stream().anyMatch(TableTransfer::skipped));
    }

    @Test
    @DisplayName("a truncated dump fails cleanly, naming the line, and leaves no open stream")
    void aTruncatedDumpFailsCleanly() throws IOException {
        for (int index = 0; index < 40; index++) {
            await(stats.save(row(index)));
        }
        TransferReport export = await(Transfers.of(plugin).export(folder));

        Path broken = folder.resolve("broken" + net.exylia.lib.database.transfer.internal
                .DumpFormatAccess.extension());
        List<String> lines = readLines(export.file());
        List<String> cut = new ArrayList<>(lines.subList(0, 20));
        // A line that stops mid-array, which is what a full disk or a killed
        // process leaves behind.
        cut.add(cut.get(cut.size() - 1).substring(0, 12));
        writeLines(broken, cut);

        await(stats.all().delete());
        TransferReport imported = await(Transfers.of(plugin).importFrom(broken));

        assertEquals(TransferOutcome.FAILED, imported.outcome());
        assertTrue(imported.problems().stream().anyMatch(line -> line.startsWith("line ")),
                "the failure must name the line it gave up on: " + imported.problems());

        // The file is not held open: on a locked-file platform this is the
        // difference between an operator being able to delete a bad dump and
        // having to restart the server. Deleting it is the assertion.
        assertTrue(Files.deleteIfExists(broken), "the reader left the file open");
    }

    @Test
    @DisplayName("the importer never holds more than one batch, whatever the dump holds")
    void theImporterHoldsOneBatch() throws IOException {
        // Two and a half batches: enough that a reader accumulating the file
        // would be visible, and the same shape as the round-trip test.
        List<Stats> rows = new ArrayList<>();
        for (int index = 0; index < 2500; index++) {
            rows.add(row(index));
        }
        await(stats.saveAll(rows));
        TransferReport export = await(Transfers.of(plugin).export(folder));

        List<Integer> sizes = new ArrayList<>();
        net.exylia.lib.database.transfer.internal.DumpFormatAccess
                .observeBatches(export.file(), sizes::add);

        // The bound, asserted rather than commented. A reader that parsed the
        // whole file and chunked it afterwards would produce the same sizes
        // here — which is why the seam observes the reader itself and not the
        // importer's own loop.
        assertFalse(sizes.isEmpty());
        for (int size : sizes) {
            assertTrue(size <= 1000, "a batch of " + size + " exceeds the bound: " + sizes);
        }
        assertEquals(2500, sizes.stream().mapToInt(Integer::intValue).sum(),
                "every row is handed over exactly once: " + sizes);
        // And it took more than one batch, or the bound above proves nothing.
        assertTrue(sizes.size() >= 3, "expected several batches, got " + sizes);
    }

    @Test
    @DisplayName("the dump is NDJSON: a header line, a table marker, then one line per row")
    void theFileShape() throws IOException {
        await(kits.save(new Kit("boxing", "Boxing", 0)));
        TransferReport export = await(Transfers.of(plugin).export(folder));

        List<String> lines = readLines(export.file());
        assertTrue(lines.get(0).startsWith("{\"format\":1"), lines.get(0));
        assertTrue(lines.get(0).contains("\"plugin\":\"Practice\""), lines.get(0));
        // The layout the reader binds by, in the header rather than repeated on
        // every row.
        assertTrue(lines.get(0).contains("\"name\":\"display\""), lines.get(0));
        // A marker is an object and a row is an array, which is what lets the
        // reader tell them apart by one character.
        int marker = lines.indexOf("{\"table\":\"transfer_kits\"}");
        assertTrue(marker > 0, "no table marker in " + lines);
        assertTrue(lines.get(marker + 1).startsWith("["), lines.get(marker + 1));
        assertTrue(lines.get(marker + 1).contains("\"boxing\""), lines.get(marker + 1));
    }

    @Test
    @DisplayName("a plugin with no registered tables is refused rather than exporting nothing")
    void anUnknownPluginIsRefused() {
        Plugin stranger = FakeServer.newPlugin("NeverStoredAnything");
        TransferReport report = await(Transfers.of(stranger).export(folder));

        assertEquals(TransferOutcome.FAILED, report.outcome());
        assertTrue(report.problems().get(0).contains("no registered tables"),
                report.problems().toString());
        // An empty dump on disk would look importable and be worth nothing.
        assertNull(report.file());
    }

    @Test
    @DisplayName("the tables of a plugin are what it registered, named rather than counted")
    void theTablesAreEnumerable() {
        Map<String, Repository<?>> tables = new HashMap<>(Databases.of(plugin).tables());

        assertEquals(java.util.Set.of("transfer_stats", "transfer_kits"), tables.keySet());
        assertEquals(Stats.class, tables.get("transfer_stats").type());
        // find, not of: of would create a view and with it a database.yml in
        // the data folder of a plugin that never asked for one.
        assertNull(Databases.find("NeverStoredAnything"));
        assertTrue(Databases.registeredPlugins().contains("Practice"));
    }

    // ------------------------------------------------------------- fixtures

    /**
     * Rewrites a dump without one column, in the header and in every row.
     *
     * <p>The only way to have a dump written against an older record and a
     * newer record in one test.
     *
     * @return the index the column sat at, or {@code -1} when it was not there
     */
    private static int dropColumn(List<String> lines, String column, List<String> out) {
        String header = lines.get(0);
        String entry = "{\"name\":\"" + column + "\",\"type\":\"string\"}";
        int at = columnIndex(header, column);
        if (at < 0) {
            return -1;
        }
        out.add(header.replace("," + entry, "").replace(entry + ",", ""));
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            out.add(line.startsWith("[") ? dropValue(line, at) : line);
        }
        return at;
    }

    /** Where a column sits in the header's layout for the first table that has it. */
    private static int columnIndex(String header, String column) {
        String needle = "\"name\":\"" + column + "\"";
        int found = header.indexOf(needle);
        if (found < 0) {
            return -1;
        }
        int index = 0;
        // Counting the column entries that start before this one, within the
        // same table block: every entry begins with "name".
        for (int at = header.indexOf("\"name\":\""); at >= 0 && at < found;
             at = header.indexOf("\"name\":\"", at + 1)) {
            index++;
        }
        return index;
    }

    /** Removes one value from a positional row array, respecting quoted commas. */
    private static String dropValue(String row, int at) {
        List<String> values = splitTopLevel(row.substring(1, row.length() - 1));
        if (at >= values.size()) {
            return row;
        }
        values.remove(at);
        return "[" + String.join(",", values) + "]";
    }

    private static List<String> splitTopLevel(String body) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (character == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(character);
        }
        values.add(current.toString());
        return values;
    }

    private static List<String> readLines(Path file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        }
    }

    private static void writeLines(Path file, List<String> lines) throws IOException {
        try (Writer writer = new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
        }
    }
}
