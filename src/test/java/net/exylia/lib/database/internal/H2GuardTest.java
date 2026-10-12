package net.exylia.lib.database.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A torn last write is rolled back at startup and the rows before it come back. */
class H2GuardTest {

    @Test
    void repairsATornNewestChunk(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("h2");
        SqlSettings settings = SqlSettings.file("h2", base);
        try (Connection connection = connect(base); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE players (id INT PRIMARY KEY, name VARCHAR(64))");
            for (int batch = 0; batch < 3; batch++) {
                for (int row = 0; row < 200; row++) {
                    int id = batch * 200 + row;
                    statement.execute("INSERT INTO players VALUES (" + id + ", 'player-" + id + "')");
                }
                // One chunk per batch.
                statement.execute("CHECKPOINT");
            }
        }

        // Garbage over the body of the newest chunk, header and footer left intact: what a crash mid-flush leaves.
        Path file = dir.resolve("h2.mv.db");
        long[] newest = H2Guard.chunks(file).get(0);
        try (RandomAccessFile raw = new RandomAccessFile(file.toFile(), "rw")) {
            byte[] garbage = new byte[(int) (newest[2] * 4096 - 512 - 256)];
            new Random(7).nextBytes(garbage);
            raw.seek(newest[1] * 4096 + 512);
            raw.write(garbage);
        }
        assertTrue(H2Guard.isCorruption(H2Guard.check(base, settings)), "the damage must be detected");

        List<String> log = new ArrayList<>();
        H2Guard.prepare(settings, (Level level, String message) -> log.add(message));

        assertNull(H2Guard.check(base, settings), "the repaired file reads in full");
        try (Connection connection = connect(base); Statement statement = connection.createStatement();
             ResultSet count = statement.executeQuery("SELECT COUNT(*) FROM players WHERE id < 400")) {
            count.next();
            assertEquals(400, count.getInt(1), "the rows written before the torn chunk are back");
        }
        assertTrue(log.get(log.size() - 1).contains("rolling back"), String.join("\n", log));
        try (var kept = Files.list(dir.resolve("corrupted"))) {
            assertNotNull(kept.findFirst().orElse(null), "the damaged file is kept");
        }
    }

    private static Connection connect(Path base) throws Exception {
        Properties credentials = new Properties();
        credentials.setProperty("user", "sa");
        credentials.setProperty("password", "");
        return new org.h2.Driver().connect("jdbc:h2:file:" + base, credentials);
    }
}
