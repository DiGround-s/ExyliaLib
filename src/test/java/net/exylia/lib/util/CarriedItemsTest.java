package net.exylia.lib.util;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Only plain stacks of a vanilla id count, only carried slots are read, and a take stops at the limit. */
class CarriedItemsTest {

    private final ItemStack[] slots = new ItemStack[41];
    private final Inventory inventory = (Inventory) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{Inventory.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getSize" -> slots.length;
                case "getItem" -> slots[(int) args[0]];
                case "setItem" -> {
                    slots[(int) args[0]] = (ItemStack) args[1];
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });

    @Test
    void vanillaIdsMatchOnlyPlainStacks() {
        Predicate<ItemStack> wheat = CarriedItems.matching("minecraft:wheat");
        assertTrue(wheat.test(new Stub(Material.WHEAT, 3, false)));
        assertTrue(CarriedItems.matching("WHEAT").test(new Stub(Material.WHEAT, 1, false)));
        assertFalse(wheat.test(new Stub(Material.WHEAT, 3, true)), "a renamed or enchanted stack is not taken");
        assertFalse(wheat.test(new Stub(Material.CARROT, 3, false)));
        assertFalse(wheat.test(null));
        assertFalse(CarriedItems.matching("not_a_material").test(new Stub(Material.WHEAT, 1, false)));
    }

    @Test
    void countAndTakeReadOnlyCarriedSlots() {
        slots[0] = new Stub(Material.WHEAT, 10, false);
        slots[5] = new Stub(Material.WHEAT, 10, true);
        slots[20] = new Stub(Material.WHEAT, 30, false);
        slots[38] = new Stub(Material.WHEAT, 64, false);   // armour: never counted
        Predicate<ItemStack> wheat = CarriedItems.matching("wheat");

        assertEquals(40, CarriedItems.count(inventory, wheat));
        List<ItemStack> taken = CarriedItems.take(inventory, wheat, 25);
        assertEquals(25, CarriedItems.amount(taken));
        assertEquals(2, taken.size());
        assertNull(slots[0]);
        assertEquals(15, slots[20].getAmount());
        assertEquals(10, slots[5].getAmount());
        assertEquals(64, slots[38].getAmount());

        assertEquals(15, CarriedItems.amount(CarriedItems.take(inventory, wheat, 100)));
        assertTrue(CarriedItems.take(inventory, wheat, 5).isEmpty());
        assertTrue(CarriedItems.take(inventory, stack -> true, 0).isEmpty());
    }

    /** A stack without a server: {@code new ItemStack(...)} needs the item registry. */
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
        @Override public @NotNull ItemStack clone() { return new Stub(type, amount, meta); }
    }
}
