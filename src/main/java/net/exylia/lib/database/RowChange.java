package net.exylia.lib.database;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A row another server of the network wrote or deleted.
 *
 * <p>Delivered to {@link PluginDatabase#onRemoteChange} listeners. It names the
 * row, never carries it: the listener reads the row again through its
 * repository, which is answered from Redis and is therefore the value the
 * other server just stored — or empty, when what happened was a delete.
 *
 * @param table the table, as the record's {@link Table} names it
 * @param id    the row id in record form, or {@code null} when the whole table
 *              was dropped and every row of it has to be read again
 * @since 1.155.0
 */
public record RowChange(@NotNull String table, @Nullable Object id) {

    /** Whether every row of the table changed rather than one. */
    public boolean wholeTable() {
        return id == null;
    }
}
