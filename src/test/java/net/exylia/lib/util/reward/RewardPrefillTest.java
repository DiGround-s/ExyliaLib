package net.exylia.lib.util.reward;

import net.exylia.lib.item.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A reward made from an item opens its form on that item: its name and how many were put in. */
class RewardPrefillTest {

    private static RewardEntry item() {
        return RewardEntry.item("BLAZE_ROD").build();
    }

    @Test
    @DisplayName("an item with a name of its own lends it and its stack size")
    void customName() {
        RewardEntry entry = RewardDescriptor.prefilled(item(), "<gold>Barra de blaze", "Blaze Rod", 16);
        assertEquals("<gold>Barra de blaze", entry.name());
        assertEquals(16, entry.itemAmount());
        assertEquals(false, entry.isRanged());
    }

    @Test
    @DisplayName("a plain item is named after its material")
    void materialName() {
        RewardEntry entry = RewardDescriptor.prefilled(item(), null,
                Source.of("BLAZE_ROD").label(), 1);
        assertEquals("Blaze Rod", entry.name());
        assertEquals(1, entry.itemAmount());
    }

    @Test
    @DisplayName("a name already written is kept, and an empty stack still counts one")
    void keepsName() {
        RewardEntry named = RewardEntry.item("BLAZE_ROD").name("Mine").build();
        RewardEntry entry = RewardDescriptor.prefilled(named, "<gold>Other", "Blaze Rod", 0);
        assertEquals("Mine", entry.name());
        assertEquals(1, entry.itemAmount());
    }
}
