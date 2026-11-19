package net.exylia.lib.util.upgrade;

import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A level track with no last level: each level costs {@code growth} times the one before, and
 * past {@code softCap} each level is worth less than the one before but never nothing.
 *
 * <p>For idle and clicker games, whose prices outgrow a {@code double} long before the game ends.
 * {@link Upgrade} stays the track for levels with a maximum and a formula.
 *
 * <pre>{@code
 * Curve strength = new Curve(new BigDecimal("50"), new BigDecimal("1.15"), 100, 0.5);
 * strength.price(owned);                 // the next level: 50 × 1.15^owned
 * strength.price(owned, 10);             // the next ten together
 * strength.affordable(owned, balance);   // how many of the next levels the balance pays for
 * strength.levels(owned);                // effective levels: 120 owned past a cap of 100 count as 104.47
 * }</pre>
 *
 * @param base     the first level's price; positive
 * @param growth   what each level's price is multiplied by; 1 or more
 * @param softCap  levels that count in full; 0 or less counts every level in full
 * @param falloff  the power levels past the cap are raised to, in (0, 1]: 0.5 counts 100 levels
 *                 past the cap as 10, 1 counts them all
 */
public record Curve(@NotNull BigDecimal base, @NotNull BigDecimal growth, int softCap, double falloff) {

    /** Twenty significant digits: far past what a player reads, and products stay small. */
    public static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    public Curve {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(growth, "growth");
        if (base.signum() <= 0) throw new IllegalArgumentException("base must be positive: " + base);
        if (growth.compareTo(BigDecimal.ONE) < 0) throw new IllegalArgumentException("growth must be 1 or more: " + growth);
        if (!(falloff > 0 && falloff <= 1)) throw new IllegalArgumentException("falloff must be in (0, 1]: " + falloff);
    }

    /** A track whose every level counts in full. */
    public Curve(@NotNull BigDecimal base, @NotNull BigDecimal growth) {
        this(base, growth, 0, 1);
    }

    /** The price of the next level with {@code owned} levels bought: {@code base × growth^owned}. */
    public @NotNull BigDecimal price(int owned) {
        return base.multiply(growth.pow(Math.max(0, owned), MC), MC);
    }

    /** The price of the next {@code count} levels bought together; zero for none. */
    public @NotNull BigDecimal price(int owned, int count) {
        if (count <= 0) return BigDecimal.ZERO;
        BigDecimal first = price(owned);
        if (count == 1) return first;
        if (growth.compareTo(BigDecimal.ONE) == 0) return first.multiply(BigDecimal.valueOf(count), MC);
        // a geometric series: first × (growth^count − 1) / (growth − 1)
        BigDecimal factor = growth.pow(count, MC).subtract(BigDecimal.ONE, MC);
        return first.multiply(factor, MC).divide(growth.subtract(BigDecimal.ONE, MC), MC);
    }

    /** How many of the next levels {@code budget} pays for, bought together; 0 when not even one. */
    public int affordable(int owned, @NotNull BigDecimal budget) {
        BigDecimal first = price(owned);
        if (budget.compareTo(first) < 0) return 0;
        double estimate;
        if (growth.compareTo(BigDecimal.ONE) == 0) {
            estimate = Math.pow(10, log10(budget) - log10(first));
        } else {
            // budget ≥ first × (g^k − 1) / (g − 1)  ⇔  k ≤ log(1 + budget × (g − 1) / first) / log g
            BigDecimal ratio = budget.multiply(growth.subtract(BigDecimal.ONE, MC), MC).divide(first, MC);
            estimate = log10(ratio.add(BigDecimal.ONE, MC)) / log10(growth);
        }
        int count = (int) Math.max(1, Math.min(Integer.MAX_VALUE - (long) Math.max(0, owned), Math.floor(estimate)));
        // the estimate went through doubles: settle it exactly
        while (count > 1 && price(owned, count).compareTo(budget) > 0) count--;
        while (count < Integer.MAX_VALUE - Math.max(0, owned) && price(owned, count + 1).compareTo(budget) <= 0) count++;
        return count;
    }

    /** The levels {@code owned} are worth: in full up to the cap, then {@code (owned − cap)^falloff}. */
    public double levels(int owned) {
        if (owned <= 0) return 0;
        if (softCap <= 0 || owned <= softCap || falloff >= 1) return owned;
        return softCap + Math.pow(owned - softCap, falloff);
    }

    /** {@code log10} of a positive number of any size; a double would overflow past 1e308. */
    static double log10(@NotNull BigDecimal value) {
        if (value.signum() <= 0) return Double.NEGATIVE_INFINITY;
        int digits = value.precision() - value.scale();
        return digits + Math.log10(value.movePointLeft(digits).doubleValue());
    }
}
