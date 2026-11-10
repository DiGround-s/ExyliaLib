package net.exylia.lib.text;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.util.PeriodClock;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Countdowns and clocks written into a line of text, resolved against the
 * current time.
 *
 * <pre>{@code
 * TimeTokens.resolve("{letters}Opens in {countdown:2026-12-01 18:00}");
 * // "{letters}Opens in 20d 6h 26m 12s"
 * TimeTokens.resolve("{countdown:2026-12-01 18:00 Europe/Madrid|{success}Open now!}");
 * TimeTokens.resolve("{muted}{date:HH:mm} · {date:dd MMM yyyy}");
 * }</pre>
 *
 * <ul>
 *   <li>{@code {countdown:<when>}} is the time left until {@code when}, written
 *       {@code yyyy-MM-dd HH:mm:ss}, {@code yyyy-MM-dd HH:mm} or
 *       {@code yyyy-MM-dd}. It reads {@code 24d 6h 26m 12s}, leaving out the
 *       leading units that are zero ({@code 5m 0s}, {@code 12s}). Once
 *       {@code when} has passed it reads {@code 0s}, or the text after a
 *       {@code |}: {@code {countdown:2026-12-01|{success}Open now!}}.</li>
 *   <li>{@code {date:<pattern>}} is now, in a {@link DateTimeFormatter}
 *       pattern: {@code {date:HH:mm}}, {@code {date:dd MMM yyyy}}. Month and
 *       day names are English.</li>
 * </ul>
 *
 * <p>Both take a zone after a space, an IANA id such as {@code Europe/Madrid}
 * or {@code UTC}; without one the server's zone is used
 * ({@link PeriodClock#zone(String)}). A token that does not read, a misspelled
 * date or pattern, is left in the text as written, so the typo shows.
 *
 * <p>Tokens are resolved before the text parser runs, so the text after
 * {@code |} takes palette colours and tags like any other text. Safe from any
 * thread; what a token reads is parsed once and kept.
 *
 * @since 1.563.0
 */
public final class TimeTokens {

    private static final String COUNTDOWN = "{countdown:";
    private static final String DATE = "{date:";

    private static final DateTimeFormatter[] WHEN = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)};

    /** What each token body reads as, or empty when it does not read. */
    private static final Cache<String, Optional<Token>> READ = Caffeine.newBuilder().maximumSize(1024).build();

    private sealed interface Token {
    }

    private record Countdown(long at, @Nullable String after) implements Token {
    }

    private record Clock(DateTimeFormatter format) implements Token {
    }

    private TimeTokens() {
        throw new AssertionError("No instances.");
    }

    /**
     * Whether a text holds a time token, so a caller knows it changes with the
     * clock.
     *
     * @param text the text
     * @return whether it has a {@code {countdown:...}} or {@code {date:...}}
     */
    public static boolean contains(@Nullable String text) {
        return text != null && (text.contains(COUNTDOWN) || text.contains(DATE));
    }

    /**
     * The text with every time token resolved against now.
     *
     * @param text the text
     * @return the text with its tokens in place
     */
    public static @NotNull String resolve(@NotNull String text) {
        return resolve(text, System.currentTimeMillis());
    }

    /**
     * The text with every time token resolved against a moment.
     *
     * @param text the text
     * @param now  the moment, in epoch milliseconds
     * @return the text with its tokens in place
     */
    public static @NotNull String resolve(@NotNull String text, long now) {
        return resolve(text, now, UnaryOperator.identity());
    }

    /**
     * The text with every time token resolved against a moment, each token's
     * body passed through {@code body} first: a caller fills its own
     * placeholders in there, so {@code {countdown:%event_start%}} reads the
     * date the placeholder holds.
     *
     * @param text the text
     * @param now  the moment, in epoch milliseconds
     * @param body what to do to a token's body before it is read
     * @return the text with its tokens in place
     */
    public static @NotNull String resolve(@NotNull String text, long now, @NotNull UnaryOperator<String> body) {
        if (!contains(text)) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        while (at < text.length()) {
            int countdown = text.indexOf(COUNTDOWN, at);
            int date = text.indexOf(DATE, at);
            int open = countdown < 0 ? date : date < 0 ? countdown : Math.min(countdown, date);
            if (open < 0) {
                break;
            }
            boolean isCountdown = open == countdown;
            int start = open + (isCountdown ? COUNTDOWN.length() : DATE.length());
            int close = closing(text, start);
            if (close < 0) {
                break;
            }
            out.append(text, at, open);
            String inner = body.apply(text.substring(start, close));
            Optional<Token> token = READ.get((isCountdown ? "c" : "d") + inner,
                    key -> Optional.ofNullable(isCountdown ? countdown(inner) : clock(inner)));
            out.append(token.isEmpty() ? text.substring(open, close + 1) : render(token.get(), now));
            at = close + 1;
        }
        return out.append(text, at, text.length()).toString();
    }

    /**
     * A countdown as it reads: days, hours, minutes and seconds, leaving out
     * the leading units that are zero.
     *
     * @param seconds how long is left; anything below zero reads {@code 0s}
     * @return {@code 24d 6h 26m 12s}, {@code 5m 0s}, {@code 12s}
     */
    public static @NotNull String countdown(long seconds) {
        long left = Math.max(0, seconds);
        long[] units = {left / 86_400, left % 86_400 / 3_600, left % 3_600 / 60, left % 60};
        String[] names = {"d", "h", "m", "s"};
        StringBuilder out = new StringBuilder(16);
        for (int i = 0; i < units.length; i++) {
            if (!out.isEmpty() || units[i] > 0 || i == units.length - 1) {
                out.append(out.isEmpty() ? "" : " ").append(units[i]).append(names[i]);
            }
        }
        return out.toString();
    }

    private static String render(Token token, long now) {
        if (token instanceof Countdown countdown) {
            long left = countdown.at() - now;
            if (left <= 0 && countdown.after() != null) {
                return countdown.after();
            }
            // Rounded up, so it never reads 0s while the moment is still ahead.
            return countdown((left + 999) / 1000);
        }
        return ((Clock) token).format().format(Instant.ofEpochMilli(now));
    }

    /** Where the brace that closes a token starts at {@code from}, braces inside it counted. */
    private static int closing(String text, int from) {
        int depth = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        return -1;
    }

    private static @Nullable Countdown countdown(String inner) {
        int bar = inner.indexOf('|');
        String when = (bar < 0 ? inner : inner.substring(0, bar)).trim();
        String after = bar < 0 ? null : inner.substring(bar + 1);
        String[] zoned = zoned(when);
        ZoneId zone = PeriodClock.zone(zoned[1]);
        for (DateTimeFormatter format : WHEN) {
            try {
                return new Countdown(LocalDateTime.parse(zoned[0], format).atZone(zone).toInstant().toEpochMilli(),
                        after);
            } catch (DateTimeException ignored) {
                // Tries the next way of writing it.
            }
        }
        try {
            return new Countdown(LocalDate.parse(zoned[0]).atStartOfDay(zone).toInstant().toEpochMilli(), after);
        } catch (DateTimeException notADate) {
            return null;
        }
    }

    private static @Nullable Clock clock(String inner) {
        String[] zoned = zoned(inner.trim());
        if (zoned[0].isEmpty()) {
            return null;
        }
        try {
            return new Clock(DateTimeFormatter.ofPattern(zoned[0], Locale.ENGLISH)
                    .withZone(PeriodClock.zone(zoned[1])));
        } catch (IllegalArgumentException badPattern) {
            return null;
        }
    }

    /**
     * Splits a zone off the end: the last word when it is an IANA id
     * ({@code Europe/Madrid}) or {@code UTC}/{@code GMT} with or without an
     * offset. Anything else, a pattern letter such as {@code Z} included,
     * stays part of what comes before.
     *
     * @return what comes before, and the zone or {@code null}
     */
    private static String[] zoned(String text) {
        int space = text.lastIndexOf(' ');
        if (space > 0) {
            String last = text.substring(space + 1);
            String upper = last.toUpperCase(Locale.ROOT);
            if (last.indexOf('/') > 0 || upper.startsWith("UTC") || upper.startsWith("GMT")) {
                try {
                    ZoneId.of(last);
                    return new String[]{text.substring(0, space).trim(), last};
                } catch (DateTimeException notAZone) {
                    // Part of the date or pattern after all.
                }
            }
        }
        return new String[]{text, null};
    }
}
