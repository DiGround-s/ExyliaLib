package net.exylia.lib.database.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * What bringing a table up to date actually changed.
 *
 * <p>Returned rather than logged, so the caller decides whether it is worth a
 * console line. It usually is not: on a server that has been running for months
 * nothing changes on any start, and a line per table per boot is how a startup
 * log becomes something nobody reads. The one start where a column was added is
 * the one worth saying out loud.
 *
 * @param table            the table, folded as it is stored
 * @param createdTable     whether the table did not exist and was created
 * @param addedColumns     columns added to a table that already existed
 * @param createdIndexes   indexes created, by name
 * @param blockedIndexes   indexes that could not be created because their name
 *                         is held by an index over different columns
 * @since 1.24.0
 */
public record SchemaReport(@NotNull String table,
                           boolean createdTable,
                           @NotNull List<String> addedColumns,
                           @NotNull List<String> createdIndexes,
                           @NotNull List<String> blockedIndexes) {

    /** Compact constructor, defensive: the lists outlive the builder that made them. */
    public SchemaReport {
        addedColumns = List.copyOf(addedColumns);
        createdIndexes = List.copyOf(createdIndexes);
        blockedIndexes = List.copyOf(blockedIndexes);
    }

    /**
     * A report of a schema step that created no index it could not create.
     *
     * <p>For the backends where the situation cannot arise — Mongo drops and
     * rebuilds an index it owns rather than leaving it — and for callers built
     * before the fourth list existed.
     *
     * @param table          the table
     * @param createdTable   whether the table was created
     * @param addedColumns   columns added
     * @param createdIndexes indexes created
     */
    public SchemaReport(@NotNull String table,
                        boolean createdTable,
                        @NotNull List<String> addedColumns,
                        @NotNull List<String> createdIndexes) {
        this(table, createdTable, addedColumns, createdIndexes, List.of());
    }

    /** Whether anything at all changed. */
    public boolean changed() {
        return createdTable || !addedColumns.isEmpty() || !createdIndexes.isEmpty();
    }

    /**
     * A line naming the indexes that could not be created, or {@code null}.
     *
     * <p>Separate from {@link #summary()} because it is not news about a
     * successful start: it is a problem, it is permanent until somebody acts, and
     * it deserves a warning rather than a log line. It happens when an
     * {@code @Index} keeps its explicit name and changes its columns — the old
     * index still holds the name, {@code CREATE INDEX} either does nothing or
     * fails as a duplicate, and without this the table would silently go without
     * the index the code now asks for, on every start, forever.
     *
     * @return the warning, or {@code null} when nothing was blocked
     */
    public @Nullable String blocked() {
        if (blockedIndexes.isEmpty()) {
            return null;
        }
        return table + ": the index" + (blockedIndexes.size() == 1 ? " " : "es ") + blockedIndexes
                + " could not be created, because an index of that name already exists over"
                + " different columns. The table is running without it. Drop the old index, or"
                + " give the new one a different name on its @Index.";
    }

    /**
     * A line for the console, or {@code null} when there is nothing to say.
     *
     * @return the summary, or {@code null} when nothing changed
     */
    public @Nullable String summary() {
        if (!changed()) {
            return null;
        }
        StringBuilder line = new StringBuilder(64).append(table).append(": ");
        if (createdTable) {
            line.append("created");
        }
        if (!addedColumns.isEmpty()) {
            line.append(createdTable ? ", " : "").append("added ").append(addedColumns);
        }
        if (!createdIndexes.isEmpty()) {
            line.append(createdTable || !addedColumns.isEmpty() ? ", " : "")
                    .append("indexed ").append(createdIndexes);
        }
        return line.toString();
    }
}
