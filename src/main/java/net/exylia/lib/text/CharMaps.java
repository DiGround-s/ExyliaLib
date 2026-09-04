package net.exylia.lib.text;

import org.jetbrains.annotations.NotNull;

import java.util.function.IntUnaryOperator;

/**
 * Rewrites the characters a player reads and leaves every instruction alone.
 *
 * <pre>{@code
 * // a "font": letters swapped for look-alike glyphs
 * String fraktur = CharMaps.transform("{primary}Hello <bold>%player_name%</bold>",
 *         codePoint -> FRAKTUR.getOrDefault(codePoint, codePoint));
 * // -> "{primary}ℌ𝔢𝔩𝔩𝔬 <bold>%player_name%</bold>"
 * }</pre>
 *
 * <h2>What survives untouched</h2>
 * The map runs over a raw line, before it is parsed, so everything that is an
 * instruction rather than text is copied through exactly:
 * <ul>
 *   <li>a MiniMessage tag, {@code <gradient:#a:#b>} — rewritten letters make a
 *       tag nobody can parse;</li>
 *   <li>a palette token, {@code {primary}}, and any other brace a plugin owns;</li>
 *   <li>a percent placeholder, {@code %eco_balance:comma%} — its name is
 *       matched literally when its value is substituted, so a rewritten name
 *       silently stops matching and reaches chat as written;</li>
 *   <li>a legacy code, {@code &l} or {@code &#8a51c4} — {@code l} is a letter
 *       and would otherwise become {@code &ʟ}, which is no longer bold.</li>
 * </ul>
 * An unclosed {@code <}, an unclosed brace or a lone {@code %} is text, which
 * is also how MiniMessage reads it.
 *
 * <h2>Code points, not chars</h2>
 * The map is asked per code point and may answer with any code point,
 * including one outside the basic plane: the fraktur, double-struck and
 * monospace alphabets all live there. Returning the same code point means
 * "leave it". A line where nothing changes is returned as the same instance.
 *
 * <p>This is the transform behind the library's own small capitals; a plugin
 * that ships player-selectable fonts uses the same one with its own table.
 *
 * @since 1.102.0
 */
public final class CharMaps {

    private CharMaps() {
        throw new AssertionError("No instances.");
    }

    /**
     * Rewrites the visible characters of a line.
     *
     * @param text the raw line, in any notation the text module accepts
     * @param map  the replacement for a code point, or the same code point to
     *             keep it
     * @return the rewritten line, or {@code text} itself when nothing changed
     */
    public static @NotNull String transform(@NotNull String text, @NotNull IntUnaryOperator map) {
        int length = text.length();
        StringBuilder result = null;

        for (int index = 0; index < length; ) {
            char current = text.charAt(index);

            int skipTo = endOfInstruction(text, index, current);
            if (skipTo > index) {
                if (result != null) {
                    result.append(text, index, skipTo + 1);
                }
                index = skipTo + 1;
                continue;
            }

            int codePoint = text.codePointAt(index);
            int width = Character.charCount(codePoint);
            int replacement = map.applyAsInt(codePoint);
            if (replacement == codePoint) {
                if (result != null) {
                    result.appendCodePoint(codePoint);
                }
            } else {
                if (result == null) {
                    result = new StringBuilder(length + 16);
                    result.append(text, 0, index);
                }
                result.appendCodePoint(replacement);
            }
            index += width;
        }

        return result == null ? text : result.toString();
    }

    /**
     * Returns the index of the last character of the instruction starting at
     * {@code index}, or {@code index} itself when there is no instruction
     * there.
     */
    static int endOfInstruction(String text, int index, char current) {
        switch (current) {
            case '<' -> {
                int close = text.indexOf('>', index + 1);
                return close == -1 ? index : close;
            }
            case '{' -> {
                int close = text.indexOf('}', index + 1);
                return close == -1 ? index : close;
            }
            case '%' -> {
                // Only a well-formed %name% is a placeholder. A lone percent
                // is a character in a sentence, and the rest of the line must
                // not be swallowed by it.
                int close = text.indexOf('%', index + 1);
                return close == -1 || close == index + 1 ? index : close;
            }
            case '&', '§' -> {
                if (index + 1 >= text.length()) {
                    return index;
                }
                char code = text.charAt(index + 1);
                if (code == '#' && index + 7 < text.length()) {
                    return isHex(text, index + 2, 6) ? index + 7 : index;
                }
                return isLegacyCode(code) ? index + 1 : index;
            }
            default -> {
                return index;
            }
        }
    }

    private static boolean isLegacyCode(char code) {
        char lower = Character.toLowerCase(code);
        return (lower >= '0' && lower <= '9')
                || (lower >= 'a' && lower <= 'f')
                || lower == 'k' || lower == 'l' || lower == 'm'
                || lower == 'n' || lower == 'o' || lower == 'r'
                // &x&8&a&5&1&c&4, the form older Bukkit tools produce. Only
                // the marker is consumed here; the codes that follow are
                // ordinary legacy codes and are skipped on their own.
                || lower == 'x';
    }

    private static boolean isHex(String text, int from, int count) {
        for (int index = from; index < from + count; index++) {
            char character = Character.toLowerCase(text.charAt(index));
            boolean valid = (character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f');
            if (!valid) {
                return false;
            }
        }
        return true;
    }
}
