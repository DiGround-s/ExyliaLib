package net.exylia.lib.database.internal;

import net.exylia.lib.database.PluginDatabase;
import net.exylia.lib.database.Table;
import net.exylia.lib.debug.Debug;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rows a table inherits the moment it is prepared, before the plugin reads it.
 *
 * <p>Two sources, in order:
 * <ol>
 *   <li><b>A move.</b> A plugin the setup pointed at a shared database still
 *       has its rows in its old H2 file ({@link Moves}). Each table is copied
 *       from there the first time it is prepared on the new database, so the
 *       plugin starts on its own data: nothing seeds defaults into a table that
 *       only looked empty, and no cache holds an empty state. A scoped table
 *       (a network group, a server) takes the old file's table of that scope,
 *       else the plain one: the file was this server's alone.</li>
 *   <li><b>A table just created</b> whose rows still live under an older name
 *       ({@link Table#previous()}), or, for a scoped table that asked for it,
 *       under a previous server id or the plain table.</li>
 * </ol>
 * A source is used only if it has every column the record declares, so a
 * table another plugin keeps under the same old name is never read.
 *
 * <p>Copies run one at a time on a thread of their own and read the old file
 * directly, so they never wait inside the database pool. The repository's
 * operations wait for them, which is what keeps the plugin from reading first.
 *
 * @since 1.581.0
 */
public final class Carry {

    private static final int BATCH = 500;
    private static final ExecutorService THREAD = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ExyliaLib carry");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<Path, SqlBackend> SOURCES = new ConcurrentHashMap<>();
    /** Thrown by a probe's block to stop after the first batch: the read worked. */
    private static final RuntimeException STOP = new RuntimeException("probe", null, false, false) {
    };

    private Carry() {
        throw new AssertionError("No instances.");
    }

    /**
     * What has to happen after a table is prepared, and before anything reads it.
     *
     * @param inherit whether a scoped table just created takes the rows of a
     *                previous server scope or of the plain table
     * @return the storage, once every copy it needed has landed
     */
    public static @NotNull CompletableFuture<Storage> after(@NotNull Plugin plugin, @NotNull Storage storage,
                                                     @NotNull EntityModel<?> model, @NotNull SchemaReport report,
                                                     @NotNull String scope, boolean inherit) {
        Moves.Move move = Moves.of(plugin.getName());
        boolean moving = move != null && !move.done().contains(model.table().toLowerCase(Locale.ROOT));
        List<String> older = report.createdTable() ? older(plugin, model, scope, inherit) : List.of();
        if (!moving && older.isEmpty()) return CompletableFuture.completedFuture(storage);
        return CompletableFuture.supplyAsync(() -> {
            Debug debug = Debug.of(plugin);
            boolean copied = false;
            if (moving) {
                try {
                    copied = fromMove(plugin, move, storage, model, scope, debug);
                    Moves.done(plugin.getName(), model.table());
                } catch (RuntimeException | SQLException failed) {
                    // Not written down as done: the next start tries again, and
                    // a table half copied is finished by the merge then.
                    debug.error("Could not copy " + model.table() + " from " + move.source()
                            + ".mv.db: " + failed.getMessage() + ". Its rows stay in that file and the copy is"
                            + " tried again on the next start.", failed);
                }
            }
            if (!copied && !older.isEmpty()) {
                try {
                    fromOlder(storage, model, older, debug);
                } catch (RuntimeException failed) {
                    debug.error("Could not copy the rows of " + model.table() + " from its older table: "
                            + failed.getMessage(), failed);
                }
            }
            return storage;
        }, THREAD);
    }

    /** Closes the old files it opened. From the library's shutdown. */
    public static void shutdown() {
        SOURCES.values().forEach(SqlBackend::close);
        SOURCES.clear();
    }

    // ------------------------------------------------------------------ moves

    private static boolean fromMove(Plugin plugin, Moves.Move move, Storage storage, EntityModel<?> model,
                                    String scope, Debug debug) throws SQLException {
        // Pointed back at the old file: nothing to move, and nothing to wait for.
        Path now = DatabaseRuntime.settings(plugin).file();
        if (now != null && now.toAbsolutePath().normalize().equals(move.source().toAbsolutePath().normalize())) {
            Moves.remove(plugin.getName());
            return false;
        }
        if (!java.nio.file.Files.isRegularFile(Path.of(move.source() + ".mv.db"))) return false;
        SqlBackend source = SOURCES.computeIfAbsent(move.source(),
                path -> SqlBackend.open(SqlSettings.file("h2", path), "carry-" + plugin.getName()));
        for (String name : names(model, scope, true, true, List.of())) {
            EntityModel<?> from = model.renamed(name);
            long rows = readable(source, from);
            if (rows < 0) continue;
            // The library's own tables hold every plugin's rows by key, so this server's always join them.
            Moves.Policy policy = model.type().getName().startsWith("net.exylia.lib.") ? Moves.Policy.MERGE : move.policy();
            if (rows > 0) copy(source, from, storage, model, policy, debug, move.source() + ".mv.db");
            return rows > 0;
        }
        return false;
    }

    // ------------------------------------------------------------------ older names

    /** The older tables a table just created could take its rows from, newest first. */
    private static List<String> older(Plugin plugin, EntityModel<?> model, String scope, boolean inherit) {
        List<String> previousScopes = new ArrayList<>();
        if (inherit && !scope.isEmpty()) {
            for (String id : DatabaseRuntime.redis(plugin).previousIds()) {
                try {
                    String old = PluginDatabase.scope(id);
                    if (!old.isEmpty() && !old.equals(scope)) previousScopes.add(old);
                } catch (IllegalArgumentException unusable) {
                    // A previous id that was never a scope has no table to look in.
                }
            }
        }
        // On MongoDB a collection of somebody else's reads without failing, so
        // only this record's own scoped tables are trusted there.
        boolean mongo = DatabaseRuntime.settings(plugin).engine().toLowerCase(Locale.ROOT).startsWith("mongo");
        List<String> names = names(model, scope, !mongo, inherit, previousScopes);
        names.remove(model.table());
        return names;
    }

    private static void fromOlder(Storage storage, EntityModel<?> model, List<String> older, Debug debug) {
        // Read through the same storage: the older table is on the database the new one was just made in.
        for (String name : older) {
            EntityModel<?> from = model.renamed(name);
            long rows = readable(storage, from);
            if (rows <= 0) continue;
            copy(storage, from, storage, model, Moves.Policy.MERGE, debug, name);
            return;
        }
    }

    /**
     * The tables a table's rows may be under, newest first: its own name and
     * each previous one, under its scope, then under each previous scope, then
     * plain when {@code plain} asks for it.
     */
    static List<String> names(EntityModel<?> model, String scope, boolean previous, boolean plain,
                              List<String> previousScopes) {
        Table table = model.type().getAnnotation(Table.class);
        List<String> bases = new ArrayList<>();
        bases.add(table == null ? model.table() : table.value());
        if (table != null && previous) bases.addAll(List.of(table.previous()));
        List<String> out = new ArrayList<>();
        if (scope.isEmpty()) {
            out.addAll(bases);
        } else {
            for (String base : bases) out.add(scope + "_" + base);
            for (String old : previousScopes) for (String base : bases) out.add(old + "_" + base);
            if (plain) out.addAll(bases);
        }
        // The model's own table first even when the annotation is not what named it.
        out.remove(model.table());
        out.add(0, model.table());
        return out;
    }

    // ------------------------------------------------------------------ copying

    /**
     * How many rows a table has, or {@code -1} when it is not there or lacks a
     * column the record declares, which is a table of somebody else.
     */
    private static long readable(SqlBackend source, EntityModel<?> model) {
        try {
            source.scan(model, 1, batch -> {
                throw STOP;
            });
            return 0L;
        } catch (RuntimeException stopped) {
            if (stopped != STOP) return -1L;
        } catch (SQLException missing) {
            return -1L;
        }
        try {
            return source.count(model, List.of(), List.of());
        } catch (SQLException failed) {
            return -1L;
        }
    }

    private static long readable(Storage storage, EntityModel<?> model) {
        try {
            storage.scan(model, 1, batch -> {
                throw STOP;
            }).join();
            return 0L;
        } catch (RuntimeException stopped) {
            if (root(stopped) != STOP) return -1L;
        }
        try {
            return storage.count(model, List.of(), List.of()).join();
        } catch (RuntimeException failed) {
            return -1L;
        }
    }

    private static void copy(Object source, EntityModel<?> from, Storage target, EntityModel<?> to,
                             Moves.Policy policy, Debug debug, String where) {
        long existing = target.count(to, List.of(), List.of()).join();
        if (existing > 0 && policy == Moves.Policy.NETWORK) {
            debug.warn("Kept the " + existing + " rows " + to.table() + " already had on the shared database;"
                    + " this server's stay in " + where + ".");
            return;
        }
        boolean merge = existing > 0;
        ColumnModel key = to.columns().get(to.idIndex());
        AtomicLong written = new AtomicLong();
        AtomicLong kept = new AtomicLong();
        java.util.function.Consumer<List<Object[]>> block = batch -> {
            List<Object[]> rows = batch;
            if (merge) {
                rows = new ArrayList<>(batch.size());
                for (Object[] row : batch) {
                    Object id = key.decode(row[to.idIndex()]);
                    if (id != null && Boolean.TRUE.equals(target.exists(to, id).join())) {
                        kept.incrementAndGet();
                    } else {
                        rows.add(row);
                    }
                }
            }
            written.addAndGet(target.writeRows(to, rows).join());
        };
        try {
            if (source instanceof SqlBackend backend) {
                backend.scan(from, BATCH, block);
            } else {
                ((Storage) source).scan(from, BATCH, block).join();
            }
        } catch (SQLException failed) {
            throw new IllegalStateException(failed.getMessage(), failed);
        }
        target.resequence(to).join();
        String into = from.table().equals(to.table()) ? to.table() : from.table() + " into " + to.table();
        if (kept.get() > 0) {
            debug.warn("Copied " + written.get() + " rows of " + into + " from " + where + "; kept the shared"
                    + " database's row for " + kept.get() + " keys both had. This server's version of those"
                    + " stays in " + where + ".");
        } else {
            debug.log("Copied " + written.get() + " rows of " + into + " from " + where + ".");
        }
    }

    private static Throwable root(Throwable failure) {
        Throwable at = failure;
        while (at.getCause() != null && at.getCause() != at) at = at.getCause();
        return at;
    }
}
