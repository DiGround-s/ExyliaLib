package net.exylia.lib.format.internal;

import net.exylia.lib.format.Dates;
import net.exylia.lib.format.FormatSettings;
import net.exylia.lib.format.Numbers;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A {@link FormatSettings} record turned into the handful of values the format
 * methods actually read.
 *
 * <h2>Why this exists at all</h2>
 * Reading a field off the settings record is free, so this is not about the
 * reads. It is about everything the record does <em>not</em> hold: the suffix
 * array in the right case, the threshold as a {@link BigDecimal} money can be
 * compared against, the decimals already clamped to a range that cannot produce
 * nonsense. Deriving those per call would mean allocating an array and a
 * {@code BigDecimal} on a path that runs on every tick of every scoreboard line
 * of every player — four thousand calls a second on a twenty-player server with
 * a ten-line sidebar, which is the arithmetic the whole format module is
 * written against.
 *
 * <p>So they are derived once, when {@code formats.yml} is loaded or reloaded,
 * and this object is published whole. A format call is then a volatile read of
 * the current instance, a few field reads, and the string it returns.
 *
 * <h2>Why the values are clamped here</h2>
 * A server owner writing {@code decimals: 300} should get a slightly odd number,
 * not a {@link ArithmeticException} thrown from inside a menu render — and not
 * a server that refuses to start over a cosmetic setting. Clamping at apply time
 * means the check is paid once instead of per call, and every reader downstream
 * can assume the value is sane.
 *
 * <h2>Threading</h2>
 * Immutable, and every field it holds is immutable. Safe to publish and read
 * from any thread.
 */
public final class ActiveFormats {

    /** The most decimals worth honouring, past which the digits are noise. */
    private static final int MAX_MONEY_DECIMALS = 8;
    private static final int MAX_COMPACT_DECIMALS = 3;
    private static final int MAX_PERCENT_DECIMALS = 6;

    /** Suffixes and their divisors, largest first, as {@link Numbers} orders them. */
    private static final String[] UPPERCASE = {"Q", "T", "B", "M", "K"};
    private static final String[] LOWERCASE = {"q", "t", "b", "m", "k"};
    private static final long[] DIVISORS = {
            1_000_000_000_000_000L,
            1_000_000_000_000L,
            1_000_000_000L,
            1_000_000L,
            1_000L};

    /** The same divisors as {@link BigDecimal}, so money never touches a double. */
    private static final BigDecimal[] BIG_DIVISORS = {
            BigDecimal.valueOf(1_000_000_000_000_000L),
            BigDecimal.valueOf(1_000_000_000_000L),
            BigDecimal.valueOf(1_000_000_000L),
            BigDecimal.valueOf(1_000_000L),
            BigDecimal.valueOf(1_000L)};

    /**
     * What every method produces before {@code formats.yml} has been read.
     *
     * <p>Declared after the tables above, not beside them, because a static
     * field is initialised in source order: constructing this first would read
     * a null suffix array and hand every early caller a
     * {@link NullPointerException} from inside a formatter that looks correct.
     */
    public static final ActiveFormats DEFAULTS = new ActiveFormats(new FormatSettings());

    private final FormatSettings settings;

    // Money, resolved.
    private final String moneySymbol;
    private final boolean symbolBefore;
    private final int moneyDecimals;
    private final boolean moneyCompact;
    private final BigDecimal moneyThreshold;
    private final double moneyThresholdAsDouble;

    // Compact, resolved.
    private final String[] suffixes;
    private final int compactDecimals;
    private final long compactThreshold;

    // Percent, resolved.
    private final int percentDecimals;
    private final boolean percentPlus;

    // Date, resolved.
    private final Dates.Style dateStyle;

    /**
     * Resolves settings into the values the format methods read.
     *
     * @param settings the settings as loaded from {@code formats.yml}
     */
    public ActiveFormats(@NotNull FormatSettings settings) {
        this.settings = settings;

        FormatSettings.Money money = settings.money();
        // The space is part of the symbol from here on: joining it per call
        // would mean a second string build for a decision that cannot change
        // between calls.
        String symbol = money.symbol() == null ? "" : money.symbol();
        boolean before = money.symbolPosition() != FormatSettings.SymbolPosition.AFTER;
        if (money.spaceAfterSymbol() && !symbol.isEmpty()) {
            symbol = before ? symbol + " " : " " + symbol;
        }
        this.moneySymbol = symbol;
        this.symbolBefore = before;
        this.moneyDecimals = clamp(money.decimals(), 0, MAX_MONEY_DECIMALS);
        this.moneyCompact = money.compact();
        long threshold = Math.max(0L, money.compactThreshold());
        this.moneyThreshold = BigDecimal.valueOf(threshold);
        this.moneyThresholdAsDouble = threshold;

        FormatSettings.Compact compact = settings.compact();
        this.suffixes = compact.lowercaseSuffixes() ? LOWERCASE : UPPERCASE;
        this.compactDecimals = clamp(compact.decimals(), 0, MAX_COMPACT_DECIMALS);
        // Never below a thousand: there is no suffix for a hundred, so a lower
        // threshold would silently do nothing and leave an owner adjusting a
        // setting that has no effect.
        this.compactThreshold = Math.max(1_000L, compact.threshold());

        FormatSettings.Percent percent = settings.percent();
        this.percentDecimals = clamp(percent.decimals(), 0, MAX_PERCENT_DECIMALS);
        this.percentPlus = percent.showPlus();

        FormatSettings.Date date = settings.date();
        this.dateStyle = date.style() == null ? Dates.Style.DATE : date.style();
    }

