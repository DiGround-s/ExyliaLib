package net.exylia.lib.format;

import net.exylia.lib.format.internal.ActiveFormats;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;

/**
 * Numbers and dates written the way <em>this server</em> wants them.
 *
 * <pre>{@code
 * Formats.money(1250);        // "$1,250.00"
 * Formats.compact(1_500);     // "1.5K"
 * Formats.percent(75);        // "75%"
 * Formats.date(stamp);        // "17/08/2026"
 * Formats.relative(stamp);    // "3d ago"
 * }</pre>
 *
 * <h2>The difference from {@link Numbers}</h2>
 * {@link Numbers} and {@link Dates} are fixed: {@code Numbers.compact(1500)} is
 * {@code "1.5K"} on every server that will ever run it, which is what makes them
 * testable and what makes them safe to call from anywhere. This class is the
 * same operations read through {@code plugins/ExyliaLib/formats.yml}, so a
 * server owner who wants {@code 1,5 k€} gets it everywhere at once.
 *
 * <p>Use this for anything a player reads. Use {@link Numbers} directly only
 * when the format is part of the meaning rather than of the presentation — a
 * key in a map, a value written to a file, a number another program parses.
 *
 * <h2>Why this is the module and not each plugin</h2>
 * The alternative is the currency symbol written into every menu, every lore
 * line and every message of every plugin. Changing {@code $} to {@code €} is
 * then an edit of two thousand files, and the ones that get missed are the ones
 * nobody opens — which is where a wrong symbol survives for months.
 *
 * <h2>What a call costs</h2>
 * One volatile field read, a handful of field reads off an immutable holder, and
 * the string being returned. Nothing here parses the config, builds a formatter,
 * allocates an array or takes a lock, because these run inside placeholders and
 * a placeholder runs on every tick of every scoreboard line of every player —
 * four thousand calls a second on a twenty-player server with a ten-line
 * sidebar.
 *
 * <p>Measured in {@code FormatsBenchmark}, in nanoseconds per call:
 *
 * <table border="1">
 *   <caption>Cost of one call</caption>
 *   <tr><th>Call</th><th>ns</th></tr>
 *   <tr><td>{@link #compact(long)}</td><td>30</td></tr>
 *   <tr><td>{@link #percent(double)}</td><td>29</td></tr>
 *   <tr><td>{@link #money(long)}</td><td>69</td></tr>
 *   <tr><td>{@link #money(BigDecimal)}</td><td>70</td></tr>
 *   <tr><td>a {@code DecimalFormat} built per call, as the ecosystem does today</td><td>241</td></tr>
 * </table>
 *
 * <p>Four thousand compact calls a second is 0.12&nbsp;ms of a second of server
 * time — two thousandths of one tick's budget. Money costs more because it
 * refuses to leave {@link BigDecimal}, and that is the trade being bought: a
 * balance is worth forty nanoseconds more than a kill count.
 *
 * <p>Everything derived from the settings — the suffix table in the configured
 * case, the compact threshold as a {@link BigDecimal}, the decimal counts
 * already clamped — is computed once in {@link #apply(FormatSettings)} and
 * published as one immutable object. That is the whole design: the reload path
 * pays (36&nbsp;ns, twice in the life of a server), the render path does not.
 *
 * <h2>Money never goes through a double</h2>
 * {@link #money(BigDecimal)} keeps the value exact end to end. A balance is the
 * one number a {@code double} must not hold: adding {@code 0.1} to {@code 0.2}
 * in a {@code double} gives {@code 0.30000000000000004}, and a shop that sums
 * three prices that way shows a total a player can prove wrong. The
 * {@code double} and {@code long} overloads exist because an economy plugin's
 * API hands out a {@code double} whether the caller wanted one or not, and they
 * convert through {@link BigDecimal#valueOf(double)}, which reads the shortest
 * decimal that round-trips rather than the binary noise underneath it.
 *
 * <h2>Threading</h2>
 * Safe from any thread. The active settings are published through a volatile
 * field and every object reachable from it is immutable, so a reload cannot be
 * observed half applied.
 *
 * <h2>Placeholders</h2>
 * The same operations are available to any config file without writing Java:
 * {@code %exylia_money_1250%}, {@code %exylia_compact_1500%},
 * {@code %exylia_percent_75%}, {@code %exylia_ordinal_3%},
 * {@code %exylia_relative_1755400000000%}.
 *
 * @since 1.25.0
 */
public final class Formats {

    private Formats() {
        throw new AssertionError("No instances.");
    }

    /**
     * The settings in force, already resolved.
     *
     * <p>Volatile rather than synchronised: this is read on the hottest path in
     * the library and written twice in the life of a server. Publishing a whole
     * new immutable object rather than mutating fields is what makes a reload
     * atomic — a render either sees every old setting or every new one, never
     * the new symbol beside the old decimal count.
     *
     * <p>Starts at the defaults, so a plugin that formats something before
     * {@code formats.yml} is read gets the documented output rather than a
     * {@link NullPointerException}.
     */
    private static volatile ActiveFormats active = ActiveFormats.DEFAULTS;

