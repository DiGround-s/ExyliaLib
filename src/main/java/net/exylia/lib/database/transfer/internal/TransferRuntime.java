package net.exylia.lib.database.transfer.internal;

import net.exylia.lib.database.PluginDatabase;
import net.exylia.lib.database.Repository;
import net.exylia.lib.database.internal.ColumnModel;
import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.Storage;
import net.exylia.lib.database.transfer.TableTransfer;
import net.exylia.lib.database.transfer.TransferOutcome;
import net.exylia.lib.database.transfer.TransferReport;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.redis.Redis;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

/**
 * Everything an export and an import actually do.
 *
 * <p>Both are one blocking pass driven on a background thread: read the
 * tables in a fixed order, and per table stream batches between the file and
 * the database. Nothing accumulates — one batch is alive at a time on either
 * side — and everything that goes wrong ends up in the {@link TransferReport}
 * rather than as a throw.
 *
 * <h2>Threads</h2>
 * The public entry points return immediately and complete their future on a
 * background thread. The pass itself runs on the library's own scheduler
 * through {@code Tasks.runAsync}, never on a thread of this module's own —
 * that is the library's rule, and it is also what makes the work go away when
 * the library is disabled.
 *
 * <p>Inside the pass, the file is written from exactly one thread. An export
 * hands its writer to {@code Storage.scan}, whose block runs on the database's
 * own thread and is called with each batch before the next is read, and it
 * waits for each table's scan before starting the next. So writes are
 * serialised even though two threads are involved across the pass.
 *
 * @since 1.36.0
 */
@ApiStatus.Internal
public final class TransferRuntime {

    private TransferRuntime() {
        throw new AssertionError("No instances.");
    }

    // ------------------------------------------------------------- exporting

    /**
     * Writes every registered table of a plugin into one dump.
     *
     * @param owner       the plugin whose scheduler runs the work — the
     *                    library's, so a consumer already on its way down does
     *                    not take the export with it
     * @param plugin      the plugin whose tables these are
     * @param destination a folder to write into, or the exact file to write
     * @return the report, always; the future fails only if the scheduler
     *         refused the work
     */
    public static @NotNull CompletableFuture<TransferReport> export(@NotNull Plugin owner,
                                                                    @NotNull Plugin plugin,
                                                                    @NotNull Path destination) {
        return on(owner, () -> runExport(plugin, destination));
    }

    private static TransferReport runExport(Plugin plugin, Path destination) {
        Instant started = Instant.now();
        PluginDatabase database = net.exylia.lib.database.Databases.find(plugin.getName());
        if (database == null) {
            return TransferReport.failed(plugin.getName() + " has no registered tables, so there"
                    + " is nothing to export. A plugin appears here once it has asked for its"
                    + " first repository.", elapsed(started));
        }
        Map<String, Repository<?>> tables = database.tables();
        if (tables.isEmpty()) {
            return TransferReport.failed(plugin.getName() + " has a database view but no"
                    + " registered tables yet.", elapsed(started));
        }

        List<String> problems = new ArrayList<>();
        List<DumpFormat.TableLayout> layouts = new ArrayList<>(tables.size());
        for (Repository<?> repository : tables.values()) {
            EntityModel<?> model = repository.model();
            long rows;
            try {
                rows = repository.count().join();
            } catch (RuntimeException unreadable) {
                // Not fatal: the count is what the header advertises, and an
                // export whose header says zero still carries every row. It is
                // worth a line because the number is what a later import shows.
                rows = -1L;
                problems.add("Could not count " + model.table() + " before exporting it: "
                        + rootMessage(unreadable));
            }
            layouts.add(layoutOf(model, rows));
        }

        Path file = fileFor(destination, plugin.getName(), database.engine(), started);
        long total = 0L;
        List<TableTransfer> handled = new ArrayList<>(tables.size());
        try (DumpWriter writer = DumpWriter.open(file, plugin.getName(), database.engine(),
                started, layouts)) {
            for (Repository<?> repository : tables.values()) {
                EntityModel<?> model = repository.model();
                writer.beginTable(model.table());
                long written = exportTable(repository.storage(), model, writer);
                handled.add(TableTransfer.of(model.table(), written));
                total += written;
            }
        } catch (IOException | RuntimeException failure) {
            // A half-written file is worse than none: it looks importable and
            // is missing whatever came after the failure. Deleted, and the
            // deletion itself is reported if it too fails, because a leftover
            // is exactly what somebody would try to import next.
            problems.add("The export failed after " + total + " rows: " + rootMessage(failure));
            deleteQuietly(file, problems);
            return new TransferReport(TransferOutcome.FAILED, null, handled, total,
                    elapsed(started), problems);
        }
        return new TransferReport(problems.isEmpty() ? TransferOutcome.SUCCESS : TransferOutcome.PARTIAL,
                file, handled, total, elapsed(started), problems);
    }

