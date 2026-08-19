package net.exylia.lib.database.transfer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What happened to one table.
 *
 * <p>Per table rather than only in total, because the totals of a transfer
 * answer none of the questions asked after one: which table was skipped, which
 * one drifted, which one was already full. A single number cannot be acted on.
 *
 * @param table    the table name, exactly as {@code @Table} spells it
 * @param rows     rows exported from it, or written into it
 * @param skipped  whether the table was in the dump and nothing here claims it
 * @param drifted  whether the dump's column layout differs from the model's
 * @param note     one line of detail, or {@code null} when there is nothing to
 *                 add — the columns that drifted, why it was skipped
 * @since 1.36.0
 */
public record TableTransfer(@NotNull String table,
                            long rows,
                            boolean skipped,
                            boolean drifted,
                            @Nullable String note) {

    /** A table that was handled normally. */
    public static @NotNull TableTransfer of(@NotNull String table, long rows) {
        return new TableTransfer(table, rows, false, false, null);
    }

    /** A table in the dump that no registered record claims. */
    public static @NotNull TableTransfer skipped(@NotNull String table, @NotNull String why) {
        return new TableTransfer(table, 0L, true, false, why);
    }

    /** A table whose stored layout no longer matches the record's. */
    public static @NotNull TableTransfer drifted(@NotNull String table, long rows,
                                                 @NotNull String what) {
        return new TableTransfer(table, rows, false, true, what);
    }

    @Override
    public String toString() {
        return table + " (" + rows + " rows" + (skipped ? ", skipped" : "")
                + (drifted ? ", layout drifted" : "") + ')';
    }
}
