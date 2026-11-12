package net.exylia.lib.scoreboard.internal;

import net.exylia.lib.text.Centering;
import net.exylia.lib.text.Text;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Keeps a sidebar one steady width and centres the lines that ask for it.
 *
 * <p>The client sizes a sidebar to its widest line and anchors it on the
 * right, so a value that grows by a digit (a ping going from 9 to 10) moves
 * the whole left edge. Here the board is never narrower than its widest line
 * with every value left out, and once a value has widened it, it keeps that
 * width until the line has been shorter for {@link #SHRINK_AFTER_MS}. The
 * width is held by padding a blank line with spaces, which nobody sees.
 *
 * <p>A line written with a leading {@code <center>} is padded on the left to
 * sit in the middle of that width. Spaces are four pixels, so a centred line
 * is at most two pixels off.
 */
final class BoardWidth {

    /** The tag a line starts with to be centred. */
    static final String CENTER = "<center>";

    /** How long a board keeps a width a value gave it once the value shrinks. */
    static final long SHRINK_AFTER_MS = 10_000;

    private static final int SPACE_PX = 4;
    private static final Pattern PLACEHOLDER = Pattern.compile("%[^%\\s]+%");

    private final boolean[] centered;
    private final int base;

    private int held;
    private long heldSince;

    /**
     * @param lines the configured lines, {@code <center>} still on them
     */
    BoardWidth(List<String> lines) {
        centered = new boolean[lines.size()];
        int widest = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i) == null ? "" : lines.get(i);
            centered[i] = line.startsWith(CENTER);
            String fixed = PLACEHOLDER.matcher(strip(line)).replaceAll("");
            widest = Math.max(widest, Centering.pixelWidth(Text.component(fixed)));
        }
        base = widest;
    }

    /** The line without its {@code <center>}. */
    static String strip(String line) {
        return line != null && line.startsWith(CENTER) ? line.substring(CENTER.length()) : line;
    }

    /** The width the board is held at with no value in it. */
    int base() {
        return base;
    }

    /**
     * Lays the lines out at the board's width.
     *
     * @param lines   each line as parsed
     * @param sources the configured line each came from, {@code -1} for a line a placeholder expanded into
     * @param title   the title's width in pixels: the client sizes the board to it too
     * @param now     the clock, in milliseconds
     * @return the lines to draw
     */
    Component[] layout(Component[] lines, int[] sources, int title, long now) {
        int[] widths = new int[lines.length];
        int widest = title;
        for (int i = 0; i < lines.length; i++) {
            widths[i] = Centering.pixelWidth(lines[i]);
            widest = Math.max(widest, widths[i]);
        }
        int width = hold(Math.max(base, widest), now);

        Component[] shown = lines.clone();
        int filler = -1;
        for (int i = 0; i < lines.length; i++) {
            if (isCentered(sources[i])) {
                shown[i] = Component.text(spaces((width - widths[i]) / 2)).append(lines[i]);
            } else if (filler < 0 && widths[i] == 0) {
                filler = i;
            }
        }
        if (widest < width) {
            int at = filler >= 0 ? filler : lines.length - 1;
            if (at >= 0 && !isCentered(sources[at])) {
                shown[at] = lines[at].append(Component.text(spaces(width - widths[at])));
            }
        }
        return shown;
    }

    private boolean isCentered(int source) {
        return source >= 0 && source < centered.length && centered[source];
    }

    /** Grows at once; shrinks only after the board has been narrower for a while. */
    private int hold(int wanted, long now) {
        if (wanted >= held) {
            held = wanted;
            heldSince = now;
        } else if (now - heldSince >= SHRINK_AFTER_MS) {
            held = wanted;
            heldSince = now;
        }
        return held;
    }

    private static String spaces(int pixels) {
        return pixels <= 0 ? "" : " ".repeat((pixels + SPACE_PX / 2) / SPACE_PX);
    }
}
