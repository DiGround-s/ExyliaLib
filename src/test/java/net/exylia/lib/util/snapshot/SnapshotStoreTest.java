package net.exylia.lib.util.snapshot;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.database.internal.DatabaseRuntime;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.debug.DebugCapture;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.snapshot.internal.LegacyImport;
import net.exylia.lib.util.snapshot.internal.LegacyRow;
import net.exylia.lib.util.snapshot.internal.PlayerState;
import net.exylia.lib.util.snapshot.internal.SnapshotRow;
import net.exylia.lib.util.snapshot.internal.SnapshotRuntime;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Storing and restoring, against a real engine.
 *
 * <p>H2 in memory rather than a mock, for the reason the database module's own
 * tests give: the failures this layer exists to prevent — a second context
 * overwriting the first, a clear that happens before the write lands, a
 * migration that loses a row — all pass against a mock by construction.
 *
 * <p>{@link FakeServer#runAsyncForReal()} is what makes the threading real:
 * every future here is completed by another thread, exactly as on a server.
 */
class SnapshotStoreTest {

    private static final long TIMEOUT_SECONDS = 15L;
    private static final AtomicInteger DATABASE = new AtomicInteger();

    private Plugin plugin;
    private World world;
    private PluginSnapshots snapshots;

    @BeforeAll
    static void server() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        SnapshotCodec.setItems(TestItem.IO);
        SnapshotRuntime.forgetReportedForTests();
        plugin = FakeServer.newPlugin("Practice");
        world = FakeServer.newWorld("arena");
        FakeServer.worlds(world);
        DatabaseRuntime.installForTests(plugin,
                SqlSettings.memory("h2", "snapshots" + DATABASE.incrementAndGet()));
        snapshots = Snapshots.of(plugin);
    }

    @AfterEach
    void close() {
        DebugCapture.stop();
        Snapshots.releaseAll();
        Databases.releaseAll();
        Tasks.releaseAll();
        SnapshotCodec.resetItems();
        FakeServer.reset();
    }

    /**
     * Waits for a future while the server keeps ticking.
     *
     * <p>Both halves are needed and neither is optional. The database work runs
     * on a real thread, so a plain tick loop would spin without it ever
     * finishing; the restore then comes back to the player's own thread through
     * {@code runAtEntity}, which on a real server is a tick and here is a task
     * nobody would ever run. A test that only waited would hang on exactly the
     * threading this module is built around.
     */
    private static <T> T await(CompletableFuture<T> future) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!future.isDone() && System.nanoTime() < deadline) {
            FakeServer.tick(1);
            Thread.onSpinWait();
        }
        try {
            return future.get(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for a database operation", interrupted);
        } catch (ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new AssertionError("A database operation did not complete", failure);
        }
    }

    private SnapshotPlayer geared(String name) {
        return new SnapshotPlayer(name)
                .holding(0, TestItem.of("DIAMOND_SWORD"))
                .holding(8, new TestItem("GOLDEN_APPLE", 16))
                .wearing(3, TestItem.of("DIAMOND_HELMET"))
                .offHand(TestItem.of("SHIELD"))
                .inEnderChest(1, new TestItem("EMERALD", 5))
                .health(17.5d).hunger(15, 2.5f).experience(30, 0.5f)
                .at(new Location(world, 100, 64, 200, 90f, 0f));
    }

    // ------------------------------------------------------------ round trip

    @Test
    @DisplayName("a stored snapshot puts back everything it took")
    void storeAndRestore() {
        SnapshotPlayer player = geared("DiGround");

        await(snapshots.saveAndClear(player.player(), "ffa"));
        assertTrue(player.inventoryIsEmpty(), "cleared once the row was durable");

        assertTrue(await(snapshots.restore(player.player(), "ffa")));

        assertEquals(TestItem.of("DIAMOND_SWORD"), player.contents()[0]);
        assertEquals(new TestItem("GOLDEN_APPLE", 16), player.contents()[8]);
        assertEquals(TestItem.of("DIAMOND_HELMET"), player.armor()[3]);
        assertEquals(TestItem.of("SHIELD"), player.offHandItem());
        assertEquals(new TestItem("EMERALD", 5), player.enderChest()[1]);
        assertEquals(17.5d, player.health());
        assertEquals(15, player.foodLevel());
        assertEquals(30, player.level());
    }

    @Test
    @DisplayName("restoring hands back where they were, and removes the row")
    void restoreReportsTheLocationAndConsumesTheRow() {
        SnapshotPlayer player = geared("DiGround");
        AtomicReference<Location> wentBack = new AtomicReference<>();

        await(snapshots.save(player.player(), "ffa"));
        assertTrue(await(snapshots.restore(player.player(), "ffa", wentBack::set)));

        assertNotNull(wentBack.get(), "the caller decides where they go, and needs to be told");
        assertEquals(100.0d, wentBack.get().getX());
        assertFalse(await(snapshots.has(player.id(), "ffa")),
                "a restored snapshot is used up");
        assertFalse(await(snapshots.restore(player.player(), "ffa")),
                "and restoring again does nothing rather than something wrong");
    }

    @Test
    @DisplayName("restoring a player who has no snapshot does nothing at all")
    void nothingToRestore() {
        SnapshotPlayer player = geared("DiGround");

        assertFalse(await(snapshots.restore(player.player(), "ffa")));
        assertEquals(TestItem.of("DIAMOND_SWORD"), player.contents()[0],
                "what they are holding is theirs, and is left alone");
    }

    // ------------------------------------------------- the context bug commons had

    @Test
    @DisplayName("two contexts for the same player coexist")
    void twoContextsCoexist() {
        // The single worst bug in ExyliaCommons: it keyed on the player alone,
        // so joining an event while in an arena overwrote the arena snapshot —
        // and leaving the event handed the player the arena kit while their own
        // inventory was gone for good.
        SnapshotPlayer player = geared("DiGround");

        await(snapshots.saveAndClear(player.player(), "ffa"));
        player.holding(0, TestItem.of("KIT_SWORD"));
        await(snapshots.saveAndClear(player.player(), "event"));

        assertTrue(await(snapshots.has(player.id(), "ffa")), "the first still exists");
        assertTrue(await(snapshots.has(player.id(), "event")), "and so does the second");

        // Leaving the event gives back the kit, not the real inventory.
        assertTrue(await(snapshots.restore(player.player(), "event")));
        assertEquals(TestItem.of("KIT_SWORD"), player.contents()[0]);

        // Leaving the arena gives back what the player actually owned.
        assertTrue(await(snapshots.restore(player.player(), "ffa")));
        assertEquals(TestItem.of("DIAMOND_SWORD"), player.contents()[0],
                "the inventory the player owns survived the second context");
        assertEquals(new TestItem("GOLDEN_APPLE", 16), player.contents()[8]);
    }

    @Test
    @DisplayName("a player's contexts can be listed, newest first")
    void contextsAreListed() {
        SnapshotPlayer player = geared("DiGround");

        await(snapshots.save(player.player(), "ffa"));
        await(snapshots.save(player.player(), "event"));

        List<String> contexts = await(snapshots.contexts(player.id()));

        assertEquals(2, contexts.size());
        assertTrue(contexts.contains("ffa"));
        assertTrue(contexts.contains("event"));
    }

    @Test
    @DisplayName("restoring everything applies the oldest snapshot last")
    void restoreAllEndsInTheOriginalState() {
        // A player who was in an arena when the server died, then also in an
        // event: what they should end up with is what they owned before any of
        // it, which is the earliest snapshot.
        SnapshotPlayer player = geared("DiGround");

        await(snapshots.saveAndClear(player.player(), "ffa"));
        player.holding(0, TestItem.of("KIT_SWORD"));
        await(snapshots.saveAndClear(player.player(), "event"));

        assertEquals(2, await(snapshots.restoreAll(player.player(), null)));
        assertEquals(TestItem.of("DIAMOND_SWORD"), player.contents()[0],
                "the state they were in before any context won");
        assertEquals(List.of(), await(snapshots.contexts(player.id())),
                "and both rows are used up");
    }

    @Test
    @DisplayName("two players never see each other's snapshots")
    void playersAreSeparate() {
        SnapshotPlayer first = geared("DiGround");
        SnapshotPlayer second = new SnapshotPlayer("Steve")
                .holding(0, TestItem.of("STICK"))
                .at(new Location(world, 0, 64, 0));

        await(snapshots.saveAndClear(first.player(), "ffa"));
        await(snapshots.saveAndClear(second.player(), "ffa"));

        assertTrue(await(snapshots.restore(second.player(), "ffa")));
        assertEquals(TestItem.of("STICK"), second.contents()[0]);
        assertTrue(await(snapshots.has(first.id(), "ffa")), "the other is untouched");
    }

    // -------------------------------------------------------- saveAndClear

    @Test
    @DisplayName("a failed write leaves the player holding everything they owned")
    void saveAndClearDoesNotClearWhenTheWriteFails() {
        // ExyliaCommons cleared first and wrote afterwards, so a write that
        // failed left the player with neither their inventory nor a snapshot of
        // it. Here the write has to land first, and a failure is the one case
        // where doing nothing is exactly right.
        SnapshotPlayer player = geared("DiGround");
        // A context id longer than the column, which the engine refuses.
        String tooLong = "x".repeat(200);

        CompletableFuture<Void> saving = snapshots.saveAndClear(player.player(), tooLong);

        assertThrowsFailure(saving);
        assertFalse(player.inventoryIsEmpty(), "nothing was taken away");
        assertEquals(TestItem.of("DIAMOND_SWORD"), player.contents()[0]);
        assertEquals(TestItem.of("SHIELD"), player.offHandItem());
    }

    private static void assertThrowsFailure(CompletableFuture<?> future) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!future.isDone() && System.nanoTime() < deadline) {
            FakeServer.tick(1);
            Thread.onSpinWait();
        }
        try {
            future.get(1, TimeUnit.SECONDS);
            throw new AssertionError("the write was expected to fail and did not");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted", interrupted);
        } catch (ExecutionException expected) {
            // What a failed write looks like to a caller.
        } catch (java.util.concurrent.TimeoutException timedOut) {
            throw new AssertionError("the write neither landed nor failed", timedOut);
        }
    }

    @Test
    @DisplayName("clearing takes the inventory and leaves the player alive")
    void saveAndClearOnlyTakesItems() {
        SnapshotPlayer player = geared("DiGround").mode(GameMode.ADVENTURE);

        await(snapshots.saveAndClear(player.player(), "ffa"));

        assertTrue(player.inventoryIsEmpty());
        assertEquals(17.5d, player.health(), "health is the caller's to change");
        assertEquals(30, player.level(), "and so is experience");
        assertEquals(GameMode.ADVENTURE, player.mode(), "and the game mode");
    }

    // ------------------------------------------------------- partial restore

    @Test
    @DisplayName("a partial restore touches only the parts it was given")
    void partialRestoreTouchesOnlyItsParts() {
        SnapshotPlayer player = geared("DiGround");
        await(snapshots.save(player.player(), "ffa"));

        // The player then changes in every way the snapshot could put back.
        player.holding(0, TestItem.of("KIT_SWORD"))
                .health(4.0d).hunger(3, 0.0f).experience(0, 0.0f).mode(GameMode.CREATIVE);

        assertTrue(await(snapshots.restore(player.player(), "ffa", null,
                SnapshotPart.set(SnapshotPart.HEALTH, SnapshotPart.HUNGER))));

        assertEquals(17.5d, player.health(), "asked for");
        assertEquals(15, player.foodLevel(), "asked for");
        assertEquals(2.5f, player.saturation(), "asked for");
        assertEquals(TestItem.of("KIT_SWORD"), player.contents()[0],
                "not asked for, so left exactly as it was");
        assertEquals(0, player.level(), "not asked for");
        assertEquals(GameMode.CREATIVE, player.mode(), "not asked for");
    }

    @Test
    @DisplayName("everything except one part is a set too")
    void allExceptIsUsable() {
        SnapshotPlayer player = geared("DiGround");
        await(snapshots.save(player.player(), "ffa"));

        player.holding(0, TestItem.of("KIT_SWORD")).health(4.0d);

        assertTrue(await(snapshots.restore(player.player(), "ffa", null,
                SnapshotPart.allExcept(SnapshotPart.INVENTORY))));

        assertEquals(TestItem.of("KIT_SWORD"), player.contents()[0], "the kit is kept");
        assertEquals(17.5d, player.health(), "and everything else came back");
        assertEquals(TestItem.of("DIAMOND_HELMET"), player.armor()[3]);
    }

    @Test
    @DisplayName("a snapshot can be read without being used up")
    void findDoesNotConsume() {
        SnapshotPlayer player = geared("DiGround");
        await(snapshots.save(player.player(), "ffa"));

        Optional<Snapshot> found = await(snapshots.find(player.id(), "ffa"));

        assertTrue(found.isPresent());
        assertEquals(17.5d, found.get().health());
        assertTrue(await(snapshots.has(player.id(), "ffa")), "still there");
    }

    // ----------------------------------------------------- quitting and stopping

    @Test
    @DisplayName("a player who leaves mid-restore keeps their snapshot")
    void aPlayerWhoLeavesKeepsTheirSnapshot() {
        // The answer to restoreSync: the row is already durable, so a player who
        // disconnects needs no work at all — and must not have their snapshot
        // deleted by a restore that never reached them.
        SnapshotPlayer player = geared("DiGround");
        await(snapshots.saveAndClear(player.player(), "ffa"));

        player.disconnect();
        assertFalse(await(snapshots.restore(player.player(), "ffa")),
                "there is nobody to restore onto");

        assertTrue(await(snapshots.has(player.id(), "ffa")),
                "so the snapshot is still waiting for them");
    }

    @Test
    @DisplayName("what a leaving player is owed can be asked for without blocking")
    void pendingAnswersWithoutTouchingAnything() {
        SnapshotPlayer player = geared("DiGround");
        await(snapshots.save(player.player(), "ffa"));

        Optional<Location> where = await(snapshots.pending(player.id(), "ffa"));

        assertTrue(where.isPresent());
        assertEquals(100.0d, where.get().getX());
        assertTrue(await(snapshots.has(player.id(), "ffa")), "and nothing was consumed");
    }

    @Test
    @DisplayName("a snapshot survives the plugin being released, because it is a row")
    void releaseKeepsTheRow() {
        SnapshotPlayer player = geared("DiGround");
        await(snapshots.saveAndClear(player.player(), "ffa"));

        Snapshots.release(plugin.getName());

        PluginSnapshots after = Snapshots.of(plugin);
        assertTrue(await(after.restore(player.player(), "ffa")),
                "a restart is exactly what the database is for");
        assertEquals(TestItem.of("DIAMOND_SWORD"), player.contents()[0]);
    }

    @Test
    @DisplayName("discarding throws a snapshot away without restoring it")
    void discard() {
        SnapshotPlayer player = geared("DiGround");
        await(snapshots.saveAndClear(player.player(), "ffa"));

        assertTrue(await(snapshots.discard(player.id(), "ffa")));

        assertFalse(await(snapshots.has(player.id(), "ffa")));
        assertTrue(player.inventoryIsEmpty(), "nothing was put back");
    }

    // -------------------------------------------------------------- reporting

    @Test
    @DisplayName("an unreadable item is reported once and the rest is restored")
    void unreadableItemIsReportedAndSkipped() {
        List<String> logged = DebugCapture.start();
        SnapshotPlayer player = geared("DiGround");
        Repository<SnapshotRow> rows = Databases.of(plugin).repository(SnapshotRow.class);

        // A row whose second slot holds something no version of this server can
        // read, which is what an item written by a newer one looks like.
        Snapshot broken = SnapshotCodec.decode("""
                {"gameMode":"SURVIVAL",\
                "inventory":["item:DIAMOND_SWORDx1","WRITTEN-BY-A-NEWER-SERVER"],\
                "armor":[],"offHand":null,\
                "health":11.0,"maxHealth":20.0,"foodLevel":9,"saturation":1.0,\
                "level":7,"exp":0.0,"potionEffects":[],\
                "allowFlight":false,"flying":false,"flySpeed":0.1}""",
                SnapshotRuntime::report);
        assertNotNull(broken, "one bad item does not discard a snapshot");
        await(rows.save(SnapshotRow.of(player.id(), "ffa", broken, null,
                System.currentTimeMillis())));

        assertTrue(await(snapshots.restore(player.player(), "ffa")));

        assertEquals(TestItem.of("DIAMOND_SWORD"), player.contents()[0], "the readable item");
        assertNull(player.contents()[1], "the unreadable one is an empty slot");
        assertEquals(11.0d, player.health(), "and everything that is not an item");
        assertEquals(7, player.level());
        assertEquals(1, logged.stream().filter(line -> line.contains("slot 1")).count(),
                "said once, not swallowed and not repeated: " + logged);
    }

    // -------------------------------------------------------------- migration

    @Test
    @DisplayName("rows ExyliaCommons wrote are imported, and its table is left alone")
    void legacyRowsAreImported() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Repository<LegacyRow> legacy = Databases.of(plugin).repository(LegacyRow.class);
        await(legacy.save(new LegacyRow(first.toString(), commonsSnapshot("DIAMOND_SWORD"),
                "ffa", new Location(world, 1, 64, 2), 100L, 200L)));
        await(legacy.save(new LegacyRow(second.toString(), commonsSnapshot("STICK"),
                "sandbox", new Location(world, 3, 64, 4), 100L, 200L)));

        // The import runs on the first use of the store, in the background.
        assertTrue(await(snapshots.has(first, "ffa")), "the first player's row moved");
        assertTrue(await(snapshots.has(second, "sandbox")), "and the second's");

        Optional<Snapshot> imported = await(snapshots.find(first, "ffa"));
        assertTrue(imported.isPresent());
        assertEquals(TestItem.of("DIAMOND_SWORD"), imported.get().inventory()[0],
                "with the items intact");

        assertEquals(2L, await(legacy.count()),
                "and the old table is untouched, so a server can go back");
    }

    @Test
    @DisplayName("importing twice moves nothing twice and overwrites nothing")
    void importIsIdempotent() {
        UUID player = UUID.randomUUID();
        Repository<LegacyRow> legacy = Databases.of(plugin).repository(LegacyRow.class);
        await(legacy.save(new LegacyRow(player.toString(), commonsSnapshot("DIAMOND_SWORD"),
                "ffa", null, 100L, 200L)));

        assertTrue(await(snapshots.has(player, "ffa")), "the first run moved it");

        // Something newer happens after the import: the player joins the arena
        // again and a fresh snapshot replaces the migrated one.
        SnapshotPlayer online = new SnapshotPlayer("DiGround");
        Repository<SnapshotRow> rows = Databases.of(plugin).repository(SnapshotRow.class);
        Snapshot newer = SnapshotCodec.decode(SnapshotCodec.encode(
                PlayerState.capture(online.holding(0, TestItem.of("NETHERITE_SWORD")).player())));
        assertNotNull(newer);
        await(rows.save(SnapshotRow.of(player, "ffa", newer, null, System.currentTimeMillis())));

        // A second plugin opening its own store runs the import check again.
        Snapshots.release(plugin.getName());
        PluginSnapshots second = Snapshots.of(plugin);

        Optional<Snapshot> found = await(second.find(player, "ffa"));
        assertTrue(found.isPresent());
        assertEquals(TestItem.of("NETHERITE_SWORD"), found.get().inventory()[0],
                "the newer snapshot was not overwritten by the stale legacy one");
        assertEquals(1L, await(legacy.count()), "and nothing was deleted");
    }

    @Test
    @DisplayName("the import marker is never handed back as somebody's snapshot")
    void theMarkerIsNotASnapshot() {
        // It lives in the same table, so every read has to know it is not a row
        // that restores anything onto anybody.
        UUID player = UUID.randomUUID();
        Repository<LegacyRow> legacy = Databases.of(plugin).repository(LegacyRow.class);
        await(legacy.save(new LegacyRow(player.toString(), commonsSnapshot("STICK"),
                "sandbox", null, 100L, 200L)));
        await(snapshots.has(player, "sandbox"));

        Repository<SnapshotRow> rows = Databases.of(plugin).repository(SnapshotRow.class);
        assertTrue(await(rows.exists(LegacyImport.markerKey())), "the marker was written");

        assertEquals(List.of("sandbox"), await(snapshots.contexts(player)),
                "a real player's contexts are only their own");
        assertEquals(List.of(), await(snapshots.contexts(new UUID(0L, 0L))),
                "and the player the marker is filed under has no snapshots");
        assertEquals(Optional.empty(), await(snapshots.find(new UUID(0L, 0L), "$legacy-import")),
                "the marker is never handed back as a snapshot");
    }

    @Test
    @DisplayName("a legacy row with no context still keeps its inventory")
    void legacyRowWithoutAContext() {
        // Commons allowed it to be null, and the inventory in such a row belongs
        // to somebody just as much as any other.
        UUID player = UUID.randomUUID();
        Repository<LegacyRow> legacy = Databases.of(plugin).repository(LegacyRow.class);
        await(legacy.save(new LegacyRow(player.toString(), commonsSnapshot("DIAMOND_SWORD"),
                null, null, 100L, 200L)));

        assertTrue(await(snapshots.has(player, "legacy")),
                "filed under a context of its own rather than dropped");
    }

    /** A snapshot in the shape a commons row holds, with one item in slot zero. */
    private static Snapshot commonsSnapshot(String item) {
        Snapshot decoded = SnapshotCodec.decode("""
                {"gameMode":"SURVIVAL","inventory":["item:%sx1"],"armor":[null,null,null,null],\
                "offHand":null,"health":20.0,"maxHealth":20.0,"foodLevel":20,"saturation":5.0,\
                "level":0,"exp":0.0,"potionEffects":[],\
                "allowFlight":false,"flying":false,"flySpeed":0.1}""".formatted(item));
        assertNotNull(decoded);
        return decoded;
    }
}
