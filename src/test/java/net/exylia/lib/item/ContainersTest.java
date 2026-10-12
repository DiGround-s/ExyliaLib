package net.exylia.lib.item;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainersTest {

    @Test
    void materialsAreComparedIgnoringCase() {
        assertTrue(Containers.isBlocked(Material.BEDROCK, List.of("bedrock")));
        assertFalse(Containers.isBlocked(Material.DIRT, List.of("bedrock")));
        assertFalse(Containers.isBlocked((org.bukkit.inventory.ItemStack) null, List.of("bedrock")));
    }

    @Test
    void pathsGoUpAndDown() {
        assertEquals("6.13", Containers.child("6", 13));
        assertEquals("6", Containers.parentOf("6.13"));
        assertEquals("", Containers.parentOf("6"));
        assertEquals(2, Containers.depth("6.13"));
        assertEquals(0, Containers.depth(""));
    }
}
