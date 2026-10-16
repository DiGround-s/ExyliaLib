package net.exylia.lib.web;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Where a plugin keeps entries the web editor creates, edits and deletes: mines, kits, arenas,
 * anything stored in a database or anywhere else that is not one config file.
 *
 * <p>The plugin describes one entry as a {@code record} with defaults, the same way a config is
 * declared, and maps it to and from what it stores. The editor draws the form from the record,
 * and a change is read through the config binder, entry by entry, before the store sees it.
 *
 * <pre>{@code
 * WebEditor.of(this).entries("mines", MineEdit.class, new EntryStore<>() {
 *     public Map<String, MineEdit> entries() { ... }            // what exists now
 *     public List<String> check(String id, MineEdit mine, boolean created) { ... }
 *     public CompletableFuture<Void> save(String id, MineEdit mine, boolean created) { ... }
 *     public CompletableFuture<Void> delete(String id) { ... }
 * });
 * }</pre>
 *
 * <h2>Threads</h2>
 * Every method is called on the global thread, where the plugin's own commands would run. A
 * write answers with a future: hand the database work off as the plugin always does, and
 * complete the future when it is done. The admin is told the outcome once every future of the
 * change has completed; one completed exceptionally is reported with its message.
 *
 * @param <R> one entry, a record with a no-arg constructor holding the defaults
 * @since 1.306.0
 */
public interface EntryStore<R extends Record> {

    /**
     * Every entry there is now, by id, in the order the editor lists them.
     *
     * <p>Read for every snapshot and every apply, so it should come from memory.
     */
    @NotNull Map<String, R> entries();

    /**
     * What an entry the admin adds starts as; the browser's values go on top.
     *
     * @param id the id typed in the browser
     * @return the defaults, or {@code null} for the record's no-arg constructor
     */
    default @Nullable R blank(@NotNull String id) {
        return null;
    }

    /**
     * What is wrong with an entry before it is written: an id the plugin does not accept, a
     * world that does not exist, two values that contradict each other.
     *
     * <p>Any answer refuses the whole change for this subject, so a half-applied set of entries
     * never reaches the store.
     *
     * @param created whether the entry is new
     * @return one line per problem; an empty list accepts
     */
    default @NotNull List<String> check(@NotNull String id, @NotNull R value, boolean created) {
        return List.of();
    }

    /**
     * Creates or replaces one entry.
     *
     * @param created whether the entry is new
     * @return completes when written; completes exceptionally, with a message for the admin,
     *         when it could not be
     */
    @NotNull CompletableFuture<Void> save(@NotNull String id, @NotNull R value, boolean created);

    /**
     * Deletes one entry. Deletions are always shown first and need {@code confirm}.
     *
     * @return completes when deleted; exceptionally when it could not be
     */
    @NotNull CompletableFuture<Void> delete(@NotNull String id);
}
