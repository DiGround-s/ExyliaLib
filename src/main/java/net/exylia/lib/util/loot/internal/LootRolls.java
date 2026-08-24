package net.exylia.lib.util.loot.internal;

import net.exylia.lib.util.loot.LootEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dice, and the two ways a loot table reads a weight.
 *
 * <p>Split out so everything a roll decides can be tested without a server and
 * without luck: a test that has to open a chest a hundred times to prove a
 * fifty-percent entry works is a test that fails on Fridays.
 *
 * <p>Nothing here touches Bukkit. Turning a chosen entry into an item is
 * {@link LootItems}' job, and that is the only part of the module a real server
 * is needed for.
 */
public final class LootRolls {

    private LootRolls() {
        throw new AssertionError("No instances.");
    }

    /** What the module asks when it needs a number. Replaced in tests. */
    public interface Dice {

        /** A number in {@code [0, bound)}. */
        double next(double bound);

        /** An integer in {@code [min, max]}, both ends included. */
        int between(int min, int max);

        /** Puts a list in a random order, in place. */
        void shuffle(List<?> list);
    }

    /** The real thing: per-thread, so two chests never contend. */
    public static final Dice RANDOM = new Dice() {

        @Override
        public double next(double bound) {
            return ThreadLocalRandom.current().nextDouble(bound);
        }

        @Override
        public int between(int min, int max) {
            return min >= max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
        }

        @Override
        public void shuffle(List<?> list) {
            java.util.Collections.shuffle(list, ThreadLocalRandom.current());
        }
    };

    /**
     * Rolls every entry on its own, each against its weight as a percentage.
     *
     * <p>This is what a chest and a spawner mean by a loot table: any number of
     * lines can come up, including all of them and including none.
     *
     * <p>{@code forceOneIfEmpty} is the difference between the two callers
     * ExyliaCommons had, and both behaviours are kept because both are right: a
     * chest that opened empty looks broken, so one entry is forced; a spawner
     * tick that produces nothing is a spawner tick that produced nothing.
     *
     * <p>The forced entry is picked uniformly, not by weight. That is what
     * commons did, and changing it would quietly make rare items common in
     * exactly the tables where every line is unlikely.
     *
     * @param entries         the table
     * @param forceOneIfEmpty whether an empty result should be avoided
     * @param dice            the dice
     * @return the entries that came up, in the order they were written
     */
    public static @NotNull List<LootEntry> independent(@NotNull List<LootEntry> entries,
                                                       boolean forceOneIfEmpty,
                                                       @NotNull Dice dice) {
        if (entries.isEmpty()) {
            return new ArrayList<>();
        }
        List<LootEntry> rolled = new ArrayList<>();
        for (LootEntry entry : entries) {
            if (dice.next(100.0) < entry.weight()) {
                rolled.add(entry);
            }
        }
        if (rolled.isEmpty() && forceOneIfEmpty) {
            rolled.add(entries.get(dice.between(0, entries.size() - 1)));
        }
        return rolled;
    }

    /**
     * Picks exactly one entry, each weight being its share of the total.
     *
     * <p>The other reading, and what a survival-games refill means: one item per
     * slot, and the weights decide which. Entries of weight zero or less are
     * never picked; if every entry is such, nothing is.
     *
     * @param entries the table
     * @param dice    the dice
     * @return the winner, or {@code null} if there could not be one
     */
    public static @Nullable LootEntry pick(@NotNull List<LootEntry> entries, @NotNull Dice dice) {
        double total = 0.0;
        for (LootEntry entry : entries) {
            if (entry.weight() > 0.0) {
                total += entry.weight();
            }
        }
        if (total <= 0.0) {
            return null;
        }
        double roll = dice.next(total);
        for (LootEntry entry : entries) {
            if (entry.weight() <= 0.0) {
                continue;
            }
            roll -= entry.weight();
            if (roll < 0.0) {
                return entry;
            }
        }
        // Only reachable through floating-point drift on the very last entry.
        for (int index = entries.size() - 1; index >= 0; index--) {
            if (entries.get(index).weight() > 0.0) {
                return entries.get(index);
            }
        }
        return null;
    }

    /**
     * How many of an item an entry gives.
     *
     * <p>Never below one: giving zero of something is not loot. Bounds the wrong
     * way round are read as the fixed low end, which is what commons did.
     *
     * @param entry the entry
     * @param dice  the dice
     * @return the stack size
     */
    public static int amount(@NotNull LootEntry entry, @NotNull Dice dice) {
        return Math.max(1, dice.between(entry.minAmount(), entry.maxAmount()));
    }
}
