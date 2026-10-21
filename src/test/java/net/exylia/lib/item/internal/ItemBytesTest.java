package net.exylia.lib.item.internal;

import net.exylia.lib.schematic.internal.Nbt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Item bytes from a newer server, read as this one's. */
class ItemBytesTest {

    /** What a 26.x server stored for a staff restore: data version 4903, read on 1.21.11 (4671). */
    private static byte[] newerSword() throws Exception {
        Map<String, Object> enchantments = new LinkedHashMap<>();
        enchantments.put("minecraft:sharpness", 5);
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("minecraft:enchantments", enchantments);
        components.put("minecraft:damage", 12);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", "minecraft:diamond_sword");
        root.put("count", 1);
        root.put("components", components);
        root.put("DataVersion", 4903);
        return Nbt.gzip(root);
    }

    @Test
    @DisplayName("a newer item claims this server's data version and keeps everything else")
    void newerIsLowered() throws Exception {
        Map<String, Object> read = Nbt.gunzip(ItemBytes.lowered(newerSword(), 4671));

        assertEquals(4671, read.get("DataVersion"));
        assertEquals("minecraft:diamond_sword", read.get("id"));
        assertEquals(1, read.get("count"));
        assertEquals(Nbt.gunzip(newerSword()).get("components"), read.get("components"));
    }

    @Test
    @DisplayName("an item this server can already read, or bytes that are no item, are left to the game")
    void otherwiseUntouched() throws Exception {
        assertNull(ItemBytes.lowered(newerSword(), 4903), "same version: the refusal was not about it");
        assertNull(ItemBytes.lowered(newerSword(), 5000), "older: the game upgrades it itself");
        assertNull(ItemBytes.lowered(new byte[] {1, 2, 3}, 4671), "not gzipped NBT");
    }
}
