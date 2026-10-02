package net.exylia.lib.database;

import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.SqlBackend;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.database.internal.SqlStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A table dropped under a running server comes back on the next statement,
 * instead of failing every query until a restart.
 */
class MissingTableTest {

    @Table("vanishing_players")
    record Player(@Id UUID id, @Column String effect) {
    }

    private static final EntityModel<Player> MODEL = EntityModel.of(Player.class);

    private SqlBackend backend;

    @AfterEach
    void tearDown() {
        backend.close();
    }

    @Test
    @DisplayName("a table dropped after preparation is created again and the query succeeds")
    void droppedTableIsRecreated() throws Exception {
        SqlSettings settings = SqlSettings.memory("h2", "missing_" + UUID.randomUUID());
        backend = SqlBackend.open(settings, "MissingTableTest");
        List<String> warnings = new ArrayList<>();
        SqlStorage storage = new SqlStorage(backend, Runnable::run, warnings::add);
        storage.prepare(MODEL).join();

        try (Connection connection = DriverManager.getConnection(
                backend.dialect().jdbcUrl(settings), settings.user(), settings.password());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE \"vanishing_players\"");
        }

        Player player = new Player(UUID.randomUUID(), "lightning");
        assertEquals(null, storage.find(MODEL, player.id()).join(), "the read answers instead of failing");
        storage.save(MODEL, player).join();
        assertEquals(player, storage.find(MODEL, player.id()).join());
        assertEquals(1, warnings.size(), "said once, loudly: " + warnings);
        assertTrue(warnings.get(0).contains("vanishing_players"));
    }
}
