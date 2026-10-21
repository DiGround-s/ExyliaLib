package net.exylia.lib.item.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a {@code material} value naming a resource-pack plugin's item is read. */
class CustomItemsTest {

    @Test
    @DisplayName("every provider prefix and alias is recognised, in any case")
    void prefixes() {
        for (String written : new String[] {"nexo:ruby", "oraxen:ruby", "itemsadder:pack:ruby", "ia:pack:ruby",
                "craftengine:default:ruby", "ce:default:ruby", "NEXO:RUBY", " nexo:ruby "}) {
            assertTrue(CustomItems.isCustom(written), written);
        }
    }

    @Test
    @DisplayName("vanilla keys, heads, snapshots and empty ids are not custom")
    void notCustom() {
        for (String written : new String[] {"DIAMOND_SWORD", "minecraft:stone", "playerhead-Notch",
                "bytes:rO0ABXNy", "nexo:", ":ruby", "mmoitems:SWORD:X", "%icon%"}) {
            assertFalse(CustomItems.isCustom(written), written);
        }
    }

    @Test
    @DisplayName("the id keeps its own namespace and the provider is named for a person")
    void parts() {
        assertEquals("ruby_sword", CustomItems.id("nexo:ruby_sword"));
        assertEquals("pack:ruby", CustomItems.id("ia:pack:ruby"));
        assertEquals("DIAMOND_SWORD", CustomItems.id("DIAMOND_SWORD"));
        assertEquals("ItemsAdder", CustomItems.plugin("itemsadder:pack:ruby"));
        assertEquals("CraftEngine", CustomItems.plugin("CE:default:ruby"));
        assertNull(CustomItems.plugin("STONE"));
    }

    @Test
    @DisplayName("with no provider running nothing is built and nothing throws")
    void absent() {
        assertNull(CustomItems.item("nexo:ruby"));
        assertNull(CustomItems.item("STONE"));
    }
}