    /**
     * The settings this was resolved from.
     *
     * @return the settings record
     */
    public @NotNull FormatSettings settings() {
        return settings;
    }

    // ------------------------------------------------------------- money

    /**
     * An amount of currency, without losing a cent on the way.
     *
     * <p>{@link BigDecimal} throughout. A balance is the one number a
     * {@code double} must not hold: {@code 0.1 + 0.2} is {@code 0.30000000000000004},
     * and a shop that adds three prices in a {@code double} shows a total a
     * player can prove wrong.
     *
     * @param amount the amount
     * @return the text
     */
    public @NotNull String money(@NotNull BigDecimal amount) {
        boolean negative = amount.signum() < 0;
        BigDecimal magnitude = negative ? amount.negate() : amount;

        String number;
        if (moneyCompact && magnitude.compareTo(moneyThreshold) >= 0) {
            number = compactBig(magnitude);
        } else {
            // setScale before rendering, so the rounding is decided on the exact
            // value rather than on whatever a binary approximation of it landed
            // near.
            BigDecimal scaled = magnitude.setScale(moneyDecimals, RoundingMode.HALF_UP);
            number = grouped(scaled.toPlainString());
        }
        return withSymbol(number, negative);
    }

    /**
     * An amount of currency held as a {@code double}.
     *
     * <p>Here because a caller reading an economy plugin's API has a
     * {@code double} whether they wanted one or not. The conversion goes through
     * {@link BigDecimal#valueOf(double)}, which reads the shortest decimal that
     * round-trips — so {@code 0.30000000000000004} becomes {@code 0.3} rather
     * than being rendered as the noise it is.
     *
     * @param amount the amount
     * @return the text
     */
    public @NotNull String money(double amount) {
        if (!Double.isFinite(amount)) {
            // A balance of NaN is a bug upstream, but a menu that shows "NaN"
            // is a support ticket. Zero is the honest, harmless reading.
            return withSymbol(zero(), false);
        }
        return money(BigDecimal.valueOf(amount));
    }

    /**
     * An amount of currency held as a whole number, the common case.
     *
     * @param amount the amount
     * @return the text
     */
    public @NotNull String money(long amount) {
        boolean negative = amount < 0;
        // The sign comes off through BigDecimal rather than Math.abs: the
        // absolute value of Long.MIN_VALUE is not a long, and a balance that
        // renders as a negative number after its minus sign was already printed
        // is the kind of bug nobody looks for.
        BigDecimal magnitude = BigDecimal.valueOf(amount);
        if (negative) {
            magnitude = magnitude.negate();
        }
        if (moneyCompact && magnitude.compareTo(moneyThreshold) >= 0) {
            return withSymbol(compactBig(magnitude), negative);
        }
        return withSymbol(
                grouped(magnitude.setScale(moneyDecimals, RoundingMode.UNNECESSARY).toPlainString()),
                negative);
    }

    // ----------------------------------------------------------- compact

    /**
     * A shortened number, as configured.
     *
     * @param value the number
     * @return the text
     */
    public @NotNull String compact(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        double magnitude = Math.abs(value);
        if (magnitude < compactThreshold) {
            return Numbers.grouped(value, 0);
        }
        for (int index = 0; index < DIVISORS.length; index++) {
            if (magnitude >= DIVISORS[index]) {
                return Numbers.trimmed(value / DIVISORS[index], compactDecimals) + suffixes[index];
            }
        }
        return Numbers.grouped(value, 0);
    }

