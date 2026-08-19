package net.exylia.lib.database.transfer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * What a transfer did, table by table.
 *
 * <p>The whole answer, and the only one: an export or an import completes its
 * future with one of these whether it worked, half worked or did not run at
 * all. Nothing here throws to say a table was skipped.
 *
 * <pre>{@code
 * Transfers.of(this).export(folder).thenAccept(report -> {
 *     if (report.outcome() == TransferOutcome.SUCCESS) {
 *         getLogger().info("Exported " + report.rows() + " rows to " + report.file());
 *     } else {
 *         report.problems().forEach(getLogger()::warning);
 *     }
 * });
 * }</pre>
 *
 * @param outcome  whether it worked, half worked, or did not run
 * @param file     the file written or read, or {@code null} when it never got
 *                 that far
 * @param tables   one entry per table touched, in the order they were handled
 * @param rows     rows exported or written, across every table
 * @param took     wall time, including the file and the database
 * @param problems everything worth telling somebody about, one line each;
 *                 empty on a clean run
 * @since 1.36.0
 */
public record TransferReport(@NotNull TransferOutcome outcome,
                             @Nullable Path file,
                             @NotNull List<TableTransfer> tables,
                             long rows,
                             @NotNull Duration took,
                             @NotNull List<String> problems) {

    /** Copies the lists, so a report cannot change after it is handed over. */
    public TransferReport {
        tables = List.copyOf(tables);
        problems = List.copyOf(problems);
    }

    /** A transfer that never ran, with the one line saying why. */
    public static @NotNull TransferReport failed(@NotNull String why, @NotNull Duration took) {
        return new TransferReport(TransferOutcome.FAILED, null, List.of(), 0L, took, List.of(why));
    }

    /** Whether everything asked for happened. */
    public boolean successful() {
        return outcome == TransferOutcome.SUCCESS;
    }

    /** The table names touched, in order, for a one-line summary. */
    public @NotNull List<String> tableNames() {
        return tables.stream().map(TableTransfer::table).toList();
    }

    @Override
    public String toString() {
        return "TransferReport[" + outcome + ", " + tables.size() + " tables, " + rows
                + " rows, " + took.toMillis() + "ms]";
    }
}
