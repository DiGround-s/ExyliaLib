package net.exylia.lib.format;

import net.exylia.lib.config.Comment;

/**
 * The named formats every Exylia plugin writes its numbers and dates through.
 *
 * <p>The same idea as the colour palette, applied to figures: a plugin says
 * "this is money" and the server owner decides once, in one file, what money
 * looks like everywhere. Generated as {@code plugins/ExyliaLib/formats.yml} on
 * first start.
 *
 * <h2>Why this exists</h2>
 * Without it, the currency symbol is written into every menu, lore line and
 * chat message of every plugin. Changing {@code $} to {@code €} then means
 * editing two thousand configuration files, and the ones that get missed are
 * the ones nobody opens often — which is exactly where a wrong symbol survives
 * for months.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Formats.money(balance);      // "$1,250.00"
 * Formats.compact(kills);      // "1.5K"
 * Formats.percent(winRate);    // "75%"
 * }</pre>
 *
 * <p>A value that makes no sense — three thousand decimals, a negative
 * threshold — is clamped when the file is applied rather than refused, and the
 * clamp happens once at load. A server that cannot start because somebody typed
 * an extra zero in a cosmetic setting is a worse outcome than a number with
 * eight decimals.
 *
 * @param money   how an amount of currency is written
 * @param compact how a large number is shortened
 * @param percent how a percentage is written
 * @param date    how a date is written
 * @since 1.25.0
 */
