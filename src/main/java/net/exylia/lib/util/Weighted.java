package net.exylia.lib.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToDoubleFunction;
import java.util.random.RandomGenerator;

/**
 * One entry of a list, rolled by weight.
 *
 * <pre>{@code
 * Material block = Weighted.pick(blocks, BlockEntry::weight).material();
 * }</pre>
 *
 * <p>Entries with a weight of zero or less are never rolled. When no entry has
 * a positive weight the list is treated as evenly weighted, so a list an admin
 * filled with zeros still picks something rather than nothing.
 *
 * @since 1.258.0
 */
public final class Weighted {

    private Weighted() {
    }

    /**
     * Rolls one entry.
     *
     * @param entries what to choose from
     * @param weight  how heavy each entry is
     * @return the entry rolled, or {@code null} when the list is empty
     */
    public static <T> @Nullable T pick(@NotNull List<T> entries, @NotNull ToDoubleFunction<? super T> weight) {
        return pick(entries, weight, ThreadLocalRandom.current());
    }

    /**
     * Rolls one entry with a given random source, for tests and seeded rolls.
     *
     * @param entries what to choose from
     * @param weight  how heavy each entry is
     * @param random  where the roll comes from
     * @return the entry rolled, or {@code null} when the list is empty
     */
    public static <T> @Nullable T pick(@NotNull List<T> entries, @NotNull ToDoubleFunction<? super T> weight,
                                       @NotNull RandomGenerator random) {
        if (entries.isEmpty()) {
            return null;
        }
        double total = 0;
        for (T entry : entries) {
            total += Math.max(0, weight.applyAsDouble(entry));
        }
        if (total <= 0) {
            return entries.get(random.nextInt(entries.size()));
        }
        double roll = random.nextDouble(total);
        T last = null;
        for (T entry : entries) {
            double each = Math.max(0, weight.applyAsDouble(entry));
            if (each <= 0) {
                continue;
            }
            last = entry;
            roll -= each;
            if (roll < 0) {
                return entry;
            }
        }
        // Rounding can leave the roll a hair above zero after the last entry.
        return last;
    }
}
