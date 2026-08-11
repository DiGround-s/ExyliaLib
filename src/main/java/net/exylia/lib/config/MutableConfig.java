package net.exylia.lib.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The raw contents of a config file while a {@link Migration} rewrites it.
 *
 * <p>Deliberately untyped: a migration works on a file written for an older
 * version of the schema, so the current record types do not describe it.
 *
 * <p>Paths are dotted, for example {@code database.mysql.host}.
 *
 * @since 1.1.0
 */
public interface MutableConfig {

    /**
     * Reads a raw value.
     *
     * @param path dotted path
     * @return the value, or {@code null} if the key is absent
     */
    @Nullable Object get(@NotNull String path);

    /**
     * Writes a raw value, creating intermediate sections as needed.
     *
     * @param path  dotted path
     * @param value the value to store
     */
    void set(@NotNull String path, @Nullable Object value);

    /**
     * Deletes a key and everything under it.
     *
     * @param path dotted path
     */
    void remove(@NotNull String path);

    /**
     * Returns whether a key is present.
     *
     * @param path dotted path
     * @return {@code true} if the file has that key
     */
    boolean contains(@NotNull String path);
}