    /**
     * Streams one table into the writer.
     *
     * <p>The scan's block writes each batch straight through and keeps nothing.
     * Whatever the block throws ends the scan and fails its future, which is
     * what stops an export carrying on past a disk that filled up.
     */
    private static long exportTable(Storage storage, EntityModel<?> model, DumpWriter writer) {
        return storage.scan(model, DumpFormat.BATCH_SIZE, batch -> {
            try {
                writer.writeRows(model, batch);
            } catch (IOException failure) {
                // Wrapped rather than swallowed: an unwritable file must stop
                // the walk, and the scan's contract is that a throwing block
                // ends it.
                throw new IllegalStateException("Could not write a batch of " + model.table()
                        + ": " + failure.getMessage(), failure);
            }
        }).join();
    }

    private static DumpFormat.TableLayout layoutOf(EntityModel<?> model, long rows) {
        List<DumpFormat.ColumnLayout> columns = new ArrayList<>(model.columns().size());
        for (ColumnModel column : model.columns()) {
            columns.add(new DumpFormat.ColumnLayout(column.name(), DumpFormat.tokenOf(column)));
        }
        return new DumpFormat.TableLayout(model.table(), rows, columns, model.generatedId());
    }

    /**
     * The file to write: the path itself when it names one, a generated name
     * inside it when it is a folder.
     *
     * <p>A folder is the normal case — a command passes the server's own dump
     * directory — and the generated name carries the plugin, the engine and a
     * timestamp so exporting twice does not overwrite the first copy.
     */
    private static Path fileFor(Path destination, String plugin, String engine, Instant when) {
        boolean isFolder = Files.isDirectory(destination)
                || !destination.getFileName().toString().endsWith(DumpFormat.EXTENSION);
        return isFolder
                ? destination.resolve(DumpFormat.fileName(plugin, engine, when))
                : destination;
    }

    // ------------------------------------------------------------- importing

    /**
     * Reads a dump into a plugin's registered tables.
     *
     * @param owner  the plugin whose scheduler runs the work
     * @param plugin the plugin whose tables are written
     * @param source the dump
     * @param force  whether to write into tables that already hold rows
     * @return the report, always
     */
    public static @NotNull CompletableFuture<TransferReport> importFrom(@NotNull Plugin owner,
                                                                        @NotNull Plugin plugin,
                                                                        @NotNull Path source,
                                                                        boolean force) {
        return on(owner, () -> runImport(plugin, source, force));
    }

