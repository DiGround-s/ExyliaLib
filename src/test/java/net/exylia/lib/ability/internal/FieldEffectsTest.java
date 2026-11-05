package net.exylia.lib.ability.internal;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fishing waits scale safely, crops never grow past ripe, replant takes exactly one plain seed. */
class FieldEffectsTest {

    @Test
    void waitsScaleAndStayOrdered() {
        assertArrayEquals(new int[]{50, 300}, FieldEffects.waits(100, 600, 0.5));
        assertArrayEquals(new int[]{1, 1}, FieldEffects.waits(100, 600, 0), "never zero ticks");
        assertArrayEquals(new int[]{100, 600}, FieldEffects.waits(100, 600, Double.NaN));
        assertArrayEquals(new int[]{200, 1200}, FieldEffects.waits(100, 600, 2));
    }

    @Test
    void cropsNeverGrowPastRipe() {
        assertEquals(3, FieldEffects.grown(1, 7, 2));
        assertEquals(7, FieldEffects.grown(6, 7, 5));
        assertEquals(4, FieldEffects.grown(4, 7, -3));
    }

    @Test
    void coloursByNameOrHex() {
        assertEquals(NamedTextColor.AQUA, FieldEffects.colour("aqua"));
        assertEquals(NamedTextColor.DARK_PURPLE, FieldEffects.colour("Dark Purple"));
        assertEquals(TextColor.color(0x55ffff), FieldEffects.colour("#55FFFF"));
        assertNull(FieldEffects.colour("sparkly"));
    }

    @Test
    void replantTakesOnePlainSeed() {
        List<ItemStack> drops = new ArrayList<>(List.of(new Stub(Material.WHEAT, 2, false),
                new Stub(Material.WHEAT_SEEDS, 1, true), new Stub(Material.WHEAT_SEEDS, 3, false)));
        assertTrue(FieldEffects.takeOne(drops, Material.WHEAT_SEEDS));
        assertEquals(2, drops.get(2).getAmount());
        assertEquals(1, drops.get(1).getAmount(), "a named seed is not planted");
        List<ItemStack> single = new ArrayList<>(List.of(new Stub(Material.CARROT, 1, false)));
        assertTrue(FieldEffects.takeOne(single, Material.CARROT));
        assertTrue(single.isEmpty());
        assertFalse(FieldEffects.takeOne(single, Material.CARROT));
        assertEquals(Material.BEETROOT_SEEDS, FieldEffects.SEEDS.get(Material.BEETROOTS));
    }

    private static final class Stub extends ItemStack {
        private final Material type;
        private final boolean meta;
        private int amount;

        Stub(Material type, int amount, boolean meta) {
            this.type = type;
            this.amount = amount;
            this.meta = meta;
        }

        @Override public @NotNull Material getType() { return type; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public boolean hasItemMeta() { return meta; }
    }
}
