package net.exylia.lib.config;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Consumer;

/**
 * A live handle to one config file, bound to a schema record.
 *
 * <p>Obtained from {@link Configs#define}, and normally kept in a field for the
 * lifetime of the plugin:
 *
 * <pre>{@code
 * private ConfigFile<Settings> settings;
 *
 * @Override
 * public void onEnable() {
 *     settings = Configs.define(this, "config", Settings.class).load();
 *
 *     int poolSize = settings.get().database().poolSize();
 * }
 * }</pre>
 *
 * <h2>Reading</h2>
 * {@link #get()} returns an immutable snapshot. Reading a value is a field
 * access on a record: no map lookup, no parsing, no reflection. That makes it
 * safe on hot paths, unlike calling {@code config.getInt(...)} per event.
 *
 * <p>Do not cache the record itself in your own fields. Call {@link #get()} each
 * time you need a value; it is a single volatile read, and it is what makes a
 * reload visible to your code.
 *
 * <h2>Reloading</h2>
 * {@link #reload()} parses the file and swaps the snapshot atomically. Code
 * already running keeps using the old snapshot until it finishes, and no reader
 * ever sees a half-applied config.
 *
 * <h2>Thread safety</h2>
 * Every method is safe to call from any thread. Reading is lock-free.
 *
 * @param <T> the record type describing this file
 * @since 1.1.0
 */
public interface ConfigFile<T> {

    /**
     * Returns the current values.
     *
     * <p>The returned record is immutable and never {@code null}: if the file is
     * missing or broken, this is the schema's defaults.
     *
     * @return the current snapshot
     */
    @NotNull T get();

    /**
     * Re-reads the file from disk and publishes the new values.
     *
     * <p>If the file cannot be parsed, the previous values stay in place rather
     * than reverting to defaults, so a bad edit during a reload does not wipe a
     * running server's settings. The problem is reported through
     * {@link #issues()}.
     *
     * <p>This performs file I/O, so call it from
     * {@link net.exylia.lib.task.TaskScheduler#runAsync(Runnable)} unless you
     * are already reacting to a command where a short pause is acceptable.
     *
     * @return the issues found while reloading, empty when the file was clean
     */
    @NotNull List<ConfigIssue> reload();

    /**
     * Runs an action whenever this file is reloaded.
     *
     * <p>Use it to rebuild things derived from config, such as a scheduled task
     * whose period changed:
     *
     * <pre>{@code
     * settings.onReload(values -> restartTimer(values.saveIntervalTicks()));
     * }</pre>
     *
     * <p>Listeners run on the thread that called {@link #reload()}, in
     * registration order. An exception in one listener is logged and does not
     * stop the others.
     *
     * @param listener receives the new values
     */
    void onReload(@NotNull Consumer<T> listener);

    /**
     * Writes the current values back to disk.
     *
     * <p>Only needed after {@link #update}. Loading already writes the file when
     * keys were added or migrated.
     */
    void save();

    /**
     * Changes values and writes them to disk.
     *
     * <p>For settings the plugin itself owns, such as a stored state or a
     * generated identifier:
     *
     * <pre>{@code
     * settings.update(current -> current.withServerId(newId));
     * }</pre>
     *
     * <p>Comments and keys the plugin does not own are preserved. The new
     * snapshot is published before this method returns.
     *
     * @param change maps the current values to the new ones
     */
    void update(@NotNull java.util.function.UnaryOperator<T> change);

    /**
     * Returns the problems found the last time this file was loaded.
     *
     * <p>Already logged when they were found; this is for showing them again,
     * for example in the output of a reload command.
     *
     * @return an immutable list, empty when the file was clean
     */
    @NotNull List<ConfigIssue> issues();

    /**
     * Returns this file's name, without extension or folder.
     *
     * @return for example {@code config} or {@code menus/main}
     */
    @NotNull String name();

    /**
     * Returns a read-only description of this file's record type: its keys,
     * declared types and {@link Comment} lines.
     *
     * <pre>{@code
     * for (Schema.Field field : storage.schema().fields()) {
     *     render(field.key(), field.type(), field.comments());
     * }
     * }</pre>
     *
     * <p>It describes the <b>type</b>, not the values, so it does not change
     * when the file does: a schema taken before a {@link #reload()} is still
     * valid after it, and two files of one record type project equal schemas.
     * Reading values stays {@link #get()}; writing stays {@link #update}.
     *
     * <p>Safe to call from any thread. The projection is taken once per file, so
     * this is a field read rather than a fresh analysis.
     *
     * <p>This accessor was added in 1.50.0 to an interface the library has
     * always implemented alone ({@code config.internal.ConfigFileImpl}); it was
     * never documented as an extension point, so no third-party implementation
     * is stranded by it.
     *
     * @return the projection; never {@code null}
     * @since 1.50.0
     */
    @NotNull Schema schema();
}
