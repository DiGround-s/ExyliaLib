package net.exylia.lib.progression;

import net.exylia.lib.util.upgrade.Curve;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.LongFunction;
import java.util.function.LongUnaryOperator;

/**
 * Layered prestige: a rebirth that costs more each time and pays tokens, and layers above it (ascension,
 * transcendence, ...) that each start over the layer below for a lasting multiplier and a reward. Pure numbers, so a
 * plugin keeps its own player model and only asks what a step costs, pays and resets.
 *
 * <p>Steps are counted from 1: {@code price.apply(n)} is what the n-th step costs.
 */
public final class Prestige {

    private Prestige() {
        throw new AssertionError("No instances.");
    }

    /** What the next {@code count} steps after {@code done} cost together. */
    public static BigDecimal price(LongFunction<BigDecimal> price, long done, int count) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 1; i <= count; i++) total = total.add(price.apply(done + i), Curve.MC);
        return total;
    }

    /** What the next {@code count} steps after {@code done} pay together, before any bonus. */
    public static long reward(LongUnaryOperator reward, long done, int count) {
        long total = 0;
        for (int i = 1; i <= count; i++) total += reward.applyAsLong(done + i);
        return total;
    }

    /** As many steps after {@code done} as {@code coins} pay for, up to {@code max}. */
    public static int affordable(LongFunction<BigDecimal> price, long done, BigDecimal coins, int max) {
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        while (count < max) {
            total = total.add(price.apply(done + count + 1), Curve.MC);
            if (total.compareTo(coins) > 0) break;
            count++;
        }
        return count;
    }

    /** True when going from {@code before} to {@code after} reaches {@code milestone} for the first time; 0 is off. */
    public static boolean reached(long before, long after, long milestone) {
        return milestone > 0 && before < milestone && after >= milestone;
    }

    /** The layer under {@code id} in {@code order}, or null for the first (which starts over the base rebirths). */
    public static @Nullable String below(List<String> order, String id) {
        int at = order.indexOf(id);
        return at <= 0 ? null : order.get(at - 1);
    }

    /** The layers climbing {@code id} resets: every one under it in {@code order}. */
    public static List<String> resets(List<String> order, String id) {
        return order.subList(0, Math.max(0, order.indexOf(id)));
    }

    /**
     * One layer above rebirth.
     *
     * @param needs       how many of the layer below the first climb takes
     * @param needsGrowth how many more each next climb takes
     * @param reward      what every climb pays (stars, gems...)
     * @param rewardEvery one more reward for every this many of the layer below past {@code needs}; 0 for none
     * @param multiplier  what each climb adds to the multiplier (1: the first doubles it)
     */
    public record Layer(long needs, long needsGrowth, long reward, long rewardEvery, double multiplier) {

        /** How many of the layer below the next climb takes, after {@code climbed} climbs. */
        public long needs(long climbed) {
            return Math.max(1, needs + Math.max(0, needsGrowth) * climbed);
        }

        public boolean ready(long have, long climbed) {
            return have >= needs(climbed);
        }

        /** What the next climb pays with {@code have} of the layer below. */
        public long reward(long have, long climbed) {
            long extra = rewardEvery <= 0 ? 0 : Math.max(0, have - needs(climbed)) / rewardEvery;
            return Math.max(0, reward) + extra;
        }

        /** The multiplier {@code count} climbs give. */
        public double multiplier(long count) {
            return 1 + Math.max(0, multiplier) * count;
        }
    }
}
