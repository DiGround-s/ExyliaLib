package net.exylia.lib.database;

import net.exylia.lib.FakeServer;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.crate.internal.CrateRow;
import net.exylia.lib.util.reward.PendingRewardRow;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Several tables written together: all land, or none does. */
class AtomicTest {

    @BeforeAll
    static void install() {
        FakeServer.install();
    }

    @AfterEach
    void close() {
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    @Test
    void aFailedConditionOrATakenKeyWritesNothing() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        Plugin plugin = FakeServer.newPlugin("Atomic");
        TestDatabases.memory(plugin, "atomic");
        PluginDatabase database = Databases.of(plugin);
        Repository<CrateRow> rows = database.repository(CrateRow.class);
        Repository<PendingRewardRow> pending = database.repository(PendingRewardRow.class);
        UUID uuid = UUID.randomUUID();
        CrateRow row = CrateRow.fresh("p", uuid, 5);
        rows.insertIfAbsent(row).join();
        PendingRewardRow first = new PendingRewardRow("one", "Atomic", uuid.toString(), "", 1L);

        CrateRow paid = row.withKeys(4).stampedAfter(row);
        assertTrue(database.atomic().updateIf(rows, paid, "updatedAt", row.updatedAt())
                .insert(pending, first).commit().join());
        assertEquals(4, rows.findFresh(row.id()).join().orElseThrow().keys());

        // A stale version: the insert beside it is rolled back.
        PendingRewardRow second = new PendingRewardRow("two", "Atomic", uuid.toString(), "", 1L);
        assertFalse(database.atomic().insert(pending, second)
                .updateIf(rows, row.withKeys(0).stampedAfter(row), "updatedAt", row.updatedAt()).commit().join());
        assertFalse(pending.exists("two").join(), "a write landed beside a failed condition");

        // A key already taken: the row change beside it is rolled back.
        CrateRow again = paid.withKeys(1).stampedAfter(paid);
        assertFalse(database.atomic().updateIf(rows, again, "updatedAt", paid.updatedAt())
                .insert(pending, first).commit().join());
        assertEquals(4, rows.findFresh(row.id()).join().orElseThrow().keys(), "a change landed beside a taken key");
        assertEquals(List.of("one"), pending.findAll().join().stream().map(PendingRewardRow::id).toList());
    }
}
