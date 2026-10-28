package net.exylia.lib.util.upgrade;

import net.exylia.lib.util.Expressions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.ToIntFunction;

/**
 * Something a player raises one level at a time and pays for each level: more
 * luck, an extra slot, a kit that hits harder.
 *
 * <pre>{@code
 * Upgrade luck = new Upgrade("luck", 10, "250 * %level% ^ 1.5", "gems", "%level% * 2",
 *         Map.of("level", 5));
 *
 * luck.price(3);            // what level 3 costs, in gems
 * luck.effect(owned);       // the value the plugin applies at the level owned
 * luck.check(owned, have);  // MAXED, LOCKED (a requirement short) or READY with the price
 * }</pre>
 *
 * <p>Both formulas are ExyliaLib {@link Expressions} with {@code %level%} and
 * {@code %max%} filled in. In {@link #cost} {@code %level%} is the level being
 * bought (1 for the first); in {@link #effect} it is the level owned, 0
 * included, so a formula like {@code 5 + %level%} gives the base value too.
 *
 * <p>Requirements are minimums by name, such as {@code level: 10} or
 * {@code prestige: 1}. The names are the plugin's own: {@link #check} asks a
 * function for the player's value of each one, so the library never needs to
 * know what a prestige is.
 *
 * <p>Immutable and pure: safe from any thread, and nothing is evaluated until
 * asked. Buying, which charges the currency, is {@link Upgrades#buy}.
 *
 * @param id           what the plugin stores the level under
 * @param maxLevel     the highest level, at least 1
 * @param cost         the price of reaching {@code %level%}
 * @param currency     the {@link net.exylia.lib.economy.Economy} currency id the
 *                     price is charged in; blank is the server's default
 * @param effect       the value at {@code %level%}
 * @param requirements minimum values by name; empty for none
 * @since 1.381.0
 */
public record Upgrade(@NotNull String id, int maxLevel, @NotNull String cost, @NotNull String currency,
                      @NotNull String effect, @NotNull Map<String, Integer> requirements) {

    public Upgrade {
        id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        maxLevel = Math.max(1, maxLevel);
        cost = cost == null || cost.isBlank() ? "0" : cost;
        currency = currency == null ? "" : currency.trim();
        effect = effect == null || effect.isBlank() ? "%level%" : effect;
        requirements = requirements == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(requirements));
    }

    /** A config default: one level, free, worth its level. */
    public Upgrade() {
        this("upgrade", 1, "0", "", "%level%", Map.of());
    }

    /** What the outcome of looking at an upgrade is, before anything is charged. */
    public enum State {
        /** Already at the highest level. */
        MAXED,
        /** A requirement is short; {@link Check#requirement()} names it. */
        LOCKED,
        /** The next level can be bought at {@link Check#price()}. */
        READY
    }

    /**
     * Whether the next level can be bought.
     *
     * @param state       one of the three
     * @param next        the level a purchase would reach; {@code maxLevel} when maxed
     * @param price       what it costs; zero when maxed
     * @param requirement the first requirement short, or {@code null}
     * @param needed      that requirement's minimum, or 0
     */
    public record Check(@NotNull State state, int next, @NotNull BigDecimal price,
                        @Nullable String requirement, int needed) {
    }

    /**
     * The price of reaching a level, never negative, rounded to two decimals.
     * Zero outside {@code 1..maxLevel} and for an unreadable formula; see
     * {@link #problems()} to report one.
     */
    public @NotNull BigDecimal price(int level) {
        if (level < 1 || level > maxLevel) return BigDecimal.ZERO;
        double value = evaluate(cost, level).orElse(0);
        if (value <= 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    /** The value at a level, clamped into {@code 0..maxLevel}; 0 for an unreadable formula. */
    public double effect(int level) {
        return evaluate(effect, Math.max(0, Math.min(level, maxLevel))).orElse(0);
    }

    /** Whether a level is the highest one. */
    public boolean maxed(int level) {
        return level >= maxLevel;
    }

    /**
     * Whether the level after {@code owned} can be bought.
     *
     * @param owned    the level the player has now
     * @param progress the player's value of a requirement name; called once per requirement
     */
    public @NotNull Check check(int owned, @NotNull ToIntFunction<String> progress) {
        if (maxed(owned)) return new Check(State.MAXED, maxLevel, BigDecimal.ZERO, null, 0);
        int next = Math.max(1, owned + 1);
        for (Map.Entry<String, Integer> requirement : requirements.entrySet()) {
            int needed = requirement.getValue() == null ? 0 : requirement.getValue();
            if (progress.applyAsInt(requirement.getKey()) < needed) {
                return new Check(State.LOCKED, next, price(next), requirement.getKey(), needed);
            }
        }
        return new Check(State.READY, next, price(next), null, 0);
    }

    /**
     * What is wrong with this upgrade as written, for a plugin to log at load:
     * an unreadable cost or effect. Empty when it reads.
     */
    public @NotNull List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (evaluate(cost, 1).isEmpty()) problems.add("cost '" + cost + "' does not read as a formula");
        if (evaluate(effect, 0).isEmpty()) problems.add("effect '" + effect + "' does not read as a formula");
        return problems;
    }

    private OptionalDouble evaluate(String formula, int level) {
        return Expressions.tryEvaluate(formula.replace("%level%", String.valueOf(level))
                .replace("%max%", String.valueOf(maxLevel)));
    }
}
