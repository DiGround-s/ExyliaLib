package net.exylia.lib.util;

import net.exylia.lib.item.internal.CustomItems;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Counting and taking items a player carries: the hotbar and the 27 storage
 * slots, never armour, the off hand or the crafting grid.
 *
 * <pre>{@code
 * // on the player's own thread
 * Predicate<ItemStack> wheat = CarriedItems.matching("wheat");          // or "nexo:ruby", "minecraft:wheat"
 * int carried = CarriedItems.count(player.getInventory(), wheat);
 * List<ItemStack> taken = CarriedItems.take(player.getInventory(), wheat, 64);
 * int units = CarriedItems.amount(taken);                               // up to 64
 * }</pre>
 *
 * <p><b>What matches.</b> {@link #matching(String)} is the exact item an
 * order, a delivery or a sale asks for: a Nexo, Oraxen, ItemsAdder or
 * CraftEngine id matches only that plugin's item; a vanilla id matches only a
 * plain stack of the material (no name, lore, enchantments, damage or other
 * data), so a renamed or enchanted item is never taken by accident.
 * {@link #similar(ItemStack)} matches a template by {@link ItemStack#isSimilar}.
 * Any other rule is a {@link Predicate} of the caller's.
 *
 * <p><b>Threads.</b> Call on the player's own thread ({@code runAtEntity} on
 * Folia). A take is one pass over the slots on that thread, so nothing else
 * changes the inventory between the count and the removal.
 *
 * @since 1.480.0
 */
public final class CarriedItems {

    /** Hotbar (0-8) and storage (9-35) of a player inventory. */
    private static final int SLOTS = 36;

    private CarriedItems() {
        throw new AssertionError("No instances.");
    }

    /**
     * The exact item an id names.
     *
     * @param id a custom id ({@code nexo:ruby_sword}, {@code ia:…}) or a vanilla
     *           material ({@code WHEAT}, {@code wheat}, {@code minecraft:wheat})
     * @return the test; one that accepts nothing for an unknown material
     */
    public static @NotNull Predicate<ItemStack> matching(@NotNull String id) {
        String written = Objects.requireNonNull(id, "id").trim();
        if (CustomItems.isCustom(written)) {
            String plugin = CustomItems.plugin(written);
            String inner = CustomItems.id(written);
            return stack -> {
                if (empty(stack) || !stack.hasItemMeta()) return false;
                String found = CustomItems.written(stack);
                return found != null && Objects.equals(plugin, CustomItems.plugin(found))
                        && inner.equalsIgnoreCase(CustomItems.id(found));
            };
        }
        Material material = Material.matchMaterial(written.toLowerCase(Locale.ROOT));
        if (material == null) return stack -> false;
        return stack -> !empty(stack) && stack.getType() == material && !stack.hasItemMeta();
    }

    /**
     * Items similar to a template: same material and data, any amount.
     *
     * @param template the item
     * @return the test
     */
    public static @NotNull Predicate<ItemStack> similar(@NotNull ItemStack template) {
        ItemStack copy = template.clone();
        return stack -> !empty(stack) && copy.isSimilar(stack);
    }

    /**
     * How many items the carried slots hold that the test accepts.
     *
     * @param inventory a player inventory (any inventory: its first 36 slots are read)
     * @param accepts   which stacks count
     * @return the total amount
     */
    public static int count(@NotNull Inventory inventory, @NotNull Predicate<ItemStack> accepts) {
        int total = 0;
        int slots = Math.min(SLOTS, inventory.getSize());
        for (int slot = 0; slot < slots; slot++) {
            ItemStack present = inventory.getItem(slot);
            if (!empty(present) && accepts.test(present)) total += present.getAmount();
        }
        return total;
    }

    /**
     * Takes up to {@code max} items the test accepts, hotbar first, in one pass.
     *
     * @param inventory a player inventory
     * @param accepts   which stacks may be taken
     * @param max       the most to take; zero or less takes nothing
     * @return copies of what was taken, one per slot taken from; empty when nothing was
     */
    public static @NotNull List<ItemStack> take(@NotNull Inventory inventory, @NotNull Predicate<ItemStack> accepts,
                                                int max) {
        List<ItemStack> taken = new ArrayList<>();
        int left = max;
        int slots = Math.min(SLOTS, inventory.getSize());
        for (int slot = 0; slot < slots && left > 0; slot++) {
            ItemStack present = inventory.getItem(slot);
            if (empty(present) || !accepts.test(present)) continue;
            int amount = present.getAmount();
            int take = Math.min(left, amount);
            ItemStack copy = present.clone();
            copy.setAmount(take);
            taken.add(copy);
            if (take == amount) {
                inventory.setItem(slot, null);
            } else {
                present.setAmount(amount - take);
                inventory.setItem(slot, present);
            }
            left -= take;
        }
        return taken;
    }

    /**
     * The total amount of a list of stacks, such as what {@link #take} answered.
     *
     * @param stacks the stacks
     * @return their amounts together
     */
    public static int amount(@NotNull List<ItemStack> stacks) {
        int total = 0;
        for (ItemStack stack : stacks) if (stack != null) total += stack.getAmount();
        return total;
    }

    private static boolean empty(@Nullable ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) return true;
        // Against the constants: isAir() asks the block registry, which only a running server has.
        Material type = stack.getType();
        return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR;
    }
}
