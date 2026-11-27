package net.exylia.lib.database;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A write queued just before the stop waits for a tick that never comes, and a
 * disable's final save of the same row is ordered behind it: the server hung.
 */
class StrandedWriteTest {

    @Table("stranded_rows")
    record Row(@Id UUID uuid, @Column int value) {
    }

    @BeforeEach
    void open() {
        FakeServer.install();
        FakeServer.reset();
        FakeServer.runAsyncForReal();
    }

    @AfterEach
    void close() {
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    @Test
    @DisplayName("a final save behind a write stranded by the stop still completes")
    void finalSaveBehindStrandedWrite() throws Exception {
        Plugin plugin = FakeServer.newPlugin("ExyliaMarket");
        Databases.installForTests(plugin, SqlSettings.memory("h2", "stranded"));
        Repository<Row> repository = Databases.of(plugin).repository(Row.class);
        UUID uuid = UUID.randomUUID();
        repository.save(new Row(uuid, 0)).get(10, TimeUnit.SECONDS);

        FakeServer.runAsyncOnTicks();
        repository.save(new Row(uuid, 1));
        FakeServer.setStopping(true);

        repository.saveAll(List.of(new Row(uuid, 2))).get(5, TimeUnit.SECONDS);
        assertEquals(Optional.of(new Row(uuid, 2)), repository.find(uuid).get(5, TimeUnit.SECONDS));
    }
}