    private static TransferReport runImport(Plugin plugin, Path source, boolean force) {
        Instant started = Instant.now();
        PluginDatabase database = net.exylia.lib.database.Databases.find(plugin.getName());
        if (database == null || database.tables().isEmpty()) {
            return TransferReport.failed(plugin.getName() + " has no registered tables, so there"
                    + " is nowhere to import into. A plugin appears here once it has asked for"
                    + " its first repository.", elapsed(started));
        }
        if (!Files.isReadable(source)) {
            return TransferReport.failed("There is no readable dump at " + source + ".",
                    elapsed(started));
        }
        Map<String, Repository<?>> tables = database.tables();

        List<String> problems = new ArrayList<>();
        try (DumpReader reader = DumpReader.open(source)) {
            // Every guard before the first write. A refusal that happens after
            // three tables have landed is not a refusal, it is a mess.
            List<String> occupied = occupied(reader.header(), tables, problems);
            if (!occupied.isEmpty() && !force) {
                problems.add(0, "Refused: " + String.join(", ", occupied)
                        + " already hold rows. Re-run with force to write anyway — force MERGES:"
                        + " a row whose key is in the dump is overwritten, and a row that is not"
                        + " in the dump is left exactly where it is. It does not replace the"
                        + " table.");
                return new TransferReport(TransferOutcome.FAILED, source, List.of(), 0L,
                        elapsed(started), problems);
            }
            if (!occupied.isEmpty() && Redis.isActive()) {
                // The known limitation, said out loud at the moment it applies.
                problems.add("Redis is on and " + String.join(", ", occupied) + " already held"
                        + " rows. A bulk write does not invalidate the shared cache — one message"
                        + " per batch would send every peer back to the database for the whole"
                        + " table — so other servers keep serving the rows they had until their"
                        + " entries expire.");
            }
            return read(reader, tables, source, started, problems);
        } catch (DumpException unreadable) {
            problems.add(unreadable.describe());
            return new TransferReport(TransferOutcome.FAILED, source, List.of(), 0L,
                    elapsed(started), problems);
        } catch (IOException | RuntimeException failure) {
            problems.add("The dump could not be read: " + rootMessage(failure));
            return new TransferReport(TransferOutcome.FAILED, source, List.of(), 0L,
                    elapsed(started), problems);
        }
    }

    /**
     * Walks the dump's rows into the tables that claim them.
     *
     * <p>One batch at a time and nothing kept: the reader is asked for at most
     * {@link DumpFormat#BATCH_SIZE} rows, they are bound and written, and the
     * list is dropped before the next is read.
     */
    private static TransferReport read(DumpReader reader,
                                       Map<String, Repository<?>> tables,
                                       Path source,
                                       Instant started,
                                       List<String> problems) throws IOException {
        Map<String, Long> written = new java.util.LinkedHashMap<>();
        List<TableTransfer> handled = new ArrayList<>();
        java.util.Set<String> skipped = new java.util.LinkedHashSet<>();
        java.util.Set<String> imported = new java.util.LinkedHashSet<>();
        long total = 0L;
        boolean partial = false;

        DumpReader.Batch batch;
        while ((batch = reader.next(DumpFormat.BATCH_SIZE)) != null) {
            Repository<?> repository = tables.get(batch.table());
            if (repository == null) {
                if (skipped.add(batch.table())) {
                    // A table nothing here claims is how a partial ecosystem
                    // migration looks: reported once per table, never per batch,
                    // and never fatal.
                    handled.add(TableTransfer.skipped(batch.table(),
                            "no registered record stores this table"));
                    problems.add("Skipped " + batch.table()
                            + ": no registered record of this plugin stores it.");
                    partial = true;
                }
                continue;
            }
            EntityModel<?> model = repository.model();
            DumpFormat.TableLayout layout = reader.header().table(batch.table());
            if (layout == null) {
                problems.add("The dump has rows for " + batch.table()
                        + " but its header never declared that table, so there is no column"
                        + " layout to bind them by. Its rows are skipped.");
                partial = true;
                continue;
            }
            Binding binding = Binding.of(model, layout);
            if (binding.drifted() && imported.add(batch.table() + ":drift")) {
                problems.add("Layout drift in " + batch.table() + ": " + binding.describe()
                        + ". Rows were bound by column name; any column the dump does not have"
                        + " is null.");
                partial = true;
            }
            try {
                List<Object[]> rows = binding.bind(batch.rows());
                int count = repository.storage().writeRows(model, rows).join();
                written.merge(batch.table(), (long) count, Long::sum);
                total += count;
                imported.add(batch.table());
            } catch (RuntimeException failure) {
                // One batch that the engine refused. Reported and carried past
                // — but the outcome can never be SUCCESS again, which is the
                // one thing ExyliaCommons got wrong here.
                problems.add("A batch of " + batch.table() + " ending at line " + reader.line()
                        + " could not be written: " + rootMessage(failure));
                partial = true;
            }
        }

        // After every row, not per batch: a table whose key the engine hands
        // out has a counter still sitting where it was, and the next insert
        // would ask for a key the imported rows already hold.
        for (String table : imported) {
            Repository<?> repository = tables.get(table);
            if (repository == null || !repository.model().generatedId()) {
                continue;
            }
            try {
                repository.storage().resequence(repository.model()).join();
            } catch (RuntimeException failure) {
                problems.add("The identity counter of " + table + " could not be moved past the"
                        + " imported keys: " + rootMessage(failure)
                        + ". The next insert into it will collide.");
                partial = true;
            }
        }

        for (Map.Entry<String, Long> entry : written.entrySet()) {
            DumpFormat.TableLayout layout = reader.header().table(entry.getKey());
            Repository<?> repository = tables.get(entry.getKey());
            boolean drifted = repository != null && layout != null
                    && Binding.of(repository.model(), layout).drifted();
            handled.add(drifted
                    ? TableTransfer.drifted(entry.getKey(), entry.getValue(), "bound by name")
                    : TableTransfer.of(entry.getKey(), entry.getValue()));
        }
        return new TransferReport(partial ? TransferOutcome.PARTIAL : TransferOutcome.SUCCESS,
                source, handled, total, elapsed(started), problems);
    }