    // ------------------------------------------------------------- money

    /**
     * An amount of currency, exact.
     *
     * <p>The overload to prefer. Everything about the amount survives to the
     * string: no rounding a {@code double} did on the way in, no digits
     * invented by a binary representation.
     *
     * @param amount the amount
     * @return the text, such as {@code "$1,250.00"}
     */
    public static @NotNull String money(@NotNull BigDecimal amount) {
        return active.money(amount);
    }

    /**
     * An amount of currency held as a {@code double}.
     *
     * <p>For the economy APIs that hand one out. A value that is not finite
     * renders as zero rather than as {@code "NaN"}: a menu showing {@code NaN}
     * is a support ticket, and the bug it points at is upstream of here.
     *
     * @param amount the amount
     * @return the text
     */
    public static @NotNull String money(double amount) {
        return active.money(amount);
    }

    /**
     * An amount of currency held as a whole number.
     *
     * <p>The cheapest overload, and the right one on a server whose balances
     * are whole coins.
     *
     * @param amount the amount
     * @return the text
     */
    public static @NotNull String money(long amount) {
        return active.money(amount);
    }

    // ----------------------------------------------------------- compact

    /**
     * A large number shortened, as this server writes it.
     *
     * <p>{@code 1500} is {@code "1.5K"} with the defaults. Below the configured
     * threshold the number is written out with its thousands grouped, so a
     * value a player compares against another one stays comparable.
     *
     * @param value the number
     * @return the text
     */
    public static @NotNull String compact(long value) {
        return active.compact(value);
    }

    /**
     * A large number shortened, as this server writes it.
     *
     * @param value the number
     * @return the text
     */
    public static @NotNull String compact(double value) {
        return active.compact(value);
    }

    // ----------------------------------------------------------- percent

    /**
     * A percentage, as this server writes it.
     *
     * <p>The value is already on the hundred scale: {@code 75} means seventy-five
     * percent. Named for the scale it takes because that is the mistake worth
     * preventing — a method that accepted both would render {@code 0.75} as
     * {@code "0.8%"} and nobody could tell from the call site which it meant.
     *
     * @param value the percentage, where {@code 75} means seventy-five percent
     * @return the text, such as {@code "75%"}
     */
    public static @NotNull String percent(double value) {
        return active.percent(value);
    }

    /**
     * A percentage from a part and a whole.
     *
     * <p>A whole of zero is zero percent rather than an error: a win rate with
     * no games played is nothing, not a division the caller has to guard.
     *
     * @param part  how many
     * @param whole out of how many
     * @return the text
     */
    public static @NotNull String percentOf(double part, double whole) {
        if (whole == 0 || !Double.isFinite(part) || !Double.isFinite(whole)) {
            return active.percent(0);
        }
        return active.percent(part / whole * 100.0);
    }

    // -------------------------------------------------------------- date

    /**
     * A date, in the style this server chose.
     *
     * <p>Rendered in the server machine's own timezone, so it agrees with the
     * clock behind whoever reads the log next to it.
     *
     * @param epochMillis milliseconds since the epoch
     * @return the text, such as {@code "17/08/2026"}
     */
    public static @NotNull String date(long epochMillis) {
        return active.date(epochMillis);
    }

    /**
     * How long ago something happened, or how long until it does.
     *
     * <p>{@code "3d ago"}, {@code "in 2h"}, {@code "just now"}. There is no
     * setting for this, and deliberately so: it is a duration and a direction,
     * and there is nothing about it for an owner to choose. The duration itself
     * comes from the library's single time formatter, so it reads the same here
     * as in a cooldown message.
     *
     * @param epochMillis milliseconds since the epoch
     * @return the text
     */
    public static @NotNull String relative(long epochMillis) {
        return Dates.relativeMillis(epochMillis);
    }

    // ------------------------------------------------------------ access

    /**
     * The settings currently in force.
     *
     * <p>For a plugin that needs a value rather than a rendered string — the
     * currency symbol for a sign, or the date style to format something this
     * class has no overload for. Do not cache the result: holding it is what
     * makes a reload invisible to your code.
     *
     * @return the active settings
     */
    public static @NotNull FormatSettings settings() {
        return active.settings();
    }

    /**
     * Applies settings, replacing the active ones.
     *
     * <p>Called by ExyliaLib when {@code formats.yml} is loaded or reloaded.
     * Consumers do not need to call this: editing that file is how a server
     * changes its formats, exactly as with the colour palette.
     *
     * <p>This is where every derived value is computed. It is the only method
     * here that allocates anything beyond its result, which is the point.
     *
     * @param settings the settings to apply
     */
    @ApiStatus.Internal
    public static void apply(@NotNull FormatSettings settings) {
        active = new ActiveFormats(settings);
    }

    /**
     * Puts the built-in defaults back.
     *
     * <p>For tests, and for a shutdown that must not leave one server's symbol
     * visible to the next thing that loads this class in the same JVM.
     */
    @ApiStatus.Internal
    public static void reset() {
        active = ActiveFormats.DEFAULTS;
    }
}
