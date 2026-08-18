package net.exylia.lib.database;

import net.exylia.lib.FakeServer;
import net.exylia.lib.config.Configs;
import net.exylia.lib.database.internal.DatabaseRuntime;
import net.exylia.lib.database.internal.SqlSettings;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a server owner's {@code database.yml} is promised.
 *
 * <p>The layout is ExyliaCommons', on purpose: a server already running commons
 * plugins must keep the credentials it has. These read real files from disk,
 * because every failure this class exists to prevent — a password pruned as an
 * unknown key, a section stringified into a value, a rename that drops what it
 * was carrying — happens in the binder, not in the record.
 */
class DatabaseSettingsTest {

    @TempDir
    Path folder;

    private static final AtomicInteger PLUGINS = new AtomicInteger();

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Configs.releaseAll();
        plugin = FakeServer.newPlugin("DbSettings" + PLUGINS.incrementAndGet(), folder.toFile());
        DatabaseRuntime.init(plugin);
    }

    @AfterEach
    void tearDown() {
        DatabaseRuntime.shutdown();
        Configs.releaseAll();
    }

    private void write(String yaml) throws IOException {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("database.yml"), yaml);
    }

    private String read() throws IOException {
        return Files.readString(folder.resolve("database.yml"));
    }

    private SqlSettings resolved() {
        return DatabaseRuntime.settings(plugin);
    }

    @Test
    @DisplayName("\"a file ExyliaCommons wrote keeps its credentials\"")
    void commonsFileSurvives() throws IOException {
        write("""
                database:
                  type: mysql
                  mysql:
                    host: 10.0.0.5
                    port: 3306
                    database: practice
                    username: exylia
                    password: hunter2
                """);

        SqlSettings settings = resolved();
        assertEquals("mysql", settings.engine());
        assertEquals("10.0.0.5", settings.host());
        assertEquals("practice", settings.database());
        assertEquals("exylia", settings.user());
        assertEquals("hunter2", settings.password());

        // And they are still in the file afterwards, not pruned on the way out.
        assertTrue(read().contains("password: hunter2"));
    }

    @Test
    @DisplayName("\"a section is never bound into a value\"")
    void aSectionIsNotAValue() throws IOException {
        // The bug this guards: String.valueOf on a ConfigurationSection wrote
        // "MemorySection[path='database', root='YamlConfiguration']" into the
        // file as though an owner had typed it.
        write("""
                database:
                  type: h2
                  h2:
                    file: database/h2
                """);

        resolved();
        assertFalse(read().contains("MemorySection"),
                "a section was stringified into a value");
    }

    @Test
    @DisplayName("\"only the block the type names is read\"")
    void onlyTheNamedBlockIsRead() throws IOException {
        write("""
                database:
                  type: postgresql
                  mysql:
                    host: 10.0.0.5
                    database: wrong
                  postgresql:
                    host: 10.0.0.9
                    port: 5432
                    database: survival
                    username: exylia
                    password: secret
                """);

        SqlSettings settings = resolved();
        assertEquals("postgresql", settings.engine());
        assertEquals("10.0.0.9", settings.host());
        assertEquals("survival", settings.database());
    }

    @Test
    @DisplayName("\"the flat layout of 1.24 is carried into the block its engine names\"")
    void theFlatLayoutMigrates() throws IOException {
        write("""
                type: postgresql
                file: database/h2
                host: 10.0.0.9
                port: 5432
                database: survival
                user: exylia
                password: secret
                pool-size: 12
                config-version: 1
                """);

        SqlSettings settings = resolved();
        assertEquals("postgresql", settings.engine());
        assertEquals("10.0.0.9", settings.host());
        assertEquals("survival", settings.database());
        assertEquals("exylia", settings.user());
        assertEquals("secret", settings.password());
        assertEquals(12, settings.poolSize());

        String file = read();
        // Under postgresql, not mysql: the flat layout had one set of fields and
        // they belong to whichever engine it named.
        assertTrue(file.contains("postgresql:"));
        assertTrue(file.contains("host: 10.0.0.9"));
        assertTrue(file.contains("config-version: 2"));
    }

    @Test
    @DisplayName("\"an h2 file is resolved against the plugin folder\"")
    void embeddedFileIsRelative() throws IOException {
        write("""
                database:
                  type: h2
                  h2:
                    file: data/players
                """);

        SqlSettings settings = resolved();
        assertTrue(settings.embedded());
        assertEquals(folder.resolve("data/players"), settings.file());
    }

    @Test
    @DisplayName("\"a mongo connection string wins over the fields above it\"")
    void mongoConnectionString() throws IOException {
        write("""
                database:
                  type: mongodb
                  mongodb:
                    database: practice
                    connection-string: mongodb+srv://user:pass@cluster.example/practice
                """);

        SqlSettings settings = resolved();
        assertEquals("mongodb+srv://user:pass@cluster.example/practice",
                settings.properties().get("connection-string"));
        assertEquals("practice", settings.database());
    }

    @Test
    @DisplayName("\"an unknown engine falls back to h2 rather than stopping the server\"")
    void unknownEngineFallsBack() throws IOException {
        write("""
                database:
                  type: sqlite
                """);

        SqlSettings settings = resolved();
        assertEquals("h2", settings.engine());
        assertTrue(settings.embedded());
    }

    @Test
    @DisplayName("\"the keys commons wrote that this library does not honour are removed\"")
    void unhonouredKeysArePruned() throws IOException {
        write("""
                database:
                  type: h2
                  server-id: server-1
                  redis:
                    enabled: false
                  write-behind:
                    enabled: true
                """);

        resolved();
        String file = read();
        assertFalse(file.contains("write-behind"), "a setting that does nothing was left in the file");
        // The commons server-id sat at the top of the block and meant nothing
        // here, so it goes. The one this library writes lives inside redis:,
        // where it names the sender of an invalidation — same key name, and the
        // only reason it is asserted by indentation rather than by name.
        assertFalse(file.contains("\n  server-id:"),
                "the top-level server-id does nothing here and should be gone");
        assertTrue(file.contains("redis:"), "the Redis block is honoured, not pruned");
        assertTrue(file.contains("key-prefix:"), "and it gains the keys this library adds");
    }

    @Test
    @DisplayName("\"a fresh file needs nothing installed and nothing configured\"")
    void freshFileIsEmbedded() throws IOException {
        SqlSettings settings = resolved();
        assertTrue(settings.embedded());
        assertEquals(folder.resolve("database/h2"), settings.file());
        assertTrue(read().contains("type: h2"));
    }
}
