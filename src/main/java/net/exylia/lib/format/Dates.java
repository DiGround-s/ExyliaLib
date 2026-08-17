package net.exylia.lib.format;

import net.exylia.lib.util.TimeFormats;
import org.jetbrains.annotations.NotNull;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dates written the way a player should read them.
 *
 * <pre>{@code
 * Dates.formatMillis(stamp, Dates.Style.DATE);   // "17/08/2026"
 * Dates.formatMillis(stamp, Dates.Style.FULL);   // "Monday, 17 August 2026"
 * Dates.relativeMillis(stamp);                   // "3d ago"
 * Dates.relativeMillis(expiry);                  // "in 2h"
 * }</pre>
 *
 * <p>The absolute half answers "when did this happen"; the relative half
 * answers "how long ago", which is the one a menu actually asks. There are
 * fifteen call sites in the ecosystem rendering a mail's age, a posting's
 * expiry or a member's last login, and every one of them wants
 * {@code "3d ago"} rather than a timestamp the reader has to subtract from
 * today in their head.
 *
 * <h2>What this costs</h2>
 * These run inside placeholders, and a placeholder runs on every tick of every
 * scoreboard of every player. A {@link DateTimeFormatter} is immutable and
 * thread safe, so every style's formatter is built exactly once when this class
 * loads and shared by everything afterwards — that is the whole trick, and it
 * is why formatting a date here is an arithmetic conversion and a string build
 * rather than a pattern parse.
 *
 * <p>The pattern parse is not a rounding error: {@code DateTimeFormatter.ofPattern}
 * compiles a pattern string into a tree of printer objects, which is orders of
 * magnitude more expensive than the formatting it enables. ExyliaCommons paid
 * for a cache lookup on every call to avoid it; here there is nothing to look
 * up, because the formatter is a field on the enum constant the caller already
 * has in their hand.
 *
 * <p>The server's zone is read once for the same reason.
 * {@link ZoneId#systemDefault()} clones the JDK's default {@code TimeZone} on
 * every call, so calling it per render would allocate twice per date for a
 * value that does not change while a server is up.
 *
 * <h2>Timezone</h2>
 * The server's own zone, not UTC and not the player's. A date on a scoreboard
 * has to agree with the clock on the wall behind the person running the server,
 * because they are the one who reads {@code "17/08/2026 14:30"} and decides
 * whether that matches the incident in their log. A per-call overload takes an
 * explicit zone for the cases where it matters — a database record written by
 * another service, a scheduled event announced in a fixed zone.
 *
 * <h2>Units are in the method name</h2>
 * {@link #ofMillis} and {@link #ofSeconds}, never a single method that guesses.
 * ExyliaCommons decided by magnitude: a number above ten billion was
 * milliseconds, anything below was seconds. That works until the number is a
 * real millisecond timestamp from before 1970-04-26, which is under ten billion
 * — and then a date in April 1970 is silently read as one in 1970-01-01, and
 * the caller has no way to see it happening. There is no heuristic here at all:
 * the caller says which unit they hold, or the method does not exist.
 *
 * <h2>Locale</h2>
 * Fixed to {@link Locale#US}, exactly like {@link net.exylia.lib.format.Numbers}
 * and {@link TimeFormats}. A host in Spain would otherwise render
 * {@code "lunes, 17 agosto 2026"} from the same config that renders
 * {@code "Monday, 17 August 2026"} elsewhere, and neither is wrong from Java's
 * point of view — which is what makes it impossible for a config author to see
 * coming.
 *
 * <h2>What a nonsense timestamp renders as</h2>
 * Something, always. Nothing here throws, because every one of these is called
 * from inside a menu render or a scoreboard line, and a corrupt column must
 * cost a wrong-looking date rather than a screen that fails to draw.
 *
 * <p>A timestamp that is merely absurd is rendered honestly: a negative one is
 * a date before 1970, and {@code Long.MAX_VALUE} milliseconds is the year
 * {@code +292278994}, printed with the extra digits that make it obvious. That
 * is deliberately not tidied up into a friendlier value — a date in the year
 * two hundred and ninety-two million reads as broken data at a glance, which is
 * how it gets fixed, and any tidying would be the magnitude heuristic this
 * class exists to avoid.
 *
 * <p>{@code "unknown"} is only for what cannot be written at all: a moment past
 * the range {@link LocalDateTime} can express, which a second count large
 * enough to be clamped by {@link #ofSeconds} produces. An English word rather
 * than an empty string, because a lore line reading {@code "Last seen: "} looks
 * like the plugin forgot to fill it in, and somebody opens a bug report about
 * the placeholder instead of about the timestamp.
 *
 * <h2>Threading</h2>
 * Safe from any thread. Everything shared here is immutable.
 *
 * <h2>Reload</h2>
 * Nothing cached here derives from the colour palette, so there is no
 * {@code invalidateAll} to call: a formatter renders digits and month names,
 * and the module that colours them is {@code text}.
 *
 * @since 1.25.0
 */
public final class Dates {

    private Dates() {
        throw new AssertionError("No instances.");
    }

    /**
     * How a date should be written.
     *
     * <p>Named rather than spelled out as a pattern at every call site, because
     * {@code "dd/MM/yyyy"} and {@code "DD/MM/YYYY"} look the same to a reader
     * and only one of them is a date — {@code DD} is the day of the year and
     * {@code YYYY} is the week-based year, which is off by one for the last
     * days of December. Naming the styles means the ecosystem writes that
     * pattern once, here, and gets it right.
     *
     * <p>Every constant holds its formatter, built when this enum loads. It is
     * immutable and shared; nothing rebuilds it.
     */
    public enum Style {

        /** {@code "2026-08-17"} — sortable, for a log line or a file name. */
        ISO("yyyy-MM-dd"),

        /** {@code "2026-08-17 14:30:05"} — sortable, to the second. */
        ISO_TIME("yyyy-MM-dd HH:mm:ss"),

        /** {@code "17/08/2026"} — the day first, as the ecosystem writes it. */
        DATE("dd/MM/yyyy"),

        /** {@code "14:30"} — the time of day, on a twenty-four hour clock. */
        TIME("HH:mm"),

        /** {@code "14:30:05"} — the time of day, to the second. */
        TIME_SECONDS("HH:mm:ss"),

        /** {@code "17 Aug"} — for a column where the year is understood. */
        SHORT("dd MMM"),

        /** {@code "17 August 2026"} — for a line a player reads once. */
        LONG("dd MMMM yyyy"),

        /** {@code "Monday, 17 August 2026"} — with the weekday spelled out. */
        FULL("EEEE, dd MMMM yyyy");

        private final DateTimeFormatter formatter;

        Style(String pattern) {
            this.formatter = DateTimeFormatter.ofPattern(pattern, Locale.US);
        }

        /**
         * This style's formatter.
         *
         * <p>Immutable and shared. Handed out so a caller who formats in a loop
         * can hold it directly, not so it can be reconfigured — {@code withZone}
         * and friends return a copy and leave this one alone.
         *
         * @return the formatter
         */
        public @NotNull DateTimeFormatter formatter() {
            return formatter;
        }
    }

    /**
     * The server's zone, read once.
     *
     * <p>{@link ZoneId#systemDefault()} clones the JDK's default
     * {@code TimeZone} on every call. A server does not move house while it is
     * running, so paying that on every scoreboard line would be an allocation
     * for a value that cannot have changed.
     */
    private static final ZoneId SERVER_ZONE = ZoneId.systemDefault();

    /**
     * What a moment outside {@link LocalDateTime}'s range reads as.
     *
     * <p>Reached only when the moment genuinely cannot be written — see the
     * note on the class for why an absurd-but-writable date is printed rather
     * than hidden behind this.
     */
    private static final String UNKNOWN = "unknown";

    /** What a difference too small to be worth a number reads as. */
    private static final String JUST_NOW = "just now";

    /**
     * How close to now counts as now, in seconds.
     *
     * <p>Ten, not sixty. Sixty is the convention every web UI uses, and it is
     * wrong here: half these call sites render an expiry rather than an age,
     * and telling a player that a mail expiring in forty seconds expires
     * {@code "just now"} is a lie they find out about the hard way. Ten seconds
     * is short enough that both readings are true — something ten seconds old
     * and something ten seconds away are both, honestly, now — and long enough
     * that a menu drawn once does not sit there counting single seconds it
     * stopped being sure about the moment it was drawn.
     */
    private static final long JUST_NOW_SECONDS = 10L;

    /**
     * Formatters built from caller-supplied patterns.
     *
     * <p>Capped, because a pattern is normally a constant from a config and
     * there are a handful of them, but nothing stops a caller from building one
     * out of a placeholder value. Past the cap the formatter is still returned,
     * just not remembered: a slow render is a worse outcome than an unbounded
     * map only in theory, and a map that grows with player input is a leak.
     */
    private static final Map<String, DateTimeFormatter> PATTERNS = new ConcurrentHashMap<>();

    /** How many caller-supplied patterns are worth remembering. */
    private static final int PATTERN_LIMIT = 64;

    // ------------------------------------------------------------- units

    /**
     * Reads a timestamp in milliseconds since the epoch.
     *
     * <p>What {@code System.currentTimeMillis()}, {@code OfflinePlayer.getLastPlayed()}
     * and a JDBC {@code BIGINT} column all hold.
     *
     * <p>Every {@code long} is a valid instant in milliseconds, so this cannot
     * fail. What an absurd one prints is on the class documentation.
     *
     * @param epochMillis milliseconds since 1970-01-01T00:00:00Z
     * @return the instant
     */
    public static @NotNull Instant ofMillis(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis);
    }

    /**
     * Reads a timestamp in seconds since the epoch.
     *
     * <p>What a Unix tool, a REST API or a hand-written config holds.
     *
     * @param epochSeconds seconds since 1970-01-01T00:00:00Z
     * @return the instant
     */
    public static @NotNull Instant ofSeconds(long epochSeconds) {
        try {
            return Instant.ofEpochSecond(epochSeconds);
        } catch (DateTimeException outOfRange) {
            // A second count large enough to leave Instant's range is not a date
            // anybody meant. Clamping keeps the caller's render path free of a
            // try/catch it should not have to write.
            return epochSeconds < 0 ? Instant.MIN : Instant.MAX;
        }
    }

    // ---------------------------------------------------------- absolute

    /**
     * A date, in the server's zone.
     *
     * @param when  the moment
     * @param style how to write it
     * @return the text, or {@code "unknown"} when the moment is not a date
     */
    public static @NotNull String format(@NotNull Instant when, @NotNull Style style) {
        return format(when, style, SERVER_ZONE);
    }

    /**
     * A date, in a zone the caller names.
     *
     * <p>For the cases where the server's own clock is the wrong answer: a row
     * written by another service in UTC, or an event announced to a community
     * that reads it in one fixed zone regardless of where the machine sits.
     *
     * @param when  the moment
     * @param style how to write it
     * @param zone  the zone to read the moment in
     * @return the text, or {@code "unknown"} when the moment is not a date
     */
    public static @NotNull String format(@NotNull Instant when,
                                         @NotNull Style style,
                                         @NotNull ZoneId zone) {
        return render(when, zone, style.formatter);
    }

    /**
     * A date that already carries no zone.
     *
     * <p>For a {@code LocalDateTime} that came out of a {@code TIMESTAMP}
     * column, where the value is already whatever the writer meant it to be and
     * converting it again would move it.
     *
     * @param when  the date and time
     * @param style how to write it
     * @return the text, or {@code "unknown"} when it cannot be written
     */
    public static @NotNull String format(@NotNull LocalDateTime when, @NotNull Style style) {
        return render(when, style.formatter);
    }

    /**
     * A date from a millisecond timestamp, in the server's zone.
     *
     * @param epochMillis milliseconds since the epoch
     * @param style       how to write it
     * @return the text, or {@code "unknown"} when the timestamp is not a date
     */
    public static @NotNull String formatMillis(long epochMillis, @NotNull Style style) {
        return format(ofMillis(epochMillis), style);
    }

    /**
     * A date from a millisecond timestamp, in a zone the caller names.
     *
     * @param epochMillis milliseconds since the epoch
     * @param style       how to write it
     * @param zone        the zone to read the timestamp in
     * @return the text, or {@code "unknown"} when the timestamp is not a date
     */
    public static @NotNull String formatMillis(long epochMillis,
                                               @NotNull Style style,
                                               @NotNull ZoneId zone) {
        return format(ofMillis(epochMillis), style, zone);
    }

    /**
     * A date from a second timestamp, in the server's zone.
     *
     * @param epochSeconds seconds since the epoch
     * @param style        how to write it
     * @return the text, or {@code "unknown"} when the timestamp is not a date
     */
    public static @NotNull String formatSeconds(long epochSeconds, @NotNull Style style) {
        return format(ofSeconds(epochSeconds), style);
    }

    // ----------------------------------------------------------- pattern

    /**
     * A date in a pattern the caller supplies, in the server's zone.
     *
     * <p>The escape hatch, for the one config in the ecosystem that wants
     * something no style covers. Prefer a {@link Style}: a named style is a
     * decision made once and reviewed once, and a pattern string is a decision
     * made again in every file that copies it.
     *
     * @param when    the moment
     * @param pattern a {@link DateTimeFormatter} pattern
     * @return the text, or {@code "unknown"} when the moment is not a date
     */
    public static @NotNull String format(@NotNull Instant when, @NotNull String pattern) {
        return render(when, SERVER_ZONE, pattern(pattern));
    }

    /**
     * A date in a pattern the caller supplies, in a zone the caller names.
     *
     * @param when    the moment
     * @param pattern a {@link DateTimeFormatter} pattern
     * @param zone    the zone to read the moment in
     * @return the text, or {@code "unknown"} when the moment is not a date
     */
    public static @NotNull String format(@NotNull Instant when,
                                         @NotNull String pattern,
                                         @NotNull ZoneId zone) {
        return render(when, zone, pattern(pattern));
    }

    /**
     * A zoneless date in a pattern the caller supplies.
     *
     * @param when    the date and time
     * @param pattern a {@link DateTimeFormatter} pattern
     * @return the text, or {@code "unknown"} when it cannot be written
     */
    public static @NotNull String format(@NotNull LocalDateTime when, @NotNull String pattern) {
        return render(when, pattern(pattern));
    }

    /**
     * The formatter for a pattern, built once and remembered.
     *
     * <p>Public so a caller reading a config can resolve the pattern at load
     * and hold the formatter, which is the shape the rest of this class uses
     * internally and the only one with no lookup at all on the render path.
     *
     * <p>A pattern the JDK cannot parse falls back to {@link Style#ISO_TIME}
     * rather than throwing, and the fallback is remembered under the broken
     * pattern so a typo costs one parse rather than one per render. It is not
     * silent in any way that matters: every date in that menu comes out as an
     * ISO timestamp, which is exactly the sort of visible wrongness that gets a
     * config fixed. Throwing instead would take a menu down over a punctuation
     * mark.
     *
     * @param pattern a {@link DateTimeFormatter} pattern
     * @return the formatter, immutable and safe to share
     */
    public static @NotNull DateTimeFormatter pattern(@NotNull String pattern) {
        DateTimeFormatter known = PATTERNS.get(pattern);
        if (known != null) {
            return known;
        }
        DateTimeFormatter built;
        try {
            built = DateTimeFormatter.ofPattern(pattern, Locale.US);
        } catch (IllegalArgumentException unreadable) {
            built = Style.ISO_TIME.formatter;
        }
        if (PATTERNS.size() < PATTERN_LIMIT) {
            PATTERNS.putIfAbsent(pattern, built);
        }
        return built;
    }

    // ---------------------------------------------------------- relative

    /**
     * How long ago something happened, or how long until it does.
     *
     * <pre>{@code
     * Dates.relative(postedAt);   // "3d ago"
     * Dates.relative(expiresAt);  // "in 2h"
     * }</pre>
     *
     * <p>Past reads {@code "<time> ago"} and future reads {@code "in <time>"},
     * which is the shape ExyliaCommons produced and the shape the fifteen
     * menus already written against it expect.
     *
     * <p>The time itself comes from {@link TimeFormats}, so a duration renders
     * identically here, in a cooldown message and in a boss bar. There is one
     * duration formatter in this library on purpose: two of them is how a
     * server ends up showing {@code "3d"} on a scoreboard and {@code "72h"} in
     * the menu that explains it.
     *
     * <p>No zone is involved. The gap between two moments is the same length
     * whichever clock you read it on, so this is the one half of the class a
     * timezone cannot get wrong.
     *
     * @param when the moment
     * @return the text
     */
    public static @NotNull String relative(@NotNull Instant when) {
        return relative(Instant.now(), when);
    }

    /**
     * How one moment reads from another.
     *
     * <p>Read from {@code from}: a {@code to} that comes after it is
     * {@code "in <time>"}, and one that comes before is {@code "<time> ago"}.
     * {@link #relative(Instant)} is this with {@code from} set to now.
     *
     * <p>Two moments closer together than ten seconds are {@code "just now"} in
     * either direction — see the note on the threshold for why not a minute.
     *
     * @param from the moment to read from
     * @param to   the moment to read
     * @return the text
     */
    public static @NotNull String relative(@NotNull Instant from, @NotNull Instant to) {
        Duration between = Duration.between(from, to);
        // Not toMillis(): it overflows on a gap of more than about three hundred
        // thousand years, and a corrupt timestamp produces exactly that.
        double seconds = between.getSeconds() + between.getNano() / 1_000_000_000.0;
        double magnitude = Math.abs(seconds);
        if (magnitude < JUST_NOW_SECONDS) {
            return JUST_NOW;
        }
        String rendered = TimeFormats.render(magnitude, TimeFormats.Style.COMPACT);
        return seconds < 0 ? rendered + " ago" : "in " + rendered;
    }

    /**
     * How long ago a millisecond timestamp was, or how long until it is.
     *
     * <p>The one the menus call. Note that a zero here is a real date — the
     * first of January 1970, which renders as some fifty-odd years ago — and
     * not a missing value. Bukkit's {@code getLastPlayed()} returns zero for a
     * player who has never joined, and that is the caller's sentinel to check,
     * not this method's to guess: a formatter that decides some of its inputs
     * do not mean what they say is one a caller can no longer reason about.
     *
     * @param epochMillis milliseconds since the epoch
     * @return the text
     */
    public static @NotNull String relativeMillis(long epochMillis) {
        return relative(ofMillis(epochMillis));
    }

    /**
     * How long ago a second timestamp was, or how long until it is.
     *
     * @param epochSeconds seconds since the epoch
     * @return the text
     */
    public static @NotNull String relativeSeconds(long epochSeconds) {
        return relative(ofSeconds(epochSeconds));
    }

    // ----------------------------------------------------------- helpers

    /**
     * Formats an instant, or gives up in a way a menu can print.
     *
     * <p>The conversion is what throws: a timestamp far enough from the epoch
     * has no year a {@code LocalDateTime} can hold, and a column holding
     * uninitialised memory reaches that easily. Catching here rather than
     * validating up front keeps the cost on the path that already failed.
     */
    private static String render(Instant when, ZoneId zone, DateTimeFormatter formatter) {
        try {
            return LocalDateTime.ofInstant(when, zone).format(formatter);
        } catch (DateTimeException | ArithmeticException notADate) {
            return UNKNOWN;
        }
    }

    /** Formats a zoneless date, or gives up in a way a menu can print. */
    private static String render(LocalDateTime when, DateTimeFormatter formatter) {
        try {
            return when.format(formatter);
        } catch (DateTimeException | ArithmeticException notADate) {
            return UNKNOWN;
        }
    }
}
