package net.exylia.lib.text.internal;

/**
 * Rewrites the letters of a line as small capitals.
 *
 * <p>The look ExyliaCommons called its {@code small} font: {@code WELCOME}
 * drawn as {@code ᴡᴇʟᴄᴏᴍᴇ}. It is not a Minecraft font — the client has one
 * default font and no way to switch it from a message — so the letters are
 * swapped for the Unicode small capital that looks like them. Every glyph is
 * in the basic multilingual plane and fits a single {@code char}, which is
 * what lets this run as an array lookup rather than as the map of strings
 * commons used.
 *
 * <h2>What must survive untouched</h2>
 * The transform runs over the raw line, before MiniMessage parses it, so it
 * has to leave alone everything that is an instruction rather than text:
 * <ul>
 *   <li>a MiniMessage tag, {@code <gradient:#a:#b>} — rewriting the letters
 *       inside it produces a tag nobody can parse;</li>
 *   <li>a palette token, {@code {primary}}, and any other brace a plugin owns
 *       as a placeholder;</li>
 *   <li>a percent placeholder, {@code %eco_balance:comma%} — its name is
 *       matched literally when the value is substituted into the parsed
 *       component, so a rewritten name silently stops matching and the line
 *       reaches chat with the placeholder still in it;</li>
 *   <li>a legacy code, {@code &l} — {@code l} is a letter and would otherwise
 *       become {@code &ʟ}, which is no longer bold.</li>
 * </ul>
 *
 * <p>Anything with no small capital — digits, punctuation, accented letters,
 * the arrows and bars Exylia lore is built from — is copied through. Two
 * letters have no small capital at all in Unicode, {@code s} and {@code x};
 * lowercase is what commons drew for them and what this draws too.
 *
 * <h2>Case</h2>
 * There is no separate "force uppercase" switch, because there is nothing for
 * it to do: {@code a} and {@code A} both map to {@code ᴀ}. Commons shipped
 * that flag in every server's config for years and it could not change a
 * single character of output.
 */
public final class SmallText {

    /**
     * The small capital for each ASCII letter, indexed by the letter.
     *
     * <p>A zero means "no replacement, copy the character": that covers
     * everything that is not a letter, and the two letters Unicode has no
     * small capital for.
     */
    private static final char[] SMALL = new char[128];

    static {
        map('a', 'ᴀ');
        map('b', 'ʙ');
        map('c', 'ᴄ');
        map('d', 'ᴅ');
        map('e', 'ᴇ');
        map('f', 'ғ');
        map('g', 'ɢ');
        map('h', 'ʜ');
        map('i', 'ɪ');
        map('j', 'ᴊ');
        map('k', 'ᴋ');
        map('l', 'ʟ');
        map('m', 'ᴍ');
        map('n', 'ɴ');
        map('o', 'ᴏ');
        map('p', 'ᴘ');
        map('q', 'ǫ');
        map('r', 'ʀ');
        // Unicode has no small capital S, so lowercase stands in for it. Same
        // for X below. Commons drew them this way and the files were written
        // against that look.
        map('s', 's');
        map('t', 'ᴛ');
        map('u', 'ᴜ');
        map('v', 'ᴠ');
        map('w', 'ᴡ');
        map('x', 'x');
        map('y', 'ʏ');
        map('z', 'ᴢ');
    }

    /** Registers a glyph for both cases of a letter, since they share one. */
    private static void map(char lower, char small) {
        SMALL[lower] = small;
        SMALL[Character.toUpperCase(lower)] = small;
    }

    private SmallText() {
        throw new AssertionError("No instances.");
    }

    /**
     * Rewrites the visible letters of a line as small capitals.
     *
     * <p>Single pass, no allocation until the first letter that actually
     * changes: a line of pure punctuation or an already-transformed line is
     * returned as the same instance.
     *
     * @param text the raw line, in any notation the text module accepts
     * @return the line with its letters as small capitals
     */
    static String apply(String text) {
        int length = text.length();
        StringBuilder result = null;

        for (int index = 0; index < length; index++) {
            char current = text.charAt(index);

            // A tag, a token or a placeholder: skip to its end and copy it
            // exactly. Its content is an instruction, not something a player
            // reads.
            int skipTo = endOfInstruction(text, index, current);
            if (skipTo > index) {
                if (result != null) {
                    result.append(text, index, skipTo + 1);
                }
                index = skipTo;
                continue;
            }

            char replacement = current < SMALL.length ? SMALL[current] : 0;
            if (replacement == 0 || replacement == current) {
                if (result != null) {
                    result.append(current);
                }
                continue;
            }

            if (result == null) {
                result = new StringBuilder(length);
                result.append(text, 0, index);
            }
            result.append(replacement);
        }

        return result == null ? text : result.toString();
    }

    /**
     * Returns the index of the last character of the instruction starting at
     * {@code index}, or {@code index} itself when there is no instruction
     * there.
     */
    private static int endOfInstruction(String text, int index, char current) {
        switch (current) {
            case '<' -> {
                int close = text.indexOf('>', index + 1);
                // An unclosed '<' is text. MiniMessage would render it as text
                // too, so the two agree on what the player sees.
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
            case '&', '\u00a7' -> {
                if (index + 1 >= text.length()) {
                    return index;
                }
                char code = text.charAt(index + 1);
                if (code == '#' && index + 7 < text.length()) {
                    // &#8a51c4: six hex digits, four of which are letters.
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

    /**
     * Returns the small capital for a letter, or {@code 0} when there is none.
     *
     * <p>For measuring: a centred line has to know how wide the glyph that
     * will be drawn is, not how wide the letter that was written is.
     *
     * @param character the character as written
     * @return its small capital, or {@code 0} when it is drawn unchanged
     */
    public static char glyphFor(char character) {
        return character < SMALL.length ? SMALL[character] : 0;
    }

    /** Whether small capitals are on, so measuring knows what will be drawn. */
    public static boolean enabled() {
        return TextEngine.smallText();
    }
}
