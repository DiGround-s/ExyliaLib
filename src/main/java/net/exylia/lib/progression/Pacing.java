package net.exylia.lib.progression;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * The engine of a pace simulator: a model player earns at a rate, picks what to buy, and the simulator reads the
 * times between steps against the ranges a design wants. The plugin keeps its own player model (what it earns, what
 * it can buy, what a purchase does) and asks this which purchase to make.
 */
public final class Pacing {

    private Pacing() {
        throw new AssertionError("No instances.");
    }

    /**
     * The purchase a greedy player makes on the way to {@code target}: only one that brings the target closer than
     * farming for it alone, and among those the quickest to pay itself back. Options priced at or above the target are
     * skipped, since farming for the target already reaches them.
     *
     * @param coins     what the player holds
     * @param rate      what they earn a second now
     * @param target    what they save up for (the next rebirth)
     * @param options   what they could buy
     * @param price     an option's price
     * @param rateAfter what they would earn a second after buying it; 0 or less when it cannot be bought
     * @return the pick, or null to farm for the target
     */
    public static <T> @Nullable T pick(double coins, double rate, double target, List<T> options,
                                       ToDoubleFunction<T> price, ToDoubleFunction<T> rateAfter) {
        double alone = (target - coins) / rate;
        T best = null;
        double bestPayback = 0;
        for (T option : options) {
            double cost = price.applyAsDouble(option);
            if (cost >= target) continue;
            double wait = Math.max(0, cost - coins) / rate;
            double after = coins + wait * rate - cost;
            double faster = rateAfter.applyAsDouble(option);
            if (faster <= rate) continue;
            if (wait + Math.max(0, target - after) / faster >= alone) continue;
            double payback = wait + cost / (faster - rate);
            if (best == null || payback < bestPayback) {
                best = option;
                bestPayback = payback;
            }
        }
        return best;
    }

    /** The median of {@code values}, NaN when empty. */
    public static double median(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }

    /**
     * A line when {@code value} falls outside {@code min}-{@code max} (or is NaN), null when it is on pace:
     * {@code "A rebirth every 130.0 min, wanted 40-120"}.
     */
    public static @Nullable String outside(String what, double value, double min, double max, String unit) {
        if (!Double.isNaN(value) && value >= min && value <= max) return null;
        return String.format("%s %.1f %s, wanted %s-%s", what, value, unit, num(min), num(max));
    }

    private static String num(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
