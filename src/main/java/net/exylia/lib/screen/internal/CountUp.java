package net.exylia.lib.screen.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code count-up="8t"}: a number in a text that steps from its old value to its new one, written the way the new one
 * is ({@code 1.2K}, {@code 1,234}, {@code 12.5}). The steps are renders: the text asks for one every 2 ticks while it
 * counts. Since 1.671.0.
 *
 * <p>Internal: free to change without notice.
 */
public final class CountUp {

    private CountUp() {
    }

    /** A number in a text: where it is, its value, and how it is written. */
    record Token(int start, int end, double value, int decimals, String suffix, boolean grouped) {
    }

    /** A count in progress, per text, kept in the session. */
    static final class Count {
        double from;
        double to;
        long start;
        long length;
    }

    private static final Pattern NUMBER = Pattern.compile("-?(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d+))?([KMBTQ])?(?![\\w])");
    private static final Pattern MARKUP = Pattern.compile("<[^>]*>|\\{[^}]*}|[&§][0-9a-fk-orA-FK-OR]");
    private static final String SUFFIXES = "KMBTQ";

    /** The first number written in {@code text}, outside tags and colour codes, or {@code null}. */
    static Token find(String text) {
        // Tags and codes are blanked, so a hex colour is never read as a number.
        StringBuilder masked = new StringBuilder(text);
        Matcher tags = MARKUP.matcher(text);
        while (tags.find()) for (int i = tags.start(); i < tags.end(); i++) masked.setCharAt(i, ' ');
        Matcher m = NUMBER.matcher(masked);
        while (m.find()) {
            if (m.start() > 0 && Character.isLetterOrDigit(masked.charAt(m.start() - 1))) continue;
            String whole = m.group(1).replace(",", "");
            String fraction = m.group(2) == null ? "" : m.group(2);
            double value = Double.parseDouble(whole + (fraction.isEmpty() ? "" : "." + fraction));
            if (masked.charAt(m.start()) == '-') value = -value;
            String suffix = m.group(3) == null ? "" : m.group(3);
            if (!suffix.isEmpty()) value *= Math.pow(1000, SUFFIXES.indexOf(suffix) + 1);
            return new Token(m.start(), m.end(), value, fraction.length(), suffix, m.group(1).contains(","));
        }
        return null;
    }

    /** {@code value} written as {@code like} is: its suffix, its decimals, its grouping. */
    static String format(double value, Token like) {
        double shown = like.suffix().isEmpty() ? value : value / Math.pow(1000, SUFFIXES.indexOf(like.suffix()) + 1);
        // Cut, as compact numbers are, so a count never shows more than it reaches.
        BigDecimal number = BigDecimal.valueOf(shown).setScale(like.decimals(), RoundingMode.DOWN);
        String text;
        if (like.grouped()) {
            text = String.format(java.util.Locale.ROOT, "%,." + like.decimals() + "f", number);
        } else {
            text = number.toPlainString();
        }
        return text + like.suffix();
    }

    /** Ease out: fast first, settling on the value. */
    static double eased(double t) {
        double u = 1 - Math.max(0, Math.min(1, t));
        return 1 - u * u * u;
    }

    /**
     * What a counting text shows now, and whether it still counts.
     *
     * @param counts the session's counts by text key
     * @param key    the text's key: its id, its row's track, or where it is written
     * @param text   the text as it is now, number included
     * @param ticks  how long a count takes
     * @param now    the time in milliseconds
     * @param reduced whether the viewer wants reduced motion: the number jumps
     * @return the text to draw; {@code counting[0]} says whether to draw again in 2 ticks
     */
    static String step(Map<String, Count> counts, String key, String text, int ticks, long now, boolean reduced,
                       boolean[] counting) {
        counting[0] = false;
        Token token = find(text);
        if (token == null) return text;
        Count count = counts.get(key);
        if (count == null || reduced) {
            count = new Count();
            count.from = count.to = token.value();
            counts.put(key, count);
            return text;
        }
        double t = count.length <= 0 ? 1 : (now - count.start) / (double) count.length;
        double at = count.from + (count.to - count.from) * eased(t);
        if (token.value() != count.to) {
            // A new value while counting starts from where the count stands.
            count.from = t >= 1 ? count.to : at;
            count.to = token.value();
            count.start = now;
            count.length = ticks * 50L;
            t = 0;
            at = count.from;
        }
        if (t >= 1) return text;
        counting[0] = true;
        return text.substring(0, token.start()) + format(at, token) + text.substring(token.end());
    }

    /** A fresh store of counts for a session. */
    static Map<String, Count> store() {
        return new ConcurrentHashMap<>();
    }
}
