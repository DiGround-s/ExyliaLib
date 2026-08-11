package net.exylia.lib.text.internal;

/**
 * Decides, in one pass, how much work a piece of text actually needs.
 *
 * <p>Most strings a server sends are plain: a player name, a number, a score.
 * Running those through placeholder replacement, a legacy pass and a MiniMessage
 * parse costs far more than the message is worth, and this module sits on the
 * hot path of chat, titles, action bars and scoreboards.
 *
 * <p>So every string is scanned once, cheaply, for the three characters that can
 * possibly introduce formatting. What the scan finds decides which stages run:
 * a string with no ampersand, brace or angle bracket skips the pipeline entirely
 * and becomes a plain component.
 *
 * <p>The scan is a single loop over the characters with no allocation, which is
 * orders of magnitude cheaper than the parsing it avoids.
 */
public final class FormatScanner {

    /** Nothing to do: the text has no formatting characters at all. */
    public static final int PLAIN = 0;

    /** Contains {@code &}, so a legacy colour pass is needed. */
    public static final int AMPERSAND = 1;

    /** Contains <code>{</code>, so palette tokens or placeholders may be present. */
    public static final int BRACE = 1 << 1;

    /** Contains {@code <}, so MiniMessage tags may be present. */
    public static final int ANGLE = 1 << 2;

    private FormatScanner() {
    }

    /**
     * Reports which formatting characters appear in a string.
     *
     * @param text the text to scan
     * @return a bit set of {@link #AMPERSAND}, {@link #BRACE} and {@link #ANGLE},
     *         or {@link #PLAIN} when the text needs no processing at all
     */
    public static int scan(String text) {
        int flags = PLAIN;
        for (int i = 0, length = text.length(); i < length; i++) {
            switch (text.charAt(i)) {
                case '&' -> flags |= AMPERSAND;
                case '{' -> flags |= BRACE;
                case '<' -> flags |= ANGLE;
                default -> {
                    // Not a formatting character.
                }
            }
        }
        return flags;
    }

    /**
     * Returns whether a scan result means "no work needed".
     *
     * @param flags the result of {@link #scan(String)}
     * @return {@code true} when the text can be turned straight into a plain
     *         component
     */
    public static boolean isPlain(int flags) {
        return flags == PLAIN;
    }

    /**
     * Returns whether a flag is set.
     *
     * @param flags the result of {@link #scan(String)}
     * @param flag  the flag to test
     * @return {@code true} when present
     */
    public static boolean has(int flags, int flag) {
        return (flags & flag) != 0;
    }
}
