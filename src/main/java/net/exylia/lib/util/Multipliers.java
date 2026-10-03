package net.exylia.lib.util;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Multipliers that are not whole numbers, applied to things that are.
 *
 * <pre>{@code
 * int xp = Multipliers.scale(3, 1.2);             // 3 or 4, 3.6 on average
 * List<ItemStack> drops = Multipliers.scale(items, 1.5);
 * String shown = Multipliers.format(1.25);        // "1.25"
 * }</pre>
 *
 * <p>The fraction is rolled rather than rounded: one block's single drop at
 * x1.2 is two drops a fifth of the time, so a small amount gains what the
 * multiplier says on average instead of never gaining anything.
 *
 * @since 1.226.0
 */
public final class Multipliers {

    private Multipliers() {
        throw new AssertionError("No instances.");
    }

    /**
     * An amount multiplied, the fraction rolled.
     *
     * @param amount the amount; zero or less is returned as it is
     * @param factor the multiplier; below zero counts as zero
     * @return the new amount
     */
    public static int scale(int amount, double factor) {
        if (factor == 1.0 || amount <= 0) return amount;
        double exact = amount * Math.max(0.0, factor);
        int whole = (int) Math.floor(exact);
        return ThreadLocalRandom.current().nextDouble() < exact - whole ? whole + 1 : whole;
    }

    /**
     * Items multiplied, each one's fraction rolled, cut into stacks no larger
     * than the item allows.
     *
     * @param items  the items; never changed
     * @param factor the multiplier
     * @return new stacks, or copies of the same ones when the factor is 1
     */
    public static @NotNull List<ItemStack> scale(@NotNull Collection<ItemStack> items, double factor) {
        if (factor == 1.0 || items.isEmpty()) return new ArrayList<>(items);
        List<ItemStack> out = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            split(item, scale(item.getAmount(), factor), out);
        }
        return out;
    }

    /**
     * Adds {@code total} of an item to {@code out}, a full stack at a time.
     *
     * @param item  the item; never changed
     * @param total how many
     * @param out   where the stacks go
     */
    public static void split(@NotNull ItemStack item, int total, @NotNull List<ItemStack> out) {
        int max = Math.max(1, item.getMaxStackSize());
        while (total > 0) {
            ItemStack stack = item.clone();
            stack.setAmount(Math.min(max, total));
            out.add(stack);
            total -= stack.getAmount();
        }
    }

    /**
     * A multiplier as a person reads it: {@code 2}, {@code 1.5}, {@code 1.25}.
     *
     * @param factor the multiplier
     * @return at most two decimals, none when it is whole
     */
    public static @NotNull String format(double factor) {
        return BigDecimal.valueOf(factor).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
