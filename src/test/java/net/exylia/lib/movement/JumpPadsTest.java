package net.exylia.lib.movement;

import org.bukkit.Material;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JumpPadsTest {

    private static final JumpPad BLOCK = JumpPad.of("block", new PadTrigger.At("lobby", 10, 64, 10), 1.5, 0.8);
    private static final JumpPad PLATES = JumpPad.of("plates",
            PadTrigger.materials(Set.of(Material.STONE_PRESSURE_PLATE), Set.of(Material.SLIME_BLOCK)), 1.0, 1.0);
    private static final JumpPad SLIME = JumpPad.of("slime", PadTrigger.materials(Set.of(Material.SLIME_BLOCK)), 2.0, 1.2);

    @Test
    @DisplayName("a block pad matches at the feet or under them, and wins over a material rule")
    void blockPad() {
        List<JumpPad> pads = List.of(PLATES, BLOCK);
        assertEquals(BLOCK, JumpPads.match(pads, "lobby", 10, 64, 10, Material.AIR, Material.STONE, Material.STONE));
        assertEquals(BLOCK, JumpPads.match(pads, "lobby", 10, 65, 10, Material.STONE_PRESSURE_PLATE,
                Material.SLIME_BLOCK, Material.STONE));
        assertNull(JumpPads.match(pads, "other", 10, 64, 10, Material.AIR, Material.STONE, Material.STONE));
    }

    @Test
    @DisplayName("a material rule needs its block below, a full-block rule matches under the feet")
    void materialRules() {
        List<JumpPad> pads = List.of(PLATES, SLIME);
        assertEquals(PLATES, JumpPads.match(pads, "w", 0, 64, 0, Material.STONE_PRESSURE_PLATE,
                Material.SLIME_BLOCK, Material.STONE));
        assertNull(JumpPads.match(List.of(PLATES), "w", 0, 64, 0, Material.STONE_PRESSURE_PLATE,
                Material.DIRT, Material.STONE));
        assertEquals(SLIME, JumpPads.match(pads, "w", 0, 64, 0, Material.AIR, Material.SLIME_BLOCK, Material.STONE));
    }

    @Test
    @DisplayName("a pad throws the way the player looks, or towards its fixed yaw")
    void direction() {
        Vector looking = BLOCK.velocity(0f);              // yaw 0 faces south, +z
        assertEquals(0.0, looking.getX(), 1e-9);
        assertEquals(1.5, looking.getZ(), 1e-9);
        assertEquals(0.8, looking.getY(), 1e-9);
        Vector east = BLOCK.towards(-90f).velocity(0f);   // the player's yaw is ignored
        assertEquals(1.5, east.getX(), 1e-9);
        assertEquals(0.0, east.getZ(), 1e-9);
    }

    @Test
    @DisplayName("a double jump flattens the look direction and keeps its vertical power")
    void doubleJumpVelocity() {
        Vector up = DoubleJump.velocity(new Vector(0, 1, 0), 0.9, 0.75);
        assertEquals(new Vector(0, 0.75, 0), up);
        Vector ahead = DoubleJump.velocity(new Vector(3, -2, 4), 1.0, 0.5);
        assertEquals(0.6, ahead.getX(), 1e-9);
        assertEquals(0.8, ahead.getZ(), 1e-9);
        assertEquals(0.5, ahead.getY(), 1e-9);
    }
}
