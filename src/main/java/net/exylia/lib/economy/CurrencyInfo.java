package net.exylia.lib.economy;

import net.exylia.lib.format.Formats;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * How a currency presents itself: what it is called, what it looks like and
 * how an amount of it is written.
 *
 * <p>Every provider answers with one, and {@code currencies.yml} may overlay
 * any part of it — a server that wants Vault's money called "Coins" with a
 * gold ingot as its icon says so there, and every plugin drawing a price
 * follows, because none of them ever formats a number by hand.
 *
 * <h2>Formats</h2>
 * A format is a line with three placeholders: {@code %amount%} (the number,
 * grouped and scaled to {@link #decimals()}), {@code %symbol%} and
 * {@code %name%} (singular or plural, by the amount). The default
 * {@code "%symbol%%amount%"} writes {@code $1,250.00}; a token currency might
 * prefer {@code "%amount% %name%"} for {@code 3 Tokens}.
 *
 * @param id           the provider's id
 * @param name         one unit, such as {@code "Coin"}
 * @param namePlural   several, such as {@code "Coins"}
 * @param symbol       what stands beside an amount, such as {@code "$"}; may be empty
 * @param icon         the item a menu draws it as, in item-source notation:
 *                     a material name or a {@code basehead-...} texture
 * @param decimals     how many decimal places an amount carries; {@code 0}
 *                     for a whole-number currency
 * @param format       how an amount is written in full
 * @param compactFormat how an amount is written short, {@code %amount%} being
 *                     compacted ({@code 1.2k}) rather than grouped
 * @since 1.150.0
 */
public record CurrencyInfo(
        @NotNull String id,
        @NotNull String name,
        @NotNull String namePlural,
        @NotNull String symbol,
        @NotNull String icon,
        int decimals,
        @NotNull String format,
        @NotNull String compactFormat) {

    /** The format a currency has unless it says otherwise. */
    public static final String DEFAULT_FORMAT = "%symbol%%amount%";

    public CurrencyInfo {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A currency needs an id.");
        }
        id = id.toLowerCase(Locale.ROOT);
        name = blankTo(name, capitalised(id));
        namePlural = blankTo(namePlural, name.endsWith("s") ? name : name + "s");
        symbol = symbol == null ? "" : symbol;
        icon = blankTo(icon, "GOLD_NUGGET");
        // -1 is "unspecified", which only an overlay says; see overlaid().
        decimals = Math.max(-1, Math.min(8, decimals));
        format = blankTo(format, DEFAULT_FORMAT);
        compactFormat = blankTo(compactFormat, format);
    }

    /**
     * The plainest description of a currency: an id, a name and a symbol.
     *
     * @param id     the provider's id
     * @param name   one unit
     * @param plural several
     * @param symbol the symbol, or empty
     */
    public static @NotNull CurrencyInfo of(@NotNull String id, @NotNull String name,
                                           @NotNull String plural, @NotNull String symbol) {
        return new CurrencyInfo(id, name, plural, symbol, "", 2, DEFAULT_FORMAT, DEFAULT_FORMAT);
    }

    /** Whether amounts of this currency are whole numbers. */
    public boolean isInteger() {
        return decimals == 0;
    }

    /** The decimal places an amount is written with; two when unspecified. */
    public int scaleDigits() {
        return decimals < 0 ? 2 : decimals;
    }

    /**
     * An amount, written the way this currency writes it.
     *
     * @param amount the amount
     * @return the text, such as {@code $1,250.00} or {@code 3 Tokens}
     */
    public @NotNull String format(@NotNull BigDecimal amount) {
        BigDecimal scaled = scale(amount);
        return signed(scaled, fill(format, grouped(scaled.abs()), scaled));
    }

    /**
     * An amount, written short: {@code $1.2k} rather than {@code $1,250.00}.
     *
     * @param amount the amount
     * @return the text
     */
    public @NotNull String formatCompact(@NotNull BigDecimal amount) {
        BigDecimal scaled = scale(amount);
        String number = scaled.abs().compareTo(BigDecimal.valueOf(1000)) >= 0
                ? Formats.compact(scaled.abs().doubleValue())
                : grouped(scaled.abs());
        return signed(scaled, fill(compactFormat, number, scaled));
    }

    /**
     * An amount rounded to what this currency can hold.
     *
     * <p>Down, never to the nearest: a currency with no decimals asked to
     * hold {@code 2.9} holds {@code 2}. Rounding up would be money nobody
     * paid, and a shop that rounds a price up on every sale is a shop that
     * steals.
     */
    public @NotNull BigDecimal scale(@NotNull BigDecimal amount) {
        return amount.setScale(scaleDigits(), RoundingMode.DOWN);
    }

    /** The name for an amount: singular for exactly one, plural otherwise. */
    public @NotNull String nameFor(@NotNull BigDecimal amount) {
        return amount.compareTo(BigDecimal.ONE) == 0 ? name : namePlural;
    }

    /**
     * This description with another laid over it.
     *
     * <p>Every blank in the overlay keeps this one's value, so a file that only
     * renames a currency does not lose its symbol.
     *
     * @param over the overlay; {@code null} for none
     * @return the combined description
     */
    public @NotNull CurrencyInfo overlaid(@Nullable CurrencyInfo over) {
        if (over == null) return this;
        return new CurrencyInfo(id,
                blankTo(over.name, name),
                blankTo(over.namePlural, namePlural),
                over.symbol.isEmpty() ? symbol : over.symbol,
                blankTo(over.icon, icon),
                over.decimals >= 0 ? over.decimals : decimals,
                blankTo(over.format, format),
                blankTo(over.compactFormat, compactFormat));
    }

    private String fill(String template, String number, BigDecimal amount) {
        return template
                .replace("%amount%", number)
                .replace("%symbol%", symbol)
                .replace("%name%", nameFor(amount))
                .replace("%id%", id);
    }

    /** The sign in front of the whole thing: {@code -$3.00}, never {@code $-3.00}. */
    private static String signed(BigDecimal amount, String text) {
        return amount.signum() < 0 ? "-" + text : text;
    }

    /** {@code 1234567.5} as {@code 1,234,567.50}. */
    private String grouped(BigDecimal scaled) {
        String plain = scaled.abs().toPlainString();
        int dot = plain.indexOf('.');
        String whole = dot < 0 ? plain : plain.substring(0, dot);
        String fraction = dot < 0 ? "" : plain.substring(dot);
        StringBuilder out = new StringBuilder(plain.length() + plain.length() / 3);
        for (int index = 0; index < whole.length(); index++) {
            if (index > 0 && (whole.length() - index) % 3 == 0) out.append(',');
            out.append(whole.charAt(index));
        }
        return out + fraction;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String capitalised(String id) {
        String clean = id.replace('_', ' ').replace('-', ' ');
        return Character.toUpperCase(clean.charAt(0)) + clean.substring(1);
    }
}