    /**
     * The tables in the dump that this plugin claims and that already hold rows.
     *
     * <p>Checked before anything is written, and only for tables the import
     * would actually touch: a table full of rows that the dump has nothing for
     * is not in the way of anything.
     */
    private static List<String> occupied(DumpFormat.Header header,
                                         Map<String, Repository<?>> tables,
                                         List<String> problems) {
        List<String> found = new ArrayList<>();
        for (DumpFormat.TableLayout layout : header.tables()) {
            Repository<?> repository = tables.get(layout.table());
            if (repository == null) {
                continue;
            }
            try {
                long rows = repository.count().join();
                if (rows > 0L) {
                    found.add(layout.table() + " (" + rows + " rows)");
                }
            } catch (RuntimeException unreadable) {
                // Counted as in the way. A table whose count cannot be read
                // might be full, and the refusal is the safe answer.
                found.add(layout.table() + " (could not be counted)");
                problems.add("Could not count " + layout.table() + " before importing: "
                        + rootMessage(unreadable));
            }
        }
        return found;
    }

    /**
     * How a dump's columns line up with a model's, resolved once per table.
     *
     * <p>By name, never by position. A record that gained, lost or reordered a
     * component since the dump was written must still import, and binding
     * positionally would put one column's value into another and report
     * success — which for a {@code UUID} column landing in {@code clan} is a
     * table nobody can read and nothing that says so.
     */
    record Binding(@NotNull EntityModel<?> model,
                   int[] sources,
                   @NotNull List<String> missing,
                   @NotNull List<String> extra,
                   @NotNull List<String> types) {

        static Binding of(EntityModel<?> model, DumpFormat.TableLayout layout) {
            List<ColumnModel> columns = model.columns();
            int[] sources = new int[columns.size()];
            List<String> missing = new ArrayList<>(0);
            List<String> types = new ArrayList<>(columns.size());
            for (int index = 0; index < columns.size(); index++) {
                ColumnModel column = columns.get(index);
                int at = layout.indexOf(column.name());
                sources[index] = at;
                types.add(at < 0 ? DumpFormat.tokenOf(column) : layout.columns().get(at).type());
                if (at < 0) {
                    missing.add(column.name());
                }
            }
            List<String> extra = new ArrayList<>(0);
            for (DumpFormat.ColumnLayout column : layout.columns()) {
                if (model.column(column.name()) == null) {
                    extra.add(column.name());
                }
            }
            return new Binding(model, sources, List.copyOf(missing), List.copyOf(extra),
                    List.copyOf(types));
        }

        boolean drifted() {
            return !missing.isEmpty() || !extra.isEmpty();
        }

        String describe() {
            StringBuilder text = new StringBuilder();
            if (!missing.isEmpty()) {
                text.append("the dump has no ").append(missing);
            }
            if (!extra.isEmpty()) {
                if (!text.isEmpty()) {
                    text.append("; ");
                }
                text.append("the record no longer has ").append(extra);
            }
            return text.toString();
        }

        /**
         * Turns parsed rows into rows in {@link EntityModel#columns()} order.
         *
         * <p>A column the dump does not carry is {@code null}. That is correct
         * even for a column the record declares non-null: the schema layer
         * never emits {@code NOT NULL}, and a record reading that row back gets
         * the type's absent value — which is precisely what a column added to a
         * live table looks like on every row that predates it.
         */
        List<Object[]> bind(List<List<Object>> rows) {
            List<Object[]> bound = new ArrayList<>(rows.size());
            for (List<Object> row : rows) {
                Object[] values = new Object[sources.length];
                for (int index = 0; index < sources.length; index++) {
                    int at = sources[index];
                    Object raw = at >= 0 && at < row.size() ? row.get(at) : null;
                    values[index] = DumpReader.coerce(raw, types.get(index));
                }
                bound.add(values);
            }
            return bound;
        }
    }

