package net.exylia.lib.util.snapshot;

import net.exylia.lib.FakeServer;
import net.exylia.lib.util.snapshot.internal.PlayerState;
import org.bukkit.GameMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a live player, and writing one back.
 *
 * <p>No database anywhere in here: capture and restore are the in-memory
 * lifetime, and they have to work on their own for the stored one to mean
 * anything.
 */
class SnapshotCaptureTest {

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        SnapshotCodec.setItems(TestItem.IO);
    }

    @AfterEach
    void tearDown() {
        SnapshotCodec.resetItems();
        FakeServer.reset();
    }

    private static SnapshotPlayer geared() {
        return new SnapshotPlayer("DiGround")
                .holding(0, TestItem.of("DIAMOND_SWORD"))
                .wearing(3, TestItem.of("DIAMOND_HELMET"))
                .offHand(TestItem.of("SHIELD"))
                .inEnderChest(1, new TestItem("EMERALD", 5))
                .health(17.5d).hunger(15, 2.5f).experience(30, 0.5f)
                .mode(GameMode.ADVENTURE).burning(40);
    }

    // ------------------------------------------------------------- capturing

    @Test
    @DisplayName("capturing reads everything a player is")
    void captureReadsEverything() {
        Snapshot snapshot = Snapshot.of(geared().player());

        assertEquals(GameMode.ADVENTURE, snapshot.gameMode());
        assertEquals(TestItem.of("DIAMOND_SWORD"), snapshot.inventory()[0]);
        assertEquals(TestItem.of("DIAMOND_HELMET"), snapshot.armor()[3]);
        assertEquals(TestItem.of("SHIELD"), snapshot.offHand());
        assertEquals(new TestItem("EMERALD", 5), snapshot.enderChest()[1],
                "the ender chest, which ExyliaCommons never stored");
        assertEquals(17.5d, snapshot.health());
        assertEquals(15, snapshot.foodLevel());
        assertEquals(2.5f, snapshot.saturation());
        assertEquals(30, snapshot.level());
        assertEquals(0.5f, snapshot.exp());
        assertNotNull(snapshot.physical(), "the physical state, which it never stored either");
        assertEquals(40, snapshot.physical().fireTicks());
    }

    @Test
    @DisplayName("the captured inventory is the size a commons row holds")
    void captureKeepsTheCommonsSizes() {
        // So a row written here lines up slot for slot with one written there.
        Snapshot snapshot = Snapshot.of(geared().player());

        assertEquals(Snapshot.INVENTORY_SLOTS, snapshot.inventory().length);
        assertEquals(Snapshot.ARMOR_SLOTS, snapshot.armor().length);
    }

    @Test
    @DisplayName("an empty off hand is stored as absent rather than as air")
    void emptyOffHandIsAbsent() {
        // Every row on the server would otherwise carry a Base64 string for
        // nothing, and commons wrote it as null.
        Snapshot snapshot = Snapshot.of(new SnapshotPlayer("Steve").player());

        assertNull(snapshot.offHand());
        assertFalse(snapshot.has(SnapshotPart.OFF_HAND));
    }

    // ------------------------------------------------------------- restoring

    @Test
    @DisplayName("restoring puts a changed player back exactly as they were")
    void restorePutsEverythingBack() {
        SnapshotPlayer player = geared();
        Snapshot before = Snapshot.of(player.player());

        player.holding(0, TestItem.of("KIT_SWORD")).wearing(3, null).offHand(null)
                .health(3.0d).hunger(2, 0.0f).experience(0, 0.0f).mode(GameMode.CREATIVE)
                .burning(0);

        before.restoreTo(player.player());

        assertEquals(TestItem.of("DIAMOND_SWORD"), player.contents()[0]);
        assertEquals(TestItem.of("DIAMOND_HELMET"), player.armor()[3]);
        assertEquals(TestItem.of("SHIELD"), player.offHandItem());
        assertEquals(17.5d, player.health());
        assertEquals(15, player.foodLevel());
        assertEquals(30, player.level());
        assertEquals(GameMode.ADVENTURE, player.mode());
        assertEquals(40, player.fireTicks(), "including the physical state");
    }

    @Test
    @DisplayName("restoring refreshes the client's view of the inventory")
    void restoreUpdatesTheInventory() {
        // Without it the player sees the old items until something else makes
        // the client redraw, which is usually them clicking one that is not
        // there any more.
        SnapshotPlayer player = geared();
        Snapshot before = Snapshot.of(player.player());

        before.restoreTo(player.player());

        assertTrue(player.inventoryUpdates() > 0);
    }

    @Test
    @DisplayName("a part the snapshot does not carry is left alone")
    void absentPartsAreNotApplied() {
        // A row written by ExyliaCommons has no ender chest. Emptying the
        // player's because a two-year-old row never mentioned one would be a
        // very expensive way to be consistent.
        Snapshot commons = new Snapshot(null, null, null, null, null,
                20, 20, 20, 5, 0, 0, List.of(), false, false, 0.1f, null);
        SnapshotPlayer player = geared();

        commons.restoreTo(player.player());

        assertEquals(new TestItem("EMERALD", 5), player.enderChest()[1], "kept");
        assertEquals(TestItem.of("DIAMOND_SWORD"), player.contents()[0], "kept");
        assertEquals(GameMode.ADVENTURE, player.mode(), "kept");
        assertEquals(40, player.fireTicks(), "kept");
    }

    @Test
    @DisplayName("a partial restore touches only the parts it was given")
    void partialRestore() {
        SnapshotPlayer player = geared();
        Snapshot before = Snapshot.of(player.player());
        player.holding(0, TestItem.of("KIT_SWORD")).health(3.0d).experience(0, 0.0f);

        before.restoreTo(player.player(), SnapshotPart.set(SnapshotPart.HEALTH));

        assertEquals(17.5d, player.health(), "asked for");
        assertEquals(TestItem.of("KIT_SWORD"), player.contents()[0], "not asked for");
        assertEquals(0, player.level(), "not asked for");
    }

    @Test
    @DisplayName("restoring nothing is allowed and does nothing")
    void emptyPartsDoNothing() {
        SnapshotPlayer player = geared();
        Snapshot before = Snapshot.of(player.player());
        player.holding(0, TestItem.of("KIT_SWORD")).health(3.0d);

        before.restoreTo(player.player(), SnapshotPart.set());

        assertEquals(TestItem.of("KIT_SWORD"), player.contents()[0]);
        assertEquals(3.0d, player.health());
    }

    @Test
    @DisplayName("flying is never restored without permission to fly")
    void flyingImpliesAllowFlight() {
        // The pair is applied as a pair: flying without the permission drops the
        // player out of the sky on the client's next tick.
        Snapshot inconsistent = new Snapshot(null, null, null, null, null,
                20, 20, 20, 5, 0, 0, List.of(), false, true, 0.1f, null);
        SnapshotPlayer player = geared();

        inconsistent.restoreTo(player.player());

        assertFalse(player.player().isFlying());
    }

    // --------------------------------------------------------------- clearing

    @Test
    @DisplayName("clearing takes the items and nothing else")
    void clearTakesOnlyItems() {
        // saveAndClear exists so a plugin can hand out its own kit. A player
        // whose health and game mode were also wiped would be handed one while
        // dying in spectator.
        SnapshotPlayer player = geared();

        PlayerState.clear(player.player());

        assertTrue(player.inventoryIsEmpty());
        assertEquals(17.5d, player.health());
        assertEquals(30, player.level());
        assertEquals(GameMode.ADVENTURE, player.mode());
        assertEquals(new TestItem("EMERALD", 5), player.enderChest()[1],
                "an ender chest is not something a kit replaces");
    }
}
