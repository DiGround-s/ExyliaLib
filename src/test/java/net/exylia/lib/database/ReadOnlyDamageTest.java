package net.exylia.lib.database;

import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.H2Guard;
import net.exylia.lib.database.internal.SqlBackend;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.database.internal.SqlStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An embedded database H2 turned read-only is reported once, not once per query. */
class ReadOnlyDamageTest {

    @Table("read_only_runs")
    record Run(@Id String id, @Column long runs) {
    }

    private static final EntityModel<Run> MODEL = EntityModel.of(Run.class);

    @Test
    @DisplayName("a read-only store gives one report and one full error; the repeats stay quiet but still fail")
    void readOnlyIsReportedOnce(@TempDir Path dir) throws Exception {
        SqlSettings settings = SqlSettings.file("h2", dir.resolve("h2"));
        SqlBackend writable = SqlBackend.open(settings, "ReadOnlyDamageTest");
        writable.ensureTable(MODEL);
        try (Connection connection = new org.h2.Driver().connect(writable.dialect().jdbcUrl(settings), credentials());
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO \"read_only_runs\" VALUES ('resource_drop', 1)");
            statement.execute("SHUTDOWN");
        }
        writable.close();

        SqlBackend backend = SqlBackend.open(settings.property("ACCESS_MODE_DATA", "r"), "ReadOnlyDamageTest");
        try {
            List<String> warnings = new ArrayList<>();
            List<Throwable> printed = new ArrayList<>();
            Repository<Run> runs = new Repository<>(new SqlStorage(backend, Runnable::run, warnings::add),
                    MODEL, (message, failure) -> printed.add(failure));

            for (int attempt = 0; attempt < 5; attempt++) {
                CompletionException failure = assertThrows(CompletionException.class,
                        () -> runs.increment(new Run("resource_drop", 1), "runs").join(),
                        "every caller still receives the failure");
                assertTrue(H2Guard.isDamage(failure), "90097 is damage: " + failure);
            }
            assertEquals(1, warnings.size(), "one report: " + warnings);
            assertTrue(warnings.get(0).startsWith("DATABASE READ ONLY"), warnings.get(0));
            assertEquals(1, printed.size(), "one full error, then the repeats are counted");
            assertEquals(1L, runs.find("resource_drop").join().map(Run::runs).orElseThrow(), "reads still work");
        } finally {
            backend.close();
        }
    }

    @Test
    @DisplayName("the start-up check reads the file read-only and leaves the pool writable")
    void startupCheckDoesNotLeakReadOnly(@TempDir Path dir) throws Exception {
        SqlSettings settings = SqlSettings.file("h2", dir.resolve("h2"));
        try (Connection connection = new org.h2.Driver().connect("jdbc:h2:file:" + dir.resolve("h2"), credentials());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE \"read_only_runs\" (\"id\" VARCHAR(64) PRIMARY KEY, \"runs\" BIGINT)");
        }
        List<String> log = new ArrayList<>();
        H2Guard.prepare(settings, (level, message) -> log.add(message));
        assertTrue(log.isEmpty(), "a healthy, writable file says nothing: " + log);

        SqlBackend backend = SqlBackend.open(settings, "ReadOnlyDamageTest");
        try {
            Repository<Run> runs = new Repository<>(new SqlStorage(backend, Runnable::run, log::add), MODEL);
            runs.increment(new Run("resource_drop", 1), "runs").join();
            assertEquals(1L, runs.find("resource_drop").join().map(Run::runs).orElseThrow());
        } finally {
            backend.close();
        }
    }

    @Test
    @DisplayName("ordinary errors and table permission errors are not damage")
    void ordinaryErrorsAreNotDamage() {
        assertTrue(H2Guard.isDamage(new SQLException("read only", "HY000", 90097)));
        assertFalse(H2Guard.isDamage(new SQLException("not enough rights", "42501", 90096)));
        assertFalse(H2Guard.isDamage(new SQLException("syntax", "42000", 42000)));
    }

    private static java.util.Properties credentials() {
        java.util.Properties properties = new java.util.Properties();
        properties.setProperty("user", "sa");
        properties.setProperty("password", "");
        return properties;
    }
}
