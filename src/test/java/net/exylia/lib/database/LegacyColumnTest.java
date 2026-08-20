package net.exylia.lib.database;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.internal.SqlBackend;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writing to a table an older library left behind.
 *
 * <p>Every entity in ExyliaCommons extended a base class that carried
 * {@code created_at} and {@code updated_at}, so every table it ever created has
 * two columns no record in this library declares — and it wrote them
 * {@code NOT NULL}. A plugin that migrates keeps its table, so the first insert
 * omits a column the table insists on and the row is refused.
 *
 * <p>This is not a Shields problem: it is every table Commons created, in every
 * plugin that migrates.
 */
class LegacyColumnTest {

    private static final long TIMEOUT_SECONDS = 15L;
    private static final AtomicInteger DATABASE = new AtomicInteger();

    /** As this library declares it: no timestamps, because nothing reads them. */
    @Table("shield_design_library")
    record Design(
            @Id(generated = true) long id,
            @Column("owner_uuid") String ownerUuid,
            @Column("design_json") String json,
            @Column int uses) {
    }

    /** A row whose key the caller supplies, which takes the save path. */
    @Table("player_data")
    record PlayerRow(@Id UUID uuid, @Column("shield_count") int count) {
    }

    private Plugin plugin;
    private String url;

    @BeforeAll
    static void server() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        plugin = FakeServer.newPlugin("Shields");
        String name = "legacy" + DATABASE.incrementAndGet();
        url = "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1";
        Databases.installForTests(plugin, SqlSettings.memory("h2", name));
    }

    @AfterEach
    void close() {
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Creates the table exactly as the previous library would have. */
    private void createLegacyTable(String ddl) {
        try (Connection connection = java.sql.DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        } catch (Exception failure) {
            throw new IllegalStateException("could not set up the legacy table", failure);
        }
    }

    private long countRows(String table) {
        try (Connection connection = java.sql.DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
            return rows.next() ? rows.getLong(1) : -1L;
        } catch (Exception failure) {
            throw new IllegalStateException("could not count " + table, failure);
        }
    }

    @Test
    @DisplayName("an insert survives a NOT NULL column the record no longer declares")
    void insertSurvivesLegacyNotNullColumn() {
        // Exactly what Commons left behind: created_at and updated_at, NOT NULL,
        // with no default. The record has neither, so the INSERT names four
        // columns and the table refuses the row.
        createLegacyTable("""
                CREATE TABLE "shield_design_library" (
                  "id" BIGINT AUTO_INCREMENT PRIMARY KEY,
                  "owner_uuid" VARCHAR(36),
                  "design_json" CLOB,
                  "uses" INT,
                  "created_at" BIGINT NOT NULL,
                  "updated_at" BIGINT NOT NULL
                )""");

        Repository<Design> repository = Databases.of(plugin).repository(Design.class);

        long id = assertDoesNotThrow(
                () -> await(repository.insert(new Design(0L, UUID.randomUUID().toString(), "{}", 0))),
                "a design publishes into a table Commons created");

        assertTrue(id > 0, "the database handed out a key");
        assertEquals(1L, countRows("shield_design_library"), "the row is there");
    }

    @Test
    @DisplayName("a save survives a NOT NULL column the record no longer declares")
    void saveSurvivesLegacyNotNullColumn() {
        createLegacyTable("""
                CREATE TABLE "player_data" (
                  "uuid" VARCHAR(36) PRIMARY KEY,
                  "shield_count" INT,
                  "created_at" BIGINT NOT NULL
                )""");

        Repository<PlayerRow> repository = Databases.of(plugin).repository(PlayerRow.class);
        PlayerRow row = new PlayerRow(UUID.randomUUID(), 3);

        assertDoesNotThrow(() -> await(repository.save(row)),
                "a player row saves into a table Commons created");
        assertEquals(3, await(repository.find(row.uuid())).orElseThrow().count());
    }

    @Test
    @DisplayName("a column the record does declare is still written")
    void declaredColumnsAreUntouched() {
        // The fix must not turn every column into a nullable one: a table this
        // library created keeps the constraints it was created with.
        Repository<PlayerRow> repository = Databases.of(plugin).repository(PlayerRow.class);
        PlayerRow row = new PlayerRow(UUID.randomUUID(), 7);

        await(repository.save(row));

        assertEquals(7, await(repository.find(row.uuid())).orElseThrow().count(),
                "an ordinary table is unaffected");
    }
}
