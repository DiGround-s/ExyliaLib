package net.exylia.lib.util.reward;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** The split behind {@link PluginRewards#deliver(java.util.UUID, org.bukkit.inventory.ItemStack, int)}. */
class StackSizesTest {

    @Test
    void fullStacksThenTheRest() {
        assertArrayEquals(new int[]{64, 64, 2}, PluginRewards.stackSizes(64, 130));
        assertArrayEquals(new int[]{16, 16}, PluginRewards.stackSizes(16, 32));
        assertArrayEquals(new int[]{5}, PluginRewards.stackSizes(64, 5));
    }

    @Test
    void unstackableAndNothing() {
        assertArrayEquals(new int[]{1, 1, 1}, PluginRewards.stackSizes(1, 3));
        assertArrayEquals(new int[]{1, 1}, PluginRewards.stackSizes(0, 2));
        assertArrayEquals(new int[0], PluginRewards.stackSizes(64, 0));
        assertArrayEquals(new int[0], PluginRewards.stackSizes(64, -4));
    }
}
