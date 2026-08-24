package net.exylia.lib.placeholder.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits text into literals and placeholders, in one pass, without regex.
 *
 * <p>The syntax understood here is a superset of the usual {@code %name%}:
 *
 * <pre>
 *   %eco_balance%                 plain
 *   %clan_top_3%                  arguments, when "clan_top" is registered
 *   %eco_balance:comma%           a format
 *   %clan_name|No clan%           a fallback when there is no value
 *   %clan_top_3:comma|none%       all three
 * </pre>
 *
 * <p>Doing this without a regex matters because the alternative was matching
 * repeatedly over the same string until it stopped changing. This walks the
 * characters once, allocates only the parts it finds, and gives up immediately
 * on text with no {@code %} at all.
 *
 * <h2>Which name is the placeholder</h2>
 * A name may contain underscores, and so may its arguments, so
 * {@code %clan_top_3%} is ambiguous on its own. The longest registered prefix
 * wins: if {@code clan_top} is registered the rest becomes the argument
 * {@code 3}, and if {@code clan_top_3} is registered as a whole it is used as
 * such. That keeps both styles working without the plugin author declaring which
 * one they meant.
 */
public final class TemplateCompiler {

    private TemplateCompiler() {
    }

    /**
     * Compiles text into parts.
     *
     * @param text     the raw text
     * @param resolved decides whether a name is registered, used to split
     *                 arguments off the name
     * @return the parts, in order
     */
    public static List<Part> compile(String text, java.util.function.Predicate<String> resolved) {
        int first = text.indexOf('%');
        if (first < 0) {
            // No placeholder anywhere: one literal, no scanning.
            return List.of(Part.literal(text));
        }

        List<Part> parts = new ArrayList<>(8);
        int length = text.length();
        int literalStart = 0;
        int cursor = first;

        while (cursor < length) {
            if (text.charAt(cursor) != '%') {
                cursor++;
                continue;
            }

            int close = text.indexOf('%', cursor + 1);
            if (close < 0) {
                break;
            }

            if (close == cursor + 1) {
                // "%%" is an escaped percent sign.
                if (literalStart < cursor) {
                    parts.add(Part.literal(text.substring(literalStart, cursor)));
                }
                parts.add(Part.literal("%"));
                cursor = close + 1;
                literalStart = cursor;
                continue;
            }

            String body = text.substring(cursor + 1, close);
            Part placeholder = parse(body, text.substring(cursor, close + 1), resolved);
            if (placeholder == null) {
                // Not a usable placeholder; treat the opening % as plain text
                // and keep looking from the next character.
                cursor++;
                continue;
            }

            if (literalStart < cursor) {
                parts.add(Part.literal(text.substring(literalStart, cursor)));
            }
            parts.add(placeholder);
            cursor = close + 1;
            literalStart = cursor;
        }

        if (literalStart < length) {
            parts.add(Part.literal(text.substring(literalStart, length)));
        }

        return List.copyOf(parts);
    }

    /**
     * Parses the inside of a placeholder.
     *
     * @return the part, or {@code null} when the text between the percent signs
     *         cannot be a placeholder at all
     */
    private static Part parse(String body, String original, java.util.function.Predicate<String> resolved) {
        if (body.isEmpty()) {
            return null;
        }

        String remaining = body;
        String fallback = null;
        String format = null;

        // The fallback is human-written text, so it is taken off first and is
        // the one place a space is allowed.
        int pipe = remaining.indexOf('|');
        if (pipe >= 0) {
            fallback = remaining.substring(pipe + 1);
            remaining = remaining.substring(0, pipe);
        }

        int colon = remaining.indexOf(':');
        if (colon >= 0) {
            format = remaining.substring(colon + 1);
            remaining = remaining.substring(0, colon);
        }

        // A space in the name means this was prose that happened to contain two
        // percent signs, such as "50% of 20% is fine".
        if (remaining.isEmpty() || remaining.indexOf(' ') >= 0) {
            return null;
        }

        String lower = remaining.toLowerCase(java.util.Locale.ROOT);
        String name = lower;
        List<String> args = List.of();

        // Longest registered prefix wins, so both "clan_top_3" registered whole
        // and "clan_top" with an argument work without extra declarations.
        if (!resolved.test(lower)) {
            int split = lower.lastIndexOf('_');
            while (split > 0) {
                String candidate = lower.substring(0, split);
                if (resolved.test(candidate)) {
                    name = candidate;
                    // Cut from the text as written, not from the lower-cased
                    // copy. A name is a registry key and is matched folded; an
                    // argument is a value — an arena id, a world, somebody's
                    // name — and the thing it names knows its own capitals.
                    // Folded, %clan_players_CrystalHole% asked for an arena
                    // called "crystalhole", which does not exist, and the
                    // placeholder stayed on the screen.
                    args = splitArgs(remaining.substring(split + 1));
                    break;
                }
                split = lower.lastIndexOf('_', split - 1);
            }
        }

        return new Part(null, name, args, format, fallback, original);
    }

    /** Arguments are separated by underscores, as PlaceholderAPI users expect. */
    private static List<String> splitArgs(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        if (text.indexOf('_') < 0) {
            return List.of(text);
        }
        List<String> args = new ArrayList<>(4);
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '_') {
                args.add(text.substring(start, i));
                start = i + 1;
            }
        }
        args.add(text.substring(start));
        return List.copyOf(args);
    }
}