    // --------------------------------------------------------------- wiping

    /**
     * Empties a plugin's registered tables, or the ones it names.
     *
     * <p>Nothing is written to a file here and nothing is read from one: a
     * wipe deletes rows and reports how many went. The dump an owner wants
     * taken first is an {@link #export} the caller runs before this, which is
     * what {@code /exylialib wipe} does — keeping the two apart is what lets a
     * plugin that already has its own backup skip one.
     *
     * <h2>All or nothing on the names</h2>
     * A name that matches no registered table fails the whole wipe before a
     * single row is removed. The alternative — skip it and empty the rest — is
     * how a typo in {@code players} empties {@code kits} and reports success.
     *
     * @param owner  the plugin whose scheduler runs the work — the library's
     * @param plugin the plugin whose tables these are
     * @param tables the table names to empty, or {@code null} for every
     *               registered table
     * @return the report, always; a refusal is a {@link TransferOutcome#FAILED}
     *         report and not a thrown exception
     * @since 1.76.0
     */
    public static @NotNull CompletableFuture<TransferReport> wipe(@NotNull Plugin owner,
                                                                  @NotNull Plugin plugin,
                                                                  @Nullable Set<String> tables) {
        Set<String> requested = tables == null ? null : Set.copyOf(tables);
        return on(owner, () -> runWipe(plugin, requested));
    }

    private static TransferReport runWipe(Plugin plugin, @Nullable Set<String> requested) {
        Instant started = Instant.now();
        PluginDatabase database = net.exylia.lib.database.Databases.find(plugin.getName());
        if (database == null) {
            return TransferReport.failed(plugin.getName() + " has no registered tables, so there"
                    + " is nothing to wipe. A plugin appears here once it has asked for its"
                    + " first repository.", elapsed(started));
        }
        Map<String, Repository<?>> registered = database.tables();
        if (registered.isEmpty()) {
            return TransferReport.failed(plugin.getName() + " has a database view but no"
                    + " registered tables yet.", elapsed(started));
        }

        List<Repository<?>> targets = new ArrayList<>();
        if (requested == null) {
            targets.addAll(registered.values());
        } else {
            for (String name : requested) {
                Repository<?> repository = match(registered, name);
                if (repository == null) {
                    // Before anything is deleted, deliberately: see the class
                    // note above. The known names are in the message because
                    // the answer to a typo is the list somebody meant to pick
                    // from.
                    return TransferReport.failed("Refused: " + plugin.getName() + " has no table"
                            + " named " + name + ". It registers "
                            + String.join(", ", registered.keySet()) + '.', elapsed(started));
                }
                targets.add(repository);
            }
        }

        List<String> problems = new ArrayList<>();
        List<TableTransfer> handled = new ArrayList<>(targets.size());
        long total = 0L;
        for (Repository<?> repository : targets) {
            EntityModel<?> model = repository.model();
            try {
                long removed = repository.storage().deleteAll(model).join();
                handled.add(TableTransfer.of(model.table(), removed));
                total += removed;
            } catch (RuntimeException failure) {
                // One table's failure does not stop the rest: a wipe half done
                // and fully reported is recoverable, and a wipe that stopped
                // silently at the second of five tables is what leaves an owner
                // guessing which ones went.
                problems.add("Could not empty " + model.table() + ": " + rootMessage(failure));
                handled.add(TableTransfer.skipped(model.table(), "the delete failed"));
            }
        }
        return new TransferReport(problems.isEmpty() ? TransferOutcome.SUCCESS : TransferOutcome.PARTIAL,
                null, handled, total, elapsed(started), problems);
    }

