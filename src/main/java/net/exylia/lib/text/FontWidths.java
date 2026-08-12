package net.exylia.lib.text;

/**
 * How wide each character is in Minecraft's default font.
 *
 * <p>The same table ExyliaCommons used, so a line centred there is centred
 * here. Lookup is by array index rather than by scanning an enum, because
 * centring a scoreboard means measuring every character of every line.
 */
final class FontWidths {

    /** A space, in pixels. */
    static final int SPACE = 3;

    /** Anything not in the table: most characters are four pixels wide. */
    private static final int DEFAULT = 4;

    /** Widths for ASCII, indexed by character. */
    private static final int[] ASCII = new int[128];

    static {
        java.util.Arrays.fill(ASCII, DEFAULT);

        for (char c = 'A'; c <= 'Z'; c++) {
            ASCII[c] = 5;
        }
        for (char c = 'a'; c <= 'z'; c++) {
            ASCII[c] = 5;
        }
        for (char c = '0'; c <= '9'; c++) {
            ASCII[c] = 5;
        }

        ASCII['I'] = 3;
        ASCII['f'] = 4;
        ASCII['i'] = 1;
        ASCII['k'] = 4;
        ASCII['l'] = 1;
        ASCII['t'] = 4;

        ASCII['!'] = 1;
        ASCII['@'] = 6;
        ASCII['#'] = 5;
        ASCII['$'] = 5;
        ASCII['%'] = 5;
        ASCII['^'] = 5;
        ASCII['&'] = 5;
        ASCII['*'] = 5;
        ASCII['('] = 4;
        ASCII[')'] = 4;
        ASCII['-'] = 5;
        ASCII['_'] = 5;
        ASCII['+'] = 5;
        ASCII['='] = 5;
        ASCII['{'] = 4;
        ASCII['}'] = 4;
        ASCII['['] = 3;
        ASCII[']'] = 3;
        ASCII[':'] = 1;
        ASCII[';'] = 1;
        ASCII['"'] = 3;
        ASCII['\''] = 1;
        ASCII['<'] = 4;
        ASCII['>'] = 4;
        ASCII['?'] = 5;
        ASCII['/'] = 5;
        ASCII['\\'] = 5;
        ASCII['|'] = 1;
        ASCII['~'] = 5;
        ASCII['`'] = 2;
        ASCII['.'] = 1;
        ASCII[','] = 1;
        ASCII[' '] = SPACE;
    }

    private FontWidths() {
        throw new AssertionError("No instances.");
    }

    /** Returns how many pixels wide a character is. */
    static int widthOf(char character) {
        return character < ASCII.length ? ASCII[character] : DEFAULT;
    }

    /**
     * Returns the width of a bold character.
     *
     * <p>Bold draws each character twice, one pixel apart — except a space,
     * which has nothing to draw.
     */
    static int boldWidthOf(char character) {
        return character == ' ' ? SPACE : widthOf(character) + 1;
    }
}
