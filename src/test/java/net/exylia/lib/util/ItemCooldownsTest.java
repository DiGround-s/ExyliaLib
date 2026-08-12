package net.exylia.lib.util;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item cooldowns: the base, plus the overlay the client draws.
 */
class ItemCooldownsTest {

    private FakePlayer player;
    private final AtomicLong now = new AtomicLong(1_000_000L);

    /** Every overlay call, as "MATERIAL:ticks". */
    private final List<String> drawn = new ArrayList<>();

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        player = new FakePlayer("Steve");
        FakeServer.online(player.player());

        Cooldowns.removeStore();
        Cooldowns.clearEverything();
        Cooldowns.setClock(now::get);

        drawn.clear();
        ItemCooldowns.setOverlay((p, material, ticks) -> drawn.add(material + ":" + ticks));
    }

    @AfterEach
    void tearDown() {
        ItemCooldowns.resetOverlay();
        Cooldowns.clearEverything();
        Cooldowns.resetClock();
        FakeServer.reset();
    }

    private void advance(Duration by) {
        now.addAndGet(by.toMillis());
    }

    // ------------------------------------------------------------------
    // The basics, inherited from the base
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an item on cooldown is active")
    void itemIsActive() {
        ItemCooldowns.start(player.player(), Material.ENDER_PEARL, Duration.ofSeconds(16));

        assertTrue(ItemCooldowns.isActive(player.player(), Material.ENDER_PEARL));
    }

    @Test
    @DisplayName("an item's cooldown expires on time")
    void expiresOnTime() {
        ItemCooldowns.start(player.player(), Material.ENDER_PEARL, Duration.ofSeconds(16));

        advance(Duration.ofSeconds(16));

        assertFalse(ItemCooldowns.isActive(player.player(), Material.ENDER_PEARL));
    }

    @Test
    @DisplayName("two materials are independent")
    void materialsAreIndependent() {
        ItemCooldowns.start(player.player(), Material.ENDER_PEARL, Duration.ofSeconds(16));

        assertTrue(ItemCooldowns.isActive(player.player(), Material.ENDER_PEARL));
        assertFalse(ItemCooldowns.isActive(player.player(), Material.GOLDEN_APPLE));
    }

    @Test
    @DisplayName("tryStart refuses while the item is cooling down")
    void tryStartRefuses() {
        assertTrue(ItemCooldowns.tryStart(player.player(), Material.ENDER_PEARL,
                Duration.ofSeconds(16)));
        assertFalse(ItemCooldowns.tryStart(player.player(), Material.ENDER_PEARL,
                Duration.ofSeconds(16)));
    }

    @Test
    @DisplayName("seconds left keep their decimals, as everywhere else")
    void secondsHaveDecimals() {
        ItemCooldowns.start(player.player(), Material.ENDER_PEARL, Duration.ofMillis(400));

        assertEquals(0.4,
                ItemCooldowns.remainingSeconds(player.player(), Material.ENDER_PEARL), 0.001);
        assertEquals("0.4",
                ItemCooldowns.remainingFormatted(player.player(), Material.ENDER_PEARL));
    }

    // ------------------------------------------------------------------
    // The overlay
    // ------------------------------------------------------------------

    @Test
    @DisplayName("starting a cooldown draws the client overlay")
    void startDrawsOverlay() {
        ItemCooldowns.start(player.player(), Material.ENDER_PEARL, Duration.ofSeconds(16));

        assertEquals(List.of("ENDER_PEARL:320"), drawn, "16 seconds is 320 ticks");
    }

    @Test
    @DisplayName("a refused tryStart does not redraw the overlay")
    void refusedDoesNotRedraw() {
        ItemCooldowns.tryStart(player.player(), Material.ENDER_PEARL, Duration.ofSeconds(16));
        drawn.clear();

        ItemCooldowns.tryStart(player.player(), Material.ENDER_PEARL, Duration.ofSeconds(16));

        assertTrue(drawn.isEmpty(),
                "redrawing would restart the sweep the player is watching");
    }

    @Test
    @DisplayName("clearing a cooldown clears the overlay too")
    void clearClearsOverlay() {
        ItemCooldowns.start(player.player(), Material.ENDER_PEARL, Duration.ofSeconds(16));
        drawn.clear();

        ItemCooldowns.clear(player.player(), Material.ENDER_PEARL);

        assertEquals(List.of("ENDER_PEARL:0"), drawn,
                "a zero sweep is how the overlay is taken away");
        assertFalse(ItemCooldowns.isActive(player.player(), Material.ENDER_PEARL));
    }

    @Test
    @DisplayName("restoring redraws what is left, not the whole cooldown")
    void restoreDrawsWhatIsLeft() {
        ItemCooldowns.start(player.player(), Material.ENDER_PEARL, Duration.ofSeconds(16));
        advance(Duration.ofSeconds(6));
        drawn.clear();

        ItemCooldowns.restore(player.player(), Material.ENDER_PEARL);

        assertEquals(List.of("ENDER_PEARL:200"), drawn, "ten seconds left, not sixteen");
    }

    @Test
    @DisplayName("restoring an item with nothing running draws nothing")
    void restoreOfNothingDrawsNothing() {
        ItemCooldowns.restore(player.player(), Material.ENDER_PEARL);

        assertTrue(drawn.isEmpty());
    }

    // ------------------------------------------------------------------
    // Named items
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a named item is separate from its bare material")
    void namedIsSeparateFromMaterial() {
        ItemCooldowns.start(player.player(), "fire-wand", Material.BLAZE_ROD,
                Duration.ofSeconds(30));

        assertTrue(ItemCooldowns.isActive(player.player(), "fire-wand"));
        assertFalse(ItemCooldowns.isActive(player.player(), Material.BLAZE_ROD),
                "a plain blaze rod is not the wand");
    }

    @Test
    @DisplayName("two named items sharing a material are independent")
    void namedItemsAreIndependent() {
        ItemCooldowns.start(player.player(), "fire-wand", Material.BLAZE_ROD,
                Duration.ofSeconds(30));

        assertTrue(ItemCooldowns.isActive(player.player(), "fire-wand"));
        assertFalse(ItemCooldowns.isActive(player.player(), "ice-wand"));
    }

    @Test
    @DisplayName("a named item still draws the overlay of its material")
    void namedDrawsMaterialOverlay() {
        ItemCooldowns.start(player.player(), "fire-wand", Material.BLAZE_ROD,
                Duration.ofSeconds(30));

        assertEquals(List.of("BLAZE_ROD:600"), drawn);
    }

    // ------------------------------------------------------------------
    // Sharing the base
    // ------------------------------------------------------------------

    @Test
    @DisplayName("item cooldowns are namespaced away from plain ones")
    void itemsAreNamespaced() {
        ItemCooldowns.start(player.player(), Material.ENDER_PEARL, Duration.ofSeconds(16));

        // A plugin using the bare key "ender_pearl" for something else is
        // untouched by this.
        assertFalse(Cooldowns.isActive(player.player(), "ender_pearl"));
        assertTrue(Cooldowns.isActive(player.player(), "item:ender_pearl"));
    }

    @Test
    @DisplayName("a long item cooldown inherits persistence from the base")
    void longItemCooldownsPersist(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        Cooldowns.installStore(new net.exylia.lib.util.internal.CooldownStore(
                dir, java.util.logging.Logger.getLogger("test")));

        ItemCooldowns.start(player.player(), "raid-horn", Material.GOAT_HORN,
                Duration.ofHours(6));

        // Everything the base does, this gets for free — including being
        // written to disk, since six hours is past the threshold.
        assertEquals(1, Cooldowns.dirtyCount());

        ItemCooldowns.start(player.player(), "pearl", Material.ENDER_PEARL,
                Duration.ofSeconds(16));
        Cooldowns.flushAll();

        // And the short one is still not worth a write.
        assertTrue(java.nio.file.Files.exists(dir.resolve(
                CooldownScope.player(player.player().getUniqueId()).storageId() + ".cd")));
    }
}
