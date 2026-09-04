package net.exylia.lib.text.internal;

import net.exylia.lib.text.CharMaps;

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
 * <p>The walk itself — what is a letter and what is a tag, a token, a
 * placeholder or a legacy code that must be copied through — is
 * {@link CharMaps}; this class is only the table.
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
        return CharMaps.transform(text, SmallText::smallOf);
    }

    private static int smallOf(int codePoint) {
        if (codePoint >= SMALL.length) {
            return codePoint;
        }
        char small = SMALL[codePoint];
        return small == 0 ? codePoint : small;
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
