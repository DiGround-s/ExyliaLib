package net.exylia.lib.util.reward.internal;

import net.exylia.lib.util.reward.RewardEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dice.
 *
 * <p>Split out so the runtime can be tested without them: a test that has to
 * roll a hundred times to prove a fifty-percent reward works is a test that
 * fails on Fridays.
 */
public final class Rolls {

    private Rolls() {
        throw new AssertionError("No instances.");
    }

    /** What the runtime asks when it needs a number. Replaced in tests. */
    public interface Dice {
        /** A number in {@code [0, bound)}. */
        double next(double bound);

        /** An integer in {@code [min, max]}, both ends included. */
        int between(int min, int max);
    }

    /** The real thing: per-thread, so two deliveries never contend. */
    public static final Dice RANDOM = new Dice() {
        @Override
        public double next(double bound) {
            return ThreadLocalRandom.current().nextDouble(bound);
        }

        @Override
        public int between(int min, int max) {
            return min >= max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
        }
    };

    /**
     * Whether a reward's chance came up.
     *
     * <p>{@code chance} is a percentage. A guaranteed reward does not roll at
     * all, which is what makes an ordinary delivery cost nothing.
     *
     * @param entry the reward
     * @param dice  the dice
     * @return whether it happens
     */
    public static boolean rolled(@NotNull RewardEntry entry, @NotNull Dice dice) {
        double chance = entry.chance();
        if (chance >= RewardEntry.ALWAYS) {
            return true;
        }
        if (chance <= 0.0) {
            return false;
        }
        return dice.next(100.0) < chance;
    }

    /**
     * How many of an item to give.
     *
     * <p>A range wins over the fixed amount; a fixed amount is never below one,
     * because giving zero of something is not a reward.
     *
     * @param entry the reward
     * @param dice  the dice
     * @return how many
     */
    public static int amount(@NotNull RewardEntry entry, @NotNull Dice dice) {
        if (entry.isRanged()) {
            return Math.max(1, dice.between(entry.minAmount(), entry.maxAmount()));
        }
        return Math.max(1, entry.itemAmount());
    }

    /**
     * Picks one reward out of a group, by weight.
     *
     * <p>What a loot table does: exactly one of these happens, and the weights
     * decide which. Entries of weight zero or less never win; if every entry is
     * such, nothing is picked.
     *
     * @param rewards the group
     * @param dice    the dice
     * @return the winner, or {@code null} if there could not be one
     */
    public static @Nullable RewardEntry pick(@NotNull List<RewardEntry> rewards,
                                             @NotNull Rolls.Dice dice) {
        double total = 0.0;
        for (RewardEntry entry : rewards) {
            if (entry.weight() > 0.0) {
                total += entry.weight();
            }
        }
        if (total <= 0.0) {
            return null;
        }
        double roll = dice.next(total);
        for (RewardEntry entry : rewards) {
            if (entry.weight() <= 0.0) {
                continue;
            }
            roll -= entry.weight();
            if (roll < 0.0) {
                return entry;
            }
        }
        // Only reachable through floating-point drift on the very last entry.
        for (int index = rewards.size() - 1; index >= 0; index--) {
            if (rewards.get(index).weight() > 0.0) {
                return rewards.get(index);
            }
        }
        return null;
    }

    /**
     * Puts a list in the order it should be given out.
     *
     * <p>Higher priority first, and equal priorities keep the order the file
     * wrote them in &mdash; {@link List#sort} is stable, which is what makes
     * that true rather than merely likely.
     *
     * @param rewards the rewards as written
     * @return them, in the order they should be given
     */
    public static @NotNull List<RewardEntry> ordered(@NotNull List<RewardEntry> rewards) {
        if (rewards.size() < 2) {
            return rewards;
        }
        List<RewardEntry> ordered = new ArrayList<>(rewards);
        ordered.sort(Comparator.comparingInt(RewardEntry::priority).reversed());
        return ordered;
    }
}
