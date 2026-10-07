package net.exylia.lib.ui.internal;

import net.exylia.lib.item.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a redraw may leave a slot as it is.
 *
 * <p>Only when the slot would come out identical, and only when the values it
 * was drawn with are everything it depends on. Getting the second half wrong
 * would freeze a PlaceholderAPI value on screen, which is worse than redrawing.
 */
class DrawnSlotsTest {

    private static final Item ROW = Item.of("%mine_icon%")
            .name("{primary}&l%mine_name%")
            .lore(List.of("Next reset » %next_reset%"))
            .build();

    @Test
    @DisplayName("placeholders are each %name% without a space")
    void readsPlaceholders() {
        assertEquals(Set.of("a", "b_c"), DrawnSlots.placeholdersIn("%a% and %b_c%"));
        assertEquals(Set.of(), DrawnSlots.placeholdersIn("50% off 20%"));
        assertEquals(Set.of("x"), DrawnSlots.placeholdersIn("50% off %x%"));
        assertEquals(Set.of(), DrawnSlots.placeholdersIn(null));
        assertEquals(Set.of("mine_icon", "mine_name", "next_reset"), DrawnSlots.placeholdersOf(ROW));
    }

    @Test
    @DisplayName("a slot drawn from the same values is left alone")
    void sameValuesAreSkipped() {
        DrawnSlots drawn = new DrawnSlots();
        Map<String, String> values = Map.of("mine_icon", "STONE", "mine_name", "A", "next_reset", "2m");

        drawn.record(3, ROW, values, Set.of(), Set.of());

        assertTrue(drawn.unchanged(3, ROW, Map.copyOf(values), Set.of(), Set.of()));
        assertFalse(drawn.unchanged(3, ROW, Map.of("mine_icon", "STONE", "mine_name", "A",
                "next_reset", "1m59s"), Set.of(), Set.of()), "a countdown that moved");
        assertFalse(drawn.unchanged(3, ROW, values, Set.of("mine_name"), Set.of()), "formatting changed");
        assertFalse(drawn.unchanged(4, ROW, values, Set.of(), Set.of()), "another slot");
    }

    @Test
    @DisplayName("a placeholder nobody handed over is always drawn again")
    void externalPlaceholderIsNeverSkipped() {
        DrawnSlots drawn = new DrawnSlots();
        Item ping = Item.of("STONE").name("Ping %player_ping%").build();

        drawn.record(0, ping, Map.of(), Set.of(), Set.of());

        assertFalse(drawn.unchanged(0, ping, Map.of(), Set.of(), Set.of()));
    }

    @Test
    @DisplayName("a formatted value carrying a placeholder is always drawn again")
    void placeholderInsideAFormattedValue() {
        DrawnSlots drawn = new DrawnSlots();
        Item item = Item.of("STONE").name("%rank%").build();
        Map<String, String> values = Map.of("rank", "{highlight}%vault_rank%");

        drawn.record(0, item, values, Set.of("rank"), Set.of());

        assertFalse(drawn.unchanged(0, item, values, Set.of("rank"), Set.of()));
        drawn.record(0, item, values, Set.of(), Set.of());
        assertTrue(drawn.unchanged(0, item, values, Set.of(), Set.of()), "literal, so it is just text");
    }

    @Test
    @DisplayName("a static item is drawn once and a forgotten slot is drawn again")
    void staticAndForgotten() {
        DrawnSlots drawn = new DrawnSlots();
        Item glass = Item.of("GRAY_STAINED_GLASS_PANE").build();

        drawn.record(9, glass, Map.of(), Set.of(), Set.of());
        assertTrue(drawn.unchanged(9, glass, Map.of(), Set.of(), Set.of()));

        drawn.forget(9);
        assertFalse(drawn.unchanged(9, glass, Map.of(), Set.of(), Set.of()));
    }
}
