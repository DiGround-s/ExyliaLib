package net.exylia.lib.database;

import net.exylia.lib.database.internal.DatabaseRuntime;
import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.GatedStorage;
import net.exylia.lib.database.internal.Storage;
import net.exylia.lib.debug.Debug;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One plugin's view of the shared database.
 *
 * <p>Obtained from {@link Databases#of(Plugin)} and normally kept in a field:
 *
 * <pre>{@code
 * private Repository<PlayerStats> stats;
 *
 * @Override
 * public void onEnable() {
 *     stats = Databases.of(this).repository(PlayerStats.class);
 * }
 * }</pre>
 *
 * <p>The connection is not this plugin's — one pool serves the whole server —
 * but the repositories are. Disabling the plugin drops them, and nothing it
 * queued outlives it.
 *
 * <h2>Nothing here blocks, including registration</h2>
 * {@link #repository} returns immediately. Behind it, the pool is opened if it
 * is not already and the table is created if it is not already, both on a
 * background thread; operations issued in the meantime are chained onto that
 * work rather than raced against it. So this is correct, in the same tick, with
 * no table in the database yet:
 *
 * <pre>{@code
 * Repository<PlayerStats> stats = Databases.of(this).repository(PlayerStats.class);
 * stats.save(new PlayerStats(uuid, 1200));   // lands after the CREATE TABLE
 * }</pre>
 *
 * <p>The reason it has to work that way: registration happens in
 * {@code onEnable}, on the main thread, and creating a table is a round trip to
 * a machine that may not be this one. Blocking there is a server that takes a
 * second longer to start for every plugin that stores anything, and on an
 * unreachable database it is a server that never starts at all.
 *
 * <h2>One repository per record type</h2>
 * Asking twice for the same class returns the same instance, so a plugin that
 * cannot conveniently keep the reference does not create a second table
 * preparation by asking again.
 *
 * <h2>Threads</h2>
 * Safe from any thread.
 *
 * @since 1.24.0
 */
public final class PluginDatabase {

    private final Plugin plugin;

    /**
     * This plugin's repositories, by record class.
     *
     * <p>Unbounded on purpose: the key is a loaded {@code Class} and a plugin
     * has a fixed, small number of them. A bounded cache here would mean
     * preparing a table again because a different record was used more recently.
     */
    private final Map<Class<?>, Repository<?>> repositories = new ConcurrentHashMap<>();

    PluginDatabase(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    /** The plugin this view belongs to. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /**
     * The repository for a record type, creating its table if it is missing.
     *
     * <p>Call this once per record type, from {@code onEnable}. It returns
     * without waiting for anything; see the class documentation for what happens
     * behind it.
     *
     * <p>The record is compiled here, and a record that cannot be stored fails
     * here, synchronously, with a message naming the component at fault. That is
     * deliberate: a missing {@link Table}, two {@link Id}s or a type nothing can
     * encode are mistakes in code, identical on every row and every server, so
     * they are worth one loud failure at enable — where the developer is
     * watching — rather than an exception inside a background write three days
     * later, where nobody is.
     *
     * @param recordType the record class, annotated with {@link Table} and
     *                   carrying exactly one {@link Id}
     * @param <T>        the record type
     * @return the repository, the same instance every time
     * @throws IllegalArgumentException if the class cannot be stored
     */
    @SuppressWarnings("unchecked")
    public <T> @NotNull Repository<T> repository(@NotNull Class<T> recordType) {
        return (Repository<T>) repositories.computeIfAbsent(recordType, this::build);
    }

    /**
     * Whether this plugin has already registered a repository for a record type.
     *
     * @param recordType the record class
     * @return whether it is registered
     */
    public boolean has(@NotNull Class<?> recordType) {
        return repositories.containsKey(recordType);
    }

    /**
     * How many repositories this plugin registered, for diagnostics.
     *
     * @return the count
     */
    public int registered() {
        return repositories.size();
    }

    /**
     * Forgets this plugin's repositories.
     *
     * <p>Called by the library when the plugin is disabled; a consumer does not
     * need to. The tables stay exactly where they are — this drops the objects
     * that address them, not the data.
     */
    public void release() {
        repositories.clear();
    }

    private <T> Repository<T> build(Class<T> recordType) {
        // Compiled first, and outside the background work: a record that cannot
        // be stored must fail on the thread that asked, where the stack trace
        // points at the plugin's own onEnable.
        EntityModel<T> model = EntityModel.of(recordType);
        return new Repository<>(new GatedStorage(prepare(model)), model);
    }

    /**
     * Opens the shared connection if needed and creates the table, in the
     * background.
     *
     * <p>The returned future is what every operation on the repository waits
     * for, so a failure to prepare is a failure of everything that record type
     * does — which is the honest answer. A read answered with an empty list
     * would be indistinguishable from a database that is simply new.
     */
    private CompletableFuture<Storage> prepare(EntityModel<?> model) {
        Debug debug = Debug.of(plugin);
        return DatabaseRuntime.storage().thenCompose(storage ->
                storage.prepare(model).thenApply(report -> {
                    // Only the start where something changed is worth a line. On
                    // a server that has been running for months nothing changes
                    // on any start, and a line per table per boot is how a
                    // startup log stops being read at all.
                    String summary = report.summary();
                    if (summary != null) {
                        debug.log("Database schema " + summary + ".");
                    }
                    // A warning rather than a log line, and separate from the
                    // summary: an index that could not be created is not news
                    // about a successful start, it is a table running without an
                    // index the code asks for, and it stays that way until
                    // somebody acts.
                    String blocked = report.blocked();
                    if (blocked != null) {
                        debug.warn(blocked);
                    }
                    return storage;
                }).exceptionallyCompose(failure -> {
                    debug.error("The table for " + model.type().getSimpleName() + " ("
                            + model.table() + ") could not be prepared. Nothing of that type"
                            + " can be read or written until this is fixed: "
                            + failure.getMessage(), failure);
                    return CompletableFuture.failedFuture(failure);
                }));
    }

    @Override
    public String toString() {
        return "PluginDatabase[" + plugin.getName() + ", " + repositories.size() + " repositories]";
    }
}
