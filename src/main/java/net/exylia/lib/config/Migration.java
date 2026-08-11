package net.exylia.lib.config;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * One step that carries an existing config file forward to a newer layout.
 *
 * <p>Configs live in the hands of server owners, who have already edited them.
 * When a key is renamed or a unit changes, the file on disk has to be rewritten
 * or the user silently loses the value they set. Migrations describe that
 * rewrite once, in code:
 *
 * <pre>{@code
 * Configs.define(plugin, "database", DatabaseConfig.class)
 *        .version(3)
 *        .migration(1, Migration.rename("mysql.pool", "mysql.pool-size"))
 *        .migration(2, Migration.transform("cache.ttl", seconds -> seconds / 60))
 *        .load();
 * }</pre>
 *
 * <p>Each step is bound to the version it upgrades <em>from</em>, so a file at
 * version 1 runs steps 1 and 2, while a file already at 2 runs only step 2. The
 * file records the version it reached, so every step runs exactly once no matter
 * how many times the server restarts.
 *
 * <p>A file with no version marker is assumed to be version {@code 1}, which
 * makes it safe to add migrations to a config that shipped without them.
 *
 * @since 1.1.0
 */
@FunctionalInterface
public interface Migration {

    /**
     * Rewrites the raw contents of a file.
     *
     * <p>Runs on the flat, dotted view of the file before it is bound to the
     * schema, so it can touch keys the schema no longer knows about.
     *
     * @param data the file contents, mutable
     */
    void apply(@NotNull MutableConfig data);

    /**
     * Moves a value to a new key, keeping what the user had set.
     *
     * <p>Does nothing if the old key is absent, or if the new key already has a
     * value, so re-running it can never overwrite newer data.
     *
     * @param from the old dotted path
     * @param to   the new dotted path
     * @return the migration step
     */
    static @NotNull Migration rename(@NotNull String from, @NotNull String to) {
        return data -> {
            if (data.contains(from) && !data.contains(to)) {
                data.set(to, data.get(from));
            }
            data.remove(from);
        };
    }

    /**
     * Deletes a key that no longer means anything.
     *
     * <p>Prefer this over leaving the key behind: a setting that no longer does
     * what it says is worse than no setting at all.
     *
     * @param path the dotted path to delete
     * @return the migration step
     */
    static @NotNull Migration remove(@NotNull String path) {
        return data -> data.remove(path);
    }

    /**
     * Rewrites a value in place, for when the meaning or unit of a key changes.
     *
     * <p>The function is not called when the key is absent, so it never has to
     * handle {@code null}.
     *
     * @param path    the dotted path to rewrite
     * @param rewrite maps the old value to the new one
     * @return the migration step
     */
    static @NotNull Migration transform(@NotNull String path, @NotNull UnaryOperator<Object> rewrite) {
        return data -> {
            Object current = data.get(path);
            if (current != null) {
                data.set(path, rewrite.apply(current));
            }
        };
    }

    /**
     * Runs several steps in order, as a single version bump.
     *
     * @param steps the steps to run
     * @return the combined migration
     */
    static @NotNull Migration all(@NotNull Migration... steps) {
        List<Migration> copy = List.of(steps);
        return data -> copy.forEach(step -> step.apply(data));
    }
}
