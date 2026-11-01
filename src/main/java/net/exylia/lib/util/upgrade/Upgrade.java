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
import java.util.function.Predicate;
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
 * <p><b>Levels written one by one</b> (since 1.432.0). {@code levels} overrides
 * the track for a single level, keyed by its number: its own cost, currency,
 * permission, extra requirements, and named values such as
 * {@code radius-bonus} or {@code max-members}. Whatever a level leaves blank is
 * the track's. A track can be written as formulas alone, as a list of levels
 * alone (its highest key raises {@code maxLevel}), or both:
 *
 * <pre>{@code
 * speed:
 *   max-level: 5
 *   cost: '1000 * %level%'
 *   currency: coins
 *   permission: ''
 *   levels:
 *     '5': { cost: '64', currency: diamonds, permission: 'myplugin.speed.max' }
 * }</pre>
 *
 * <p>Items are a currency like any other: register an
 * {@link net.exylia.lib.economy.ItemCurrency} and name its id.
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
 * @param permission   the node needed to buy any level; blank for none (since 1.432.0)
 * @param levels       per-level overrides keyed by level number; empty for none (since 1.432.0)
 * @since 1.381.0
 */
public record Upgrade(@NotNull String id, int maxLevel, @NotNull String cost, @NotNull String currency,
                      @NotNull String effect, @NotNull Map<String, Integer> requirements,
                      @NotNull String permission, @NotNull Map<String, Level> levels) {

    /** The requirement name {@link Check#requirement()} carries when a permission is what is missing. */
    public static final String PERMISSION = "permission";

    public Upgrade {
        id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        cost = cost == null || cost.isBlank() ? "0" : cost;
        currency = currency == null ? "" : currency.trim();
        effect = effect == null || effect.isBlank() ? "%level%" : effect;
        requirements = requirements == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(requirements));
        permission = permission == null ? "" : permission.trim();
        Map<String, Level> written = new LinkedHashMap<>();
        if (levels != null) {
            for (Map.Entry<String, Level> entry : levels.entrySet()) {
                int number = number(entry.getKey());
                if (number < 1 || entry.getValue() == null) continue;
                written.put(String.valueOf(number), entry.getValue());
                maxLevel = Math.max(maxLevel, number);
            }
        }
        levels = Collections.unmodifiableMap(written);
        maxLevel = Math.max(1, maxLevel);
    }

    /** A track of formulas with no permission and no per-level overrides. */
    public Upgrade(@NotNull String id, int maxLevel, @NotNull String cost, @NotNull String currency,
                   @NotNull String effect, @NotNull Map<String, Integer> requirements) {
        this(id, maxLevel, cost, currency, effect, requirements, "", Map.of());
    }

    /** A config default: one level, free, worth its level. */
    public Upgrade() {
        this("upgrade", 1, "0", "", "%level%", Map.of());
    }

    /**
     * One level written on its own. Blank text, an empty map and a missing
     * value all fall back to the track.
     *
     * @param cost         this level's price formula; blank for the track's
     * @param currency     the currency it is charged in; blank for the track's
     * @param permission   the node needed to buy it; blank for the track's
     * @param requirements minimums added to the track's for this level
     * @param values       named values at this level, read with {@link Upgrade#value}
     * @since 1.432.0
     */
    public record Level(@NotNull String cost, @NotNull String currency, @NotNull String permission,
                        @NotNull Map<String, Integer> requirements, @NotNull Map<String, Double> values) {

        public Level {
            cost = cost == null ? "" : cost.trim();
            currency = currency == null ? "" : currency.trim();
            permission = permission == null ? "" : permission.trim();
            requirements = requirements == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(requirements));
            values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        /** A config default: everything inherited. */
        public Level() {
            this("", "", "", Map.of(), Map.of());
        }
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
        Level written = level(level);
        double value = evaluate(written == null || written.cost().isEmpty() ? cost : written.cost(), level).orElse(0);
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
        return check(owned, progress, node -> true);
    }

    /**
     * Whether the level after {@code owned} can be bought, the permission
     * included. A missing permission is {@link State#LOCKED} with
     * {@link #PERMISSION} as the requirement, checked after the minimums.
     *
     * <pre>{@code
     * Upgrade.Check check = speed.check(level, name -> 0, player::hasPermission);
     * }</pre>
     *
     * @param owned     the level the player has now
     * @param progress  the player's value of a requirement name
     * @param permitted whether the player holds a node; on the player's thread for a live player
     * @since 1.432.0
     */
    public @NotNull Check check(int owned, @NotNull ToIntFunction<String> progress, @NotNull Predicate<String> permitted) {
        if (maxed(owned)) return new Check(State.MAXED, maxLevel, BigDecimal.ZERO, null, 0);
        int next = Math.max(1, owned + 1);
        for (Map.Entry<String, Integer> requirement : requirements(next).entrySet()) {
            int needed = requirement.getValue() == null ? 0 : requirement.getValue();
            if (progress.applyAsInt(requirement.getKey()) < needed) {
                return new Check(State.LOCKED, next, price(next), requirement.getKey(), needed);
            }
        }
        String node = permission(next);
        if (!node.isEmpty() && !permitted.test(node)) {
            return new Check(State.LOCKED, next, price(next), PERMISSION, 0);
        }
        return new Check(State.READY, next, price(next), null, 0);
    }

    /** The level written on its own, or {@code null} when it only follows the formulas. @since 1.432.0 */
    public @Nullable Level level(int level) {
        return levels.get(String.valueOf(level));
    }

    /** The currency a level is charged in: its own, else the track's. @since 1.432.0 */
    public @NotNull String currency(int level) {
        Level written = level(level);
        return written == null || written.currency().isEmpty() ? currency : written.currency();
    }

    /** The node needed to buy a level: its own, else the track's; blank for none. @since 1.432.0 */
    public @NotNull String permission(int level) {
        Level written = level(level);
        return written == null || written.permission().isEmpty() ? permission : written.permission();
    }

    /** The track's minimums with a level's own added on top, the level winning a shared name. @since 1.432.0 */
    public @NotNull Map<String, Integer> requirements(int level) {
        Level written = level(level);
        if (written == null || written.requirements().isEmpty()) return requirements;
        Map<String, Integer> merged = new LinkedHashMap<>(requirements);
        merged.putAll(written.requirements());
        return merged;
    }

    /**
     * A named value at the level owned: the one written at that level, or at
     * the closest level below that writes it, or {@code fallback} before any.
     * A protection at level 3 whose levels 1 and 2 write {@code radius-bonus}
     * reads level 2's.
     *
     * @since 1.432.0
     */
    public double value(int level, @NotNull String name, double fallback) {
        for (int at = Math.min(level, maxLevel); at > 0; at--) {
            Level written = level(at);
            if (written != null) {
                Double value = written.values().get(name);
                if (value != null) return value;
            }
        }
        return fallback;
    }

    /**
     * What is wrong with this upgrade as written, for a plugin to log at load:
     * an unreadable cost or effect. Empty when it reads.
     */
    public @NotNull List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (evaluate(cost, 1).isEmpty()) problems.add("cost '" + cost + "' does not read as a formula");
        for (Map.Entry<String, Level> written : levels.entrySet()) {
            String own = written.getValue().cost();
            if (!own.isEmpty() && evaluate(own, number(written.getKey())).isEmpty()) {
                problems.add("level " + written.getKey() + " cost '" + own + "' does not read as a formula");
            }
        }
        if (evaluate(effect, 0).isEmpty()) problems.add("effect '" + effect + "' does not read as a formula");
        return problems;
    }

    private static int number(String key) {
        try {
            return key == null ? -1 : Integer.parseInt(key.trim());
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    private OptionalDouble evaluate(String formula, int level) {
        return Expressions.tryEvaluate(formula.replace("%level%", String.valueOf(level))
                .replace("%max%", String.valueOf(maxLevel)));
    }
}
