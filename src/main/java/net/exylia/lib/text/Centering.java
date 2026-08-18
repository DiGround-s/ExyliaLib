package net.exylia.lib.text;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Centres a line in the chat window, the way ExyliaCommons did.
 *
 * <pre>{@code
 * Centering.center("{primary}WELCOME");
 * }</pre>
 *
 * <h2>How it works</h2>
 * Minecraft's default font is not monospaced: an {@code i} is one pixel wide
 * and a {@code W} is five. Centring by character count leaves text visibly
 * off-centre, so the width of every character is measured in pixels and the
 * line is padded with spaces until it sits around the middle of the chat
 * window (154 pixels of its 320).
 *
 * <p>Formatting is measured, not counted: MiniMessage tags and colour codes
 * take no space on screen, and bold text takes one pixel more per character.
 *
 * <p>Padding is spaces rather than a fixed offset because chat has no
 * concept of position — this is the same trick every centred Minecraft menu
 * uses, and the reason a centred line stops being centred if the player
 * changes their chat width.
 *
 * @since 1.17.0
 */
public final class Centering {

    /** Half the usable chat width, in pixels. */
    private static final int CENTRE_PX = 154;

    /** The full usable chat width, in pixels. */
    public static final int CHAT_WIDTH_PX = 320;

    private Centering() {
        throw new AssertionError("No instances.");
    }

    /**
     * Centres a line in the chat window.
     *
     * @param message the line, which may contain colour and formatting
     * @return the line padded to sit centred
     */
    public static @NotNull String center(@NotNull String message) {
        return centerWithin(message, CHAT_WIDTH_PX);
    }

    /**
     * Centres every line of a list.
     *
     * @param messages the lines
     * @return a new list, each line centred
     */
    public static @NotNull List<String> center(@NotNull List<String> messages) {
        return messages.stream().map(Centering::center).toList();
    }

    /**
     * Centres a line within a width of your choosing.
     *
     * <p>For a book, a sign, or a menu that is not the chat window. A line
     * wider than the space is returned unchanged rather than padded into
     * nonsense.
     *
     * @param message the line
     * @param width   the space to centre within, in pixels
     * @return the padded line
     */
    public static @NotNull String centerWithin(@NotNull String message, int width) {
        if (message.isEmpty()) {
            return message;
        }
        int messageWidth = pixelWidth(message);
        if (messageWidth > width) {
            return message;
        }
        int toCompensate = width / 2 - messageWidth / 2;
        // A space is three pixels, plus the one-pixel gap after every
        // character.
        int spaceWidth = FontWidths.SPACE + 1;

        StringBuilder padding = new StringBuilder();
        for (int compensated = 0; compensated < toCompensate; compensated += spaceWidth) {
            padding.append(' ');
        }
        return padding + message;
    }

    /**
     * Returns how wide a line is on screen, in pixels.
     *
     * <p>Formatting is not counted: tags and colour codes are invisible, and
     * bold characters are one pixel wider.
     *
     * @param message the line
     * @return its width in pixels
     */
    public static int pixelWidth(@NotNull String message) {
        int width = 0;
        boolean bold = false;

        for (int i = 0; i < message.length(); i++) {
            char current = message.charAt(i);

            // MiniMessage tag: <bold>, <gradient:#a:#b>, </bold>.
            if (current == '<') {
                int close = closingBracket(message, i);
                if (close != -1) {
                    String tag = tagName(message.substring(i + 1, close));
                    if (tag.equals("bold") || tag.equals("b")) {
                        bold = true;
                    } else if (tag.equals("/bold") || tag.equals("/b")
                            || tag.equals("reset") || tag.equals("r")) {
                        bold = false;
                    }
                    i = close;
                    continue;
                }
            }

            // Legacy code: &l turns bold on, and a colour or &r turns it off,
            // exactly as the client does. Either way the two characters take
            // no space on screen.
            if ((current == '&' || current == '\u00a7') && i + 1 < message.length()) {
                char code = Character.toLowerCase(message.charAt(i + 1));
                if (code == 'l') {
                    bold = true;
                } else if (code == 'r' || (code >= '0' && code <= '9')
                        || (code >= 'a' && code <= 'f')) {
                    bold = false;
                }
                if (isFormattingCode(code)) {
                    i++;
                    continue;
                }
            }

            // A palette token such as {primary} is replaced by a colour, so
            // it takes no space either.
            if (current == '{') {
                int close = message.indexOf('}', i);
                if (close != -1 && Colors.names().contains(message.substring(i + 1, close))) {
                    i = close;
                    continue;
                }
            }

            // Measure the glyph that will be drawn, not the letter that was
            // written: with small capitals on, "WELCOME" reaches the screen as
            // "ᴡᴇʟᴄᴏᴍᴇ", and a capital is five pixels where a small capital is
            // not. Measuring the source would push every centred line right.
            char drawn = current;
            if (net.exylia.lib.text.internal.SmallText.enabled()) {
                char small = net.exylia.lib.text.internal.SmallText.glyphFor(current);
                if (small != 0) {
                    drawn = small;
                }
            }

            width += bold ? FontWidths.boldWidthOf(drawn) : FontWidths.widthOf(drawn);
            // Every character is followed by a one-pixel gap.
            width++;
        }
        return width;
    }

    private static boolean isFormattingCode(char code) {
        return (code >= '0' && code <= '9')
                || (code >= 'a' && code <= 'f')
                || code == 'k' || code == 'l' || code == 'm'
                || code == 'n' || code == 'o' || code == 'r';
    }

    /**
     * Finds the {@code >} that closes a tag, ignoring quoted content.
     *
     * <p>A gradient can carry a quoted argument containing {@code >}, and
     * treating that as the end of the tag would measure the rest of the tag
     * as visible text.
     */
    private static int closingBracket(String text, int openIndex) {
        int depth = 0;
        boolean quoted = false;
        char quote = 0;

        for (int i = openIndex; i < text.length(); i++) {
            char current = text.charAt(i);

            if ((current == '\'' || current == '"')
                    && (i == 0 || text.charAt(i - 1) != '\\')) {
                if (!quoted) {
                    quoted = true;
                    quote = current;
                } else if (current == quote) {
                    quoted = false;
                }
            }

            if (!quoted) {
                if (current == '<') {
                    depth++;
                } else if (current == '>') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private static String tagName(String tagContent) {
        int colon = tagContent.indexOf(':');
        String name = colon == -1 ? tagContent : tagContent.substring(0, colon);
        return name.toLowerCase(java.util.Locale.ROOT);
    }
}