    /**
     * A shortened number, as configured.
     *
     * @param value the number
     * @return the text
     */
    public @NotNull String compact(long value) {
        // Not Math.abs: its result for Long.MIN_VALUE is negative, which would
        // compare below every threshold and print the whole sixteen-digit number
        // on a scoreboard sized for four.
        double magnitude = Math.abs((double) value);
        if (magnitude < compactThreshold) {
            return Numbers.grouped(value);
        }
        for (int index = 0; index < DIVISORS.length; index++) {
            if (magnitude >= DIVISORS[index]) {
                return Numbers.trimmed((double) value / DIVISORS[index], compactDecimals) + suffixes[index];
            }
        }
        return Numbers.grouped(value);
    }

    // ----------------------------------------------------------- percent

    /**
     * A percentage, as configured.
     *
     * @param value the percentage, where {@code 75} means seventy-five percent
     * @return the text
     */
    public @NotNull String percent(double value) {
        if (!Double.isFinite(value)) {
            return percentPlus ? "+0%" : "0%";
        }
        String rendered = Numbers.trimmed(value, percentDecimals);
        // The sign is read off the rendered text rather than the input: a value
        // of 0.001 with no decimals renders as "0", and "+0%" beside a "0%"
        // elsewhere is a difference nobody can explain.
        boolean positive = !rendered.isEmpty() && rendered.charAt(0) != '-' && !isZero(rendered);
        return percentPlus && positive ? "+" + rendered + "%" : rendered + "%";
    }

    // -------------------------------------------------------------- date

    /**
     * A date in the configured style.
     *
     * @param epochMillis milliseconds since the epoch
     * @return the text
     */
    public @NotNull String date(long epochMillis) {
        return Dates.formatMillis(epochMillis, dateStyle);
    }

    /**
     * The configured date style, for a caller that formats something other than
     * a millisecond timestamp.
     *
     * @return the style
     */
    public @NotNull Dates.Style dateStyle() {
        return dateStyle;
    }

    // ----------------------------------------------------------- helpers

    /**
     * Shortens a non-negative amount without leaving {@link BigDecimal}.
     *
     * <p>Dividing through a {@code double} would be simpler and would also
     * throw away the precision this whole path exists to protect, at exactly
     * the magnitudes where a {@code double} starts losing whole units.
     */
    private String compactBig(BigDecimal magnitude) {
        for (int index = 0; index < BIG_DIVISORS.length; index++) {
            if (magnitude.compareTo(BIG_DIVISORS[index]) >= 0) {
                BigDecimal shortened = magnitude
                        .divide(BIG_DIVISORS[index], compactDecimals, RoundingMode.HALF_UP)
                        .stripTrailingZeros();
                return shortened.toPlainString() + suffixes[index];
            }
        }
        return grouped(magnitude.setScale(moneyDecimals, RoundingMode.HALF_UP).toPlainString());
    }

    /**
     * Puts the symbol and the sign where they belong.
     *
     * <p>The minus sign leads whatever the symbol's position is. {@code $-5.00}
     * and {@code -5.00 €} are both read at a glance; {@code $-5.00} written the
     * other way round, as {@code -$5.00}, is what every bank statement does and
     * therefore what a player expects.
     */
    private String withSymbol(String number, boolean negative) {
        StringBuilder result = new StringBuilder(
                number.length() + moneySymbol.length() + (negative ? 1 : 0));
        if (negative) {
            result.append('-');
        }
        if (symbolBefore) {
            result.append(moneySymbol).append(number);
        } else {
            result.append(number).append(moneySymbol);
        }
        return result.toString();
    }

    /** Zero at the configured scale, for the amounts that are not amounts. */
    private String zero() {
        return BigDecimal.ZERO.setScale(moneyDecimals, RoundingMode.UNNECESSARY).toPlainString();
    }

    /**
     * Groups the thousands of an already-rendered, non-negative plain number.
     *
     * <p>{@link Numbers#grouped(long)} cannot be used here: a balance can be
     * larger than a {@code long} holds, and the point of the {@link BigDecimal}
     * path is that nothing along it silently truncates.
     */
    private static String grouped(String plain) {
        int dot = plain.indexOf('.');
        String whole = dot < 0 ? plain : plain.substring(0, dot);
        if (whole.length() <= 3) {
            return plain;
        }
        StringBuilder result = new StringBuilder(plain.length() + whole.length() / 3);
        int leading = whole.length() % 3;
        if (leading > 0) {
            result.append(whole, 0, leading);
        }
        for (int index = leading; index < whole.length(); index += 3) {
            if (index > 0) {
                result.append(',');
            }
            result.append(whole, index, index + 3);
        }
        if (dot >= 0) {
            result.append(plain, dot, plain.length());
        }
        return result.toString();
    }

    /** Whether rendered text is a zero, however many decimals it carries. */
    private static boolean isZero(String rendered) {
        for (int index = 0; index < rendered.length(); index++) {
            char character = rendered.charAt(index);
            if (character != '0' && character != '.') {
                return false;
            }
        }
        return true;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }
}
