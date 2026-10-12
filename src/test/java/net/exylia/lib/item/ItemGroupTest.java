package net.exylia.lib.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ItemGroupTest {

    @Test
    void namedGroupsNeedNoServer() {
        assertEquals(ItemGroup.ENCHANTED_BOOKS, ItemGroup.named("ENCHANTED_BOOK"));
        assertEquals(ItemGroup.POTIONS, ItemGroup.named("SPLASH_POTION"));
        assertEquals(ItemGroup.FOOD, ItemGroup.named("COOKED_BEEF"));
        assertEquals(ItemGroup.ARMOUR, ItemGroup.named("NETHERITE_CHESTPLATE"));
        assertEquals(ItemGroup.ARMOUR, ItemGroup.named("ELYTRA"));
        assertEquals(ItemGroup.TOOLS, ItemGroup.named("DIAMOND_PICKAXE"));
        assertEquals(ItemGroup.TOOLS, ItemGroup.named("BOW"));
        assertEquals(ItemGroup.REDSTONE, ItemGroup.named("REDSTONE_TORCH"));
        assertEquals(ItemGroup.REDSTONE, ItemGroup.named("OAK_PRESSURE_PLATE"));
        assertEquals(ItemGroup.REDSTONE, ItemGroup.named("POWERED_RAIL"));
        assertNull(ItemGroup.named("STONE"));
        assertNull(ItemGroup.named("DIAMOND"));
    }

    @Test
    void idsRoundTrip() {
        for (ItemGroup group : ItemGroup.values()) assertEquals(group, ItemGroup.byId(group.id()).orElseThrow());
        assertEquals(ItemGroup.ARMOUR, ItemGroup.byId("armor").orElseThrow());
        assertEquals(ItemGroup.ENCHANTED_BOOKS, ItemGroup.byId("ENCHANTED_BOOKS").orElseThrow());
    }
}