@Comment("Number and date formats shared by every Exylia plugin.")
@Comment("")
@Comment("Plugins never write a currency symbol or a date pattern themselves:")
@Comment("they say 'this is money' and this file decides what money looks like.")
@Comment("Change a value here and every menu, scoreboard and message follows.")
@Comment("")
@Comment("Run /exylialib reload after editing. No restart is needed.")
public record FormatSettings(

        @Comment("How an amount of currency is written.")
        @Comment("These defaults produce: $1,250.00 — and, past the")
        @Comment("threshold below, $2.5M")
        Money money,

        @Comment("How a large number is shortened.")
        @Comment("These defaults produce: 1.5K, 2.3M, 1.5B")
        @Comment("Used wherever a plugin asks for a shortened number, and by")
        @Comment("money when its own 'compact' is on.")
        Compact compact,

        @Comment("How a percentage is written.")
        @Comment("These defaults produce: 75% and 75.5%")
        @Comment("The number is already on the hundred scale: 75 means 75%.")
        Percent percent,

        @Comment("How a date is written.")
        @Comment("These defaults produce: 17/08/2026")
        @Comment("Relative text — '3d ago', 'in 2h' — has no setting: it is a")
        @Comment("duration and a direction, and there is nothing to choose.")
        Date date
) {

    /**
     * The Exylia defaults.
     *
     * <p>What a fresh {@code formats.yml} contains, and what every method of
     * {@link Formats} produces before the file is read — a plugin that formats
     * something during startup gets these rather than nothing.
     */
    public FormatSettings() {
        this(new Money(), new Compact(), new Percent(), new Date());
    }

    /**
     * How an amount of currency is written.
     *
     * @param symbol           the currency symbol
     * @param symbolPosition   which side of the number the symbol goes on
     * @param spaceAfterSymbol whether a space separates symbol and number
     * @param decimals         how many decimal places to show
     * @param compact          whether large amounts are shortened
     * @param compactThreshold the amount from which shortening starts
     * @since 1.25.0
     */
    public record Money(

            @Comment("The currency symbol. Any text, not only one character:")
            @Comment("'coins' and ' EUR' are as valid as '$'.")
            String symbol,

            @Comment("Which side of the number the symbol goes on.")
            @Comment("before gives $1,250.00 — after gives 1,250.00$")
            @Comment("A negative amount always keeps its minus sign in front of")
            @Comment("everything: -$5.00, never $-5.00.")
            SymbolPosition symbolPosition,

            @Comment("Whether a space separates the symbol from the number.")
            @Comment("true gives $ 1,250.00 or 1,250.00 €")
            @Comment("Set this rather than writing a space inside the symbol:")
            @Comment("YAML drops leading and trailing spaces unless the value is")
            @Comment("quoted, so a space typed there disappears without warning.")
            boolean spaceAfterSymbol,

            @Comment("How many decimal places to show. 0 to 8.")
            @Comment("2 is the usual choice for money. Use 0 on a server whose")
            @Comment("balances are always whole, to keep scoreboards short.")
            int decimals,

            @Comment("Whether large amounts are shortened to 2.5M instead of")
            @Comment("being written out as 2,500,000.00.")
            @Comment("The shortening itself follows the 'compact' section below,")
            @Comment("so both a balance and a kill count are shortened the same")
            @Comment("way. This setting only decides whether money does it.")
            boolean compact,

            @Comment("The amount from which shortening starts, when it is on.")
            @Comment("Below this the amount is written out in full, so a price")
            @Comment("a player has to approve stays exact: 999,999.00 rather")
            @Comment("than 1M, which would hide what they are about to pay.")
            long compactThreshold
    ) {

        /** The Exylia money format: {@code $1,250.00}. */
        public Money() {
            this("$", SymbolPosition.BEFORE, false, 2, true, 1_000_000L);
        }
    }

    /**
     * Which side of the number a currency symbol goes on.
     *
     * @since 1.25.0
     */
    public enum SymbolPosition {

        /** {@code $1,250.00} — the convention in English and most of Latin America. */
        BEFORE,

        /** {@code 1,250.00 €} — the convention in most of Europe. */
        AFTER
    }

    /**
     * How a large number is shortened.
     *
     * @param decimals         how many decimal places a shortened number keeps
     * @param lowercaseSuffixes whether the suffix is written {@code k} or {@code K}
     * @param threshold        the value from which shortening starts
     * @since 1.25.0
     */
    public record Compact(

            @Comment("How many decimal places a shortened number keeps. 0 to 3.")
            @Comment("1 gives 1.5K. 0 gives 1K, which loses the difference")
            @Comment("between 1,000 and 1,499 — fine for decoration, wrong for")
            @Comment("anything a player compares against another number.")
            @Comment("Trailing zeros are dropped either way: 2000 is 2K, never 2.0K.")
            int decimals,

            @Comment("Whether the suffix is written k, m, b or K, M, B.")
            @Comment("Uppercase is what the ecosystem's menus already use.")
            boolean lowercaseSuffixes,

            @Comment("The value from which shortening starts.")
            @Comment("Below it the number is written out with thousands grouped:")
            @Comment("with the default of 1000, 999 reads 999 and 12345 reads")
            @Comment("12.3K. Raise it to 100000 and 12345 reads 12,345 instead,")
            @Comment("for a scoreboard with room for the exact figure.")
            long threshold
    ) {

        /** The Exylia compact format: {@code 1.5K}, {@code 2.3M}. */
        public Compact() {
            this(1, false, 1_000L);
        }
    }

    /**
     * How a percentage is written.
     *
     * @param decimals how many decimal places to show at most
     * @param showPlus whether a positive value is prefixed with {@code +}
     * @since 1.25.0
     */
    public record Percent(

            @Comment("How many decimal places to show at most. 0 to 6.")
            @Comment("Decimals that say nothing are dropped, so 75 stays 75%")
            @Comment("and only 75.5 becomes 75.5%.")
            int decimals,

            @Comment("Whether a positive value is prefixed with a plus sign.")
            @Comment("true gives +12.5% and -3% — for a change since yesterday,")
            @Comment("where '12.5%' alone does not say which way it went.")
            @Comment("Leave it false for a plain figure such as a win rate.")
            boolean showPlus
    ) {

        /** The Exylia percent format: {@code 75%}, {@code 75.5%}. */
        public Percent() {
            this(1, false);
        }
    }

    /**
     * How a date is written.
     *
     * @param style which named style {@link Dates} renders with
     * @since 1.25.0
     */
    public record Date(

            @Comment("Which named style to use. One of:")
            @Comment("  iso           2026-08-17")
            @Comment("  iso_time      2026-08-17 14:30:05")
            @Comment("  date          17/08/2026")
            @Comment("  time          14:30")
            @Comment("  time_seconds  14:30:05")
            @Comment("  short         17 Aug")
            @Comment("  long          17 August 2026")
            @Comment("  full          Monday, 17 August 2026")
            @Comment("Dates are rendered in the server machine's own timezone,")
            @Comment("so they agree with the clock behind whoever reads the log.")
            Dates.Style style
    ) {

        /** The Exylia date format: {@code 17/08/2026}. */
        public Date() {
            this(Dates.Style.DATE);
        }
    }
}
