package net.exylia.lib.database;

import net.exylia.lib.database.internal.Dialect;
import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.SqlBackend;
import net.exylia.lib.database.internal.SqlSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two processes on one embedded file.
 *
 * <p>An H2 file belongs to one JVM. A second one that opens it is refused with
 * "The file is locked" — what a server sees when two plugins point at the same
 * file, or when somebody opens a viewer while the server runs.
 * {@code AUTO_SERVER} makes the first process serve the file to the rest.
 *
 * <p>The lock is between processes, so no test inside one JVM can reproduce it:
 * H2 hands the second caller the database it already has open. It was verified
 * out of band with two real JVMs on 2.2.224 — the first opened the file, the
 * second was refused with "The file is locked: /tmp/h2lock/db.mv.db", and with
 * AUTO_SERVER on that same second JVM opened it and read the first one's row.
 *
 * <p>What is left to guard here is the half that would break silently: that
 * the parameter reaches the URL, that it is off unless asked for, and that the
 * engine still works with it on — H2 refuses AUTO_SERVER together with
 * DB_CLOSE_ON_EXIT at connect time, so a server would simply not start.
 */
class H2AutoServerTest {

    private static final Dialect H2 = Dialect.of("h2");

    @Table("shared_rows")
    record Row(@Id UUID id, @Column int value) {
    }

    @Test
    @DisplayName("off by default, so the URL is exactly what it always was")
    void offByDefault() {
        String url = H2.jdbcUrl(SqlSettings.file("h2", Path.of("/srv/plugins/Practice/data")));

        assertTrue(url.contains("DB_CLOSE_DELAY=-1"), url);
        assertFalse(url.contains("AUTO_SERVER"), url);
    }

    @Test
    @DisplayName("the setting reaches the URL, since H2 reads it only from there")
    void settingReachesTheUrl() {
        String url = H2.jdbcUrl(SqlSettings.file("h2", Path.of("/srv/data"))
                .property("AUTO_SERVER", "TRUE"));

        assertTrue(url.contains("AUTO_SERVER=TRUE"), url);
        // The flag it cannot be combined with is never emitted, at any setting:
        // H2 throws at connect if it meets AUTO_SERVER, so a server configured
        // with both would simply not start.
        assertFalse(url.contains("DB_CLOSE_ON_EXIT"), url);
    }

    @Test
    @DisplayName("the settings turn the option into the URL parameter H2 reads")
    void configuredOptionReachesTheUrl(@TempDir Path folder) {
        DatabaseSettings on = new DatabaseSettings(new DatabaseSettings.Database("h2",
                new DatabaseSettings.Settings(), new DatabaseSettings.H2("database/h2", true),
                null, null, null, null, null));

        assertTrue(H2.jdbcUrl(on.toSql(folder)).contains("AUTO_SERVER=TRUE"));
        // And a file left alone stays single-process, which is the right default:
        // the mode opens a TCP port, and most servers run one process per file.
        assertFalse(H2.jdbcUrl(new DatabaseSettings().toSql(folder)).contains("AUTO_SERVER"));
    }

    @Test
    @DisplayName("the mode still opens the file, reads it and writes to it")
    void theFileStillWorksWithItOn(@TempDir Path folder) throws Exception {
        // The lock only happens between JVMs — inside one, H2 hands back the
        // database it already has open, so no test here can reproduce it. What
        // this can prove is the half that would break silently: that the extra
        // parameter does not stop the engine from working. H2 refuses
        // AUTO_SERVER together with DB_CLOSE_ON_EXIT at connect time, and this
        // is what would catch the day somebody adds that flag.
        SqlSettings settings = SqlSettings.file("h2", folder.resolve("shared"))
                .property("AUTO_SERVER", "TRUE");
        EntityModel<Row> model = EntityModel.of(Row.class);
        UUID id = UUID.randomUUID();

        try (SqlBackend backend = SqlBackend.open(settings, "auto-server")) {
            backend.ensureTable(model);
            backend.save(model, new Row(id, 7));

            Row found = backend.find(model, id);
            assertNotNull(found, "the row is readable with the mode on");
            assertEquals(7, found.value());
        }
    }
}