    /**
     * A registered table by name, ignoring case.
     *
     * <p>Case-insensitive because the name arrives from a chat box as often as
     * from code, and because the engines themselves disagree about it: the same
     * {@code @Table("Players")} is {@code PLAYERS} on H2 and {@code players} on
     * Postgres, so an exact match would refuse a name the admin read off the
     * database.
     */
    private static @Nullable Repository<?> match(Map<String, Repository<?>> registered, String name) {
        Repository<?> exact = registered.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Repository<?>> entry : registered.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    // ------------------------------------------------------------- machinery

    /**
     * Runs one blocking pass off the main thread.
     *
     * <p>Through {@code Tasks}, never a thread or an executor of this module's
     * own: that is the library's rule, and it is also what makes a transfer
     * stop when the library is disabled rather than writing into a database
     * whose pool is closing underneath it.
     *
     * <p>Hand-rolled rather than {@code supplyAsync} for the same reason the
     * database module does it: a scheduler that refuses work throws on the
     * <em>caller's</em> thread, and a caller that correctly handles every
     * failure through the future would be killed by the one that arrived
     * another way.
     */
    private static CompletableFuture<TransferReport> on(Plugin owner,
                                                        java.util.function.Supplier<TransferReport> pass) {
        CompletableFuture<TransferReport> future = new CompletableFuture<>();
        try {
            Tasks.of(owner).runAsync(() -> {
                try {
                    future.complete(pass.get());
                } catch (Throwable failure) {
                    // Nothing should reach here — every path above turns a
                    // failure into a report — so if one does it is a bug in
                    // this class and must be visible rather than swallowed.
                    Debug.of(owner).error("A database transfer failed in a way it should have"
                            + " reported instead: " + failure.getMessage(), failure);
                    future.complete(TransferReport.failed(String.valueOf(failure.getMessage()),
                            Duration.ZERO));
                }
            });
        } catch (RuntimeException rejected) {
            future.complete(TransferReport.failed("The transfer could not be scheduled, which"
                    + " normally means ExyliaLib is being disabled.", Duration.ZERO));
        }
        return future;
    }

    private static void deleteQuietly(Path file, List<String> problems) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException stubborn) {
            problems.add("The incomplete dump at " + file + " could not be removed: "
                    + stubborn.getMessage() + ". Delete it by hand — it is not importable.");
        }
    }

    /**
     * The message worth showing out of a future's failure.
     *
     * <p>A {@code join} wraps everything in a {@link java.util.concurrent.CompletionException}
     * whose own message is the wrapped exception's class name, which says
     * nothing a reader can act on.
     */
    private static String rootMessage(@Nullable Throwable failure) {
        Throwable current = failure;
        while (current instanceof java.util.concurrent.CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        if (current == null) {
            return "unknown";
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }

    private static Duration elapsed(Instant started) {
        return Duration.between(started, Instant.now());
    }

    /**
     * Test seam: how many rows the importer held at once, observed per batch.
     *
     * <p>Not used in production and deliberately not a field on the class: the
     * memory bound is a property of the loop above — the reader is asked for at
     * most one batch and the list is dropped before the next read — and a test
     * that wants to prove it watches the sizes go past rather than trusting the
     * comment.
     */
    static void observeBatches(@NotNull DumpReader reader, @NotNull IntConsumer sizes)
            throws IOException {
        DumpReader.Batch batch;
        while ((batch = reader.next(DumpFormat.BATCH_SIZE)) != null) {
            sizes.accept(batch.rows().size());
        }
    }
}
