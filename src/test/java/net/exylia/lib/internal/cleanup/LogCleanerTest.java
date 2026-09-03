package net.exylia.lib.internal.cleanup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers what the sweep deletes and, more importantly, what it refuses to
 * delete: this is the part of the module that removes a server owner's files.
 */
class LogCleanerTest {

    private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");

    @TempDir
    Path logs;

    @Test
    void deletesLogsOlderThanTheRetention() throws IOException {
        Path old = aged("2026-08-20-1.log.gz", 13);
        Path recent = aged("2026-09-01-1.log.gz", 1);

        assertEquals(1, sweep(7));

        assertFalse(Files.exists(old), "a log older than the retention should go");
        assertTrue(Files.exists(recent), "a log inside the retention should stay");
    }

    @Test
    void neverDeletesTheLogTheServerIsWriting() throws IOException {
        Path active = aged("latest.log", 30);

        assertEquals(0, sweep(7));

        assertTrue(Files.exists(active), "latest.log is open; deleting it frees nothing");
    }

    @Test
    void leavesAnythingThatIsNotALog() throws IOException {
        Path notes = aged("notes.txt", 30);
        Path folder = Files.createDirectory(logs.resolve("archive"));

        assertEquals(0, sweep(7));

        assertTrue(Files.exists(notes), "only log files are the module's business");
        assertTrue(Files.exists(folder), "a folder is never deleted");
    }

    @Test
    void readsARetentionBelowOneDayAsOneDay() throws IOException {
        Path today = aged("2026-09-02-1.log.gz", 0);
        Path yesterday = aged("2026-09-01-1.log.gz", 2);

        assertEquals(1, sweep(0));

        assertTrue(Files.exists(today), "a zero retention must not delete today's logs");
        assertFalse(Files.exists(yesterday));
    }

    @Test
    void doesNothingWhenTheFolderIsAbsent() {
        List<Path> failures = new ArrayList<>();

        assertEquals(0, LogCleaner.sweep(logs.resolve("missing"), 7, NOW, failures::add));

        assertTrue(failures.isEmpty(), "a server with no logs folder is not a problem to report");
    }

    private int sweep(int keepDays) {
        return LogCleaner.sweep(logs, keepDays, NOW, path -> {
            throw new AssertionError("unexpected failure on " + path);
        });
    }

    private Path aged(String name, long days) throws IOException {
        Path file = Files.writeString(logs.resolve(name), "log");
        Files.setLastModifiedTime(file, FileTime.from(NOW.minus(Duration.ofDays(days))));
        return file;
    }
}
