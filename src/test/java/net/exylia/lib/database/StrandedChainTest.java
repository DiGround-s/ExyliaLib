package net.exylia.lib.database;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Many writes to one row queued before the stop ran inline, each from inside
 * the one before it: the stack overflowed, a balance was lost and the stop hung.
 */
class StrandedChainTest {

    @Table("stranded_chain_rows")
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
    @DisplayName("a long chain of writes to one row drains at the stop without overflowing the stack")
    void longChainDrainsAtStop() throws Exception {
        Plugin plugin = FakeServer.newPlugin("ExyliaEconomy");
        Databases.installForTests(plugin, SqlSettings.memory("h2", "stranded_chain"));
        Repository<Row> repository = Databases.of(plugin).repository(Row.class);
        UUID uuid = UUID.randomUUID();
        repository.save(new Row(uuid, 0)).get(10, TimeUnit.SECONDS);

        FakeServer.runAsyncOnTicks();
        int writes = 5_000;
        CompletableFuture<Boolean> lastUpdate = null;
        for (int i = 1; i <= writes; i++) {
            lastUpdate = repository.updateIf(new Row(uuid, i), "value", i - 1);
        }
        FakeServer.setStopping(true);

        repository.save(new Row(uuid, writes + 1)).get(30, TimeUnit.SECONDS);
        assertTrue(lastUpdate.get(1, TimeUnit.SECONDS), "every queued updateIf should have won in order");
        assertEquals(Optional.of(new Row(uuid, writes + 1)), repository.find(uuid).get(5, TimeUnit.SECONDS));
    }
}
