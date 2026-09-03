package net.exylia.lib.internal.cleanup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Deletes the log files a server stopped needing.
 *
 * <p>Pure file work: no Bukkit, no clock of its own and no logger, so the sweep
 * can be exercised over a temporary folder in a test. {@link CleanupRuntime} is
 * what gives it a folder, a retention and somewhere to report to.
 *
 * @since 1.90.0
 */
final class LogCleaner {

    /**
     * The log the server is writing to right now. Deleting it does not free the
     * space — the file stays open — and the server keeps appending to a file
     * that no longer has a name.
     */
    private static final String ACTIVE = "latest.log";

    private LogCleaner() {
        throw new AssertionError("No instances.");
    }

    /**
     * Deletes every log in {@code folder} last written to more than
     * {@code keepDays} days ago.
     *
     * <p>Conservative on purpose, because this deletes files the owner did not
     * ask about one by one: only regular files are considered, only names that
     * look like a log ({@code .log}, {@code .log.gz}, {@code .gz}), and never
     * {@value #ACTIVE}. Anything else a plugin or an admin left in the folder
     * stays where it is.
     *
     * <p>A file that cannot be deleted — held open on Windows, or owned by
     * another user — is reported and skipped rather than stopping the sweep.
     *
     * @param folder   the log folder; nothing happens if it does not exist
     * @param keepDays days of logs to keep, at least 1
     * @param now      the instant to measure age from
     * @param failed   told about each file that could not be deleted
     * @return how many files were deleted
     */
    static int sweep(Path folder, int keepDays, Instant now, Consumer<Path> failed) {
        if (!Files.isDirectory(folder)) return 0;
        Instant cutoff = now.minus(Duration.ofDays(Math.max(1, keepDays)));
        int deleted = 0;
        try (Stream<Path> entries = Files.list(folder)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (!isExpiredLog(entry, cutoff)) continue;
                try {
                    Files.delete(entry);
                    deleted++;
                } catch (IOException exception) {
                    failed.accept(entry);
                }
            }
        } catch (IOException exception) {
            failed.accept(folder);
        }
        return deleted;
    }

    /** Whether one entry is a log file old enough to go. */
    private static boolean isExpiredLog(Path entry, Instant cutoff) {
        String name = entry.getFileName().toString();
        if (name.equals(ACTIVE) || !isLog(name)) return false;
        try {
            return !Files.isDirectory(entry)
                    && Files.isRegularFile(entry)
                    && Files.getLastModifiedTime(entry).toInstant().isBefore(cutoff);
        } catch (IOException exception) {
            // Age unknown means age unproven, and an unproven file is kept.
            return false;
        }
    }

    /** Whether a name is one of the shapes a server writes its logs under. */
    private static boolean isLog(String name) {
        return name.endsWith(".log") || name.endsWith(".log.gz") || name.endsWith(".gz");
    }
}
