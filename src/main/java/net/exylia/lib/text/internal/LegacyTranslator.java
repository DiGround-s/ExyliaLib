package net.exylia.lib.text.internal;

/**
 * Rewrites legacy formatting into MiniMessage tags, in a single pass.
 *
 * <p>Exylia files mix three notations, often in the same line, such as a colour
 * token followed by a legacy bold code and a legacy grey code. Rather than run
 * three separate parsers over the string, everything is normalised to
 * MiniMessage here and parsed once. That keeps the expensive step to exactly one
 * pass and lets MiniMessage own the nesting rules.
 *
 * <p>Handled, writing the ampersand as {@code amp}:
 * <ul>
 *   <li>{@code amp+a}, {@code amp+l} and the rest of the legacy codes;</li>
 *   <li>{@code amp+#rrggbb}, the common hex form;</li>
 *   <li>{@code amp+x} followed by six coded digits, the form produced by older
 *       Bukkit tools;</li>
 *   <li>a literal ampersand that is not a code, which is left alone.</li>
 * </ul>
 *
 * <p>Angle brackets are left untouched: MiniMessage already renders an unknown
 * or unclosed tag as literal text, so escaping them here would cost a check per
 * character and break genuine tags.
 */
public final class LegacyTranslator {

    /**
     * Maps a legacy code to its MiniMessage tag.
     *
     * <p>Indexed by character so lookup is an array read rather than a map
     * lookup or a switch on a boxed key. Only ASCII is possible here, hence 128
     * entries.
     */
    private static final String[] CODES = new String[128];

    static {
        CODES['0'] = "<black>";
        CODES['1'] = "<dark_blue>";
        CODES['2'] = "<dark_green>";
        CODES['3'] = "<dark_aqua>";
        CODES['4'] = "<dark_red>";
        CODES['5'] = "<dark_purple>";
        CODES['6'] = "<gold>";
        CODES['7'] = "<gray>";
        CODES['8'] = "<dark_gray>";
        CODES['9'] = "<blue>";
        CODES['a'] = "<green>";
        CODES['b'] = "<aqua>";
        CODES['c'] = "<red>";
        CODES['d'] = "<light_purple>";
        CODES['e'] = "<yellow>";
        CODES['f'] = "<white>";
        CODES['k'] = "<obfuscated>";
        CODES['l'] = "<bold>";
        CODES['m'] = "<strikethrough>";
        CODES['n'] = "<underlined>";
        CODES['o'] = "<italic>";
        CODES['r'] = "<reset>";
        // Upper case works the same way in vanilla, so accept it too.
        for (char lower = 'a'; lower <= 'z'; lower++) {
            if (CODES[lower] != null) {
                CODES[Character.toUpperCase(lower)] = CODES[lower];
            }
        }
        for (char digit = '0'; digit <= '9'; digit++) {
            CODES[digit] = CODES[digit];
        }
    }

    private LegacyTranslator() {
    }

    /**
     * Converts legacy formatting to MiniMessage.
     *
     * @param text  the raw text
     * @param flags the result of {@link FormatScanner#scan(String)}
     * @return MiniMessage-ready text
     */
    public static String toMiniMessage(String text, int flags) {
        boolean hasAmpersand = FormatScanner.has(flags, FormatScanner.AMPERSAND);
        boolean hasAngle = FormatScanner.has(flags, FormatScanner.ANGLE);

        if (!hasAmpersand && !hasAngle) {
            return text;
        }

        int length = text.length();
        // Legacy codes grow when expanded, so start with room to avoid regrowth.
        StringBuilder result = new StringBuilder(length + (hasAmpersand ? length / 2 : 8));

        for (int i = 0; i < length; i++) {
            char current = text.charAt(i);

            if (current == '&' && i + 1 < length) {
                int consumed = appendLegacy(text, i, result);
                if (consumed > 0) {
                    i += consumed - 1;
                    continue;
                }
            }

            // A '<' is left exactly as it is. MiniMessage already treats an
            // unknown or unclosed tag as literal text, so escaping here would
            // cost a check per character and change nothing, while breaking
            // real tags such as <reset>.
            result.append(current);
        }

        return result.toString();
    }

    /**
     * Writes the MiniMessage form of a legacy sequence starting at {@code index}.
     *
     * @return how many characters were consumed, or {@code 0} if this was not a
     *         legacy code after all
     */
    private static int appendLegacy(String text, int index, StringBuilder out) {
        char code = text.charAt(index + 1);

        // &#rrggbb
        if (code == '#' && index + 7 < text.length()) {
            String hex = text.substring(index + 2, index + 8);
            if (isHex(hex)) {
                out.append('<').append('#').append(hex).append('>');
                return 8;
            }
            return 0;
        }

        // &x&r&r&g&g&b&b, six colour codes after an x
        if ((code == 'x' || code == 'X') && index + 13 < text.length()) {
            StringBuilder hex = new StringBuilder(6);
            for (int offset = 0; offset < 6; offset++) {
                int position = index + 2 + offset * 2;
                if (text.charAt(position) != '&' && text.charAt(position) != '\u00a7') {
                    return 0;
                }
                hex.append(text.charAt(position + 1));
            }
            if (isHex(hex.toString())) {
                out.append('<').append('#').append(hex).append('>');
                return 14;
            }
            return 0;
        }

        if (code < CODES.length) {
            String tag = CODES[code];
            if (tag != null) {
                out.append(tag);
                return 2;
            }
        }

        return 0;
    }

    private static boolean isHex(String candidate) {
        for (int i = 0; i < candidate.length(); i++) {
            char character = candidate.charAt(i);
            boolean valid = (character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F');
            if (!valid) {
                return false;
            }
        }
        return true;
    }
}
