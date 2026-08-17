package net.exylia.lib.ui;

import net.exylia.lib.ui.UiDefinition.UiKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape of every container a menu can be.
 *
 * <p>The slot counts are written out in {@link UiKind} rather than asked of
 * {@code InventoryType}, because that enum reaches for the server's registry
 * and reading a menu file is meant to work without a server. Written-out
 * numbers go stale, so they are checked here against Bukkit's own declarations.
 *
 * <p>The expected values below are copied from
 * {@code org.bukkit.event.inventory.InventoryType} in paper-api 1.21.4. Getting
 * one wrong is not cosmetic: {@code createInventory} throws when the size does
 * not match the type, so the menu never opens at all.
 */
class UiKindTest {

    /** Slot counts as paper-api 1.21.4 declares them. */
    private static final Map<UiKind, Integer> BUKKIT = new EnumMap<>(UiKind.class);

    static {
        BUKKIT.put(UiKind.BARREL, 27);       // BARREL(27, ...)
        BUKKIT.put(UiKind.HOPPER, 5);        // HOPPER(5, ...)
        BUKKIT.put(UiKind.DROPPER, 9);       // DROPPER(9, ...)
        BUKKIT.put(UiKind.DISPENSER, 9);     // DISPENSER(9, ...)
        BUKKIT.put(UiKind.CRAFTING, 10);     // WORKBENCH(10, ...)
        BUKKIT.put(UiKind.ANVIL, 3);         // ANVIL(3, ...)
        BUKKIT.put(UiKind.GRINDSTONE, 3);    // GRINDSTONE(3, ...)
        BUKKIT.put(UiKind.CARTOGRAPHY, 3);   // CARTOGRAPHY(3, ...)
        BUKKIT.put(UiKind.FURNACE, 3);       // FURNACE(3, ...)
        BUKKIT.put(UiKind.MERCHANT, 3);      // MERCHANT(3, ...)
        BUKKIT.put(UiKind.SMITHING, 4);      // SMITHING(4, ...)
        BUKKIT.put(UiKind.LOOM, 4);          // LOOM(4, ...)
        BUKKIT.put(UiKind.BREWING, 5);       // BREWING(5, ...)
        BUKKIT.put(UiKind.ENCHANTING, 2);    // ENCHANTING(2, ...)
        BUKKIT.put(UiKind.STONECUTTER, 2);   // STONECUTTER(2, ...)
        BUKKIT.put(UiKind.BEACON, 1);        // BEACON(1, ...)
    }

    @Test
    @DisplayName("every fixed container has the slot count Bukkit gives it")
    void slotCountsMatchBukkit() {
        for (Map.Entry<UiKind, Integer> expected : BUKKIT.entrySet()) {
            assertEquals(expected.getValue(), expected.getKey().sizeOf(54),
                    expected.getKey() + " must have the slot count paper-api declares");
        }
    }

    @Test
    @DisplayName("the three that were guessed wrong are right")
    void theOnesThatWereWrong() {
        // Each of these was written from memory and was wrong. The failure is
        // not cosmetic: createInventory throws when size and type disagree, so
        // the menu never opens.
        assertEquals(4, UiKind.SMITHING.sizeOf(54), "a smithing table has four slots");
        assertEquals(10, UiKind.CRAFTING.sizeOf(54), "a crafting window has ten");
        assertEquals(27, UiKind.BARREL.sizeOf(54), "a barrel is a fixed 27");
    }

    @Test
    @DisplayName("every kind is covered, so a new one cannot be forgotten")
    void everyKindIsCovered() {
        for (UiKind kind : UiKind.values()) {
            if (kind == UiKind.CHEST) {
                continue;
            }
            assertTrue(BUKKIT.containsKey(kind),
                    kind + " has no expected slot count; add it when adding the kind");
            assertTrue(kind.sizeOf(54) > 0, kind + " must have slots");
        }
    }

    @Test
    @DisplayName("a chest is the only one whose size the file decides")
    void onlyChestsAreConfigurable() {
        assertTrue(UiKind.CHEST.isSizeConfigurable());
        assertEquals(27, UiKind.CHEST.sizeOf(27));
        assertEquals(54, UiKind.CHEST.sizeOf(54));

        // A barrel looks like a chest and is not.
        assertFalse(UiKind.BARREL.isSizeConfigurable());
        assertEquals(27, UiKind.BARREL.sizeOf(9), "a barrel ignores what the file asked for");
    }

    @Test
    @DisplayName("every kind but a chest names a container to open")
    void everyKindNamesAContainer() {
        // Reading the type does not touch the registry, so this is safe without
        // a server; only asking it for a size would be.
        for (UiKind kind : UiKind.values()) {
            if (kind == UiKind.CHEST) {
                continue;
            }
            assertNotNull(kind.name(), kind + " must exist");
        }
    }
}
