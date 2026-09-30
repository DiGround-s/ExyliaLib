package net.exylia.lib.database;

import net.exylia.lib.FakeServer;
import net.exylia.lib.util.reward.PendingBatch;
import net.exylia.lib.util.reward.PendingRewards;
import net.exylia.lib.util.reward.RewardEntry;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reward module's store in a plugin's database, as {@code /exylialib pendingrewards} browses it. */
class PendingRewardsStoreTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();

    private PendingRewards store;

    @BeforeAll
    static void server() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        Plugin plugin = FakeServer.newPlugin("ExyliaPending");
        Databases.installForTests(plugin, SqlSettings.memory("h2", "pending" + DATABASE.incrementAndGet()));
        store = PendingRewards.database(plugin);
    }

    @AfterEach
    void close() {
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    @Test
    void peekLeavesBatchesAndTakeRemovesOnlyOneOnce() throws InterruptedException {
        UUID player = UUID.randomUUID();
        store.keep(player, List.of(RewardEntry.command("say one").build()));
        store.keep(player, List.of(RewardEntry.command("say two").build(), RewardEntry.command("say three").build()));
        awaitBatches(player, 2);

        assertTrue(store.browsable());
        assertEquals(Map.of(player, 2), store.owed());
        List<PendingBatch> batches = store.peek(player);
        assertEquals(2, store.peek(player).size(), "peek must not empty the store");

        PendingBatch two = batches.stream().filter(batch -> batch.rewards().size() == 2).findFirst().orElseThrow();
        assertEquals(2, store.take(player, two.id()).size());
        assertEquals(List.of(), store.take(player, two.id()), "a batch is handed over once");
        assertEquals(List.of(), store.take(UUID.randomUUID(), batches.get(0).id()),
                "another player's id takes nothing");

        assertEquals(1, store.claim(player).size());
        assertEquals(Map.of(), store.owed());
    }

    private void awaitBatches(UUID player, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (store.peek(player).size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }
}
