package net.exylia.lib.util.crate;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.TestDatabases;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.crate.internal.CrateRow;
import net.exylia.lib.util.crate.internal.CrateStore;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A scoped server sees the global row plus its own, and keeps what it unlocks to itself. */
class CrateScopeTest {

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

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void unlocksHereStayHereAndGlobalOnesCountEverywhere() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        Plugin plugin = FakeServer.newPlugin("Effects");
        TestDatabases.memory(plugin, "scope");
        UUID uuid = new FakePlayer("Steve").player().getUniqueId();

        CrateStore global = new CrateStore(plugin, () -> 1, ignored -> { });
        await(global.open(uuid));
        await(global.edit(uuid, row -> row.withUnlocked("shared")));
        global.close(uuid);

        CrateStore scoped = CrateStore.scopedForTests(plugin, () -> 1, "randomkits");
        await(scoped.open(uuid));
        assertTrue(scoped.row(uuid).isUnlocked("shared"), "a global unlock counts on a scoped server");
        assertEquals(1, scoped.row(uuid).keys(), "the start key is the global one, given once");
        assertFalse(scoped.unlockNow(uuid, "shared"), "owned globally, so a duplicate");
        assertTrue(scoped.unlockNow(uuid, "local"));
        await(scoped.edit(uuid, row -> row.withKeys(row.keys() + 2)));
        assertEquals(3, scoped.row(uuid).keys());
        assertTrue(scoped.spendKey(uuid));
        await(scoped.editShared(uuid, row -> row.withUnlocked("bought")));
        await(scoped.writesForTests(uuid));
        scoped.close(uuid);

        CrateRow back = await(new CrateStore(plugin, () -> 1, ignored -> { }).fetch(uuid));
        assertEquals(List.of("shared", "bought"), back.unlockedIds(), "the local unlock stayed on randomkits");
        assertEquals(1, back.keys(), "keys spent and given here come off this server's row first");
    }
}
