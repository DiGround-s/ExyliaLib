package net.exylia.lib.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;

/**
 * One line written out a character at a time, its panel the same size from the first letter to the last.
 *
 * <p>A text display centres each row and sizes its panel to the widest one, so a row that simply grew would slide
 * and the panel would swell. The line is therefore wrapped once, here, with explicit row breaks, and what is not
 * written yet is spaces as wide as the letters they stand for. Widths come from the vanilla font's table; a
 * glyph it does not know is off by a pixel or two, which is a small shift and never a broken line.
 *
 * @since 1.528.0
 */
public final class Typewriter {

    /** A space, in pixels: the unit what is unwritten is measured in. */
    private static final int SPACE = 4;
    /** Rows stop this far short of the wrap width, so the client's own wrap never adds a row. */
    private static final double MARGIN = 0.94;

    private record Cell(int codePoint, Style style, int width) {
    }

    private final List<List<Cell>> rows;
    private final Component whole;
    private final int length;

    private Typewriter(List<List<Cell>> rows, Component whole) {
        this.rows = rows;
        this.whole = whole;
        int count = 0;
        for (List<Cell> row : rows) {
            count += row.size();
        }
        this.length = count;
    }

    public static Typewriter of(Component text, int lineWidth) {
        List<Cell> cells = new ArrayList<>();
        flatten(text, Style.empty(), cells);
        List<List<Cell>> rows = wrap(cells, (int) (lineWidth * MARGIN));
        return new Typewriter(rows, build(rows, Integer.MAX_VALUE));
    }

    /** How many characters there are to write. */
    public int length() {
        return length;
    }

    /** The finished line, wrapped as every frame was. */
    public Component whole() {
        return whole;
    }

    /** The line with its first {@code shown} characters written. */
    public Component frame(int shown) {
        return shown >= length ? whole : build(rows, shown);
    }

    private static void flatten(Component part, Style parent, List<Cell> out) {
        Style style = parent.merge(part.style());
        if (part instanceof TextComponent text) {
            text.content().codePoints().forEach(codePoint -> out.add(new Cell(codePoint, style, width(codePoint, style))));
        }
        for (Component child : part.children()) {
            flatten(child, style, out);
        }
    }

    private static int width(int codePoint, Style style) {
        return Centering.pixelWidth(codePoint, style.hasDecoration(TextDecoration.BOLD));
    }

    /** Breaks at the last space that fits, and inside a word only when the word alone is too wide. */
    private static List<List<Cell>> wrap(List<Cell> cells, int width) {
        List<List<Cell>> rows = new ArrayList<>();
        List<Cell> row = new ArrayList<>();
        int used = 0;
        int lastSpace = -1;
        for (Cell cell : cells) {
            if (cell.codePoint() == '\n') {
                rows.add(row);
                row = new ArrayList<>();
                used = 0;
                lastSpace = -1;
                continue;
            }
            row.add(cell);
            used += cell.width();
            if (cell.codePoint() == ' ') {
                lastSpace = row.size() - 1;
            }
            if (used > width && row.size() > 1) {
                int cut = lastSpace >= 0 ? lastSpace : row.size() - 1;
                List<Cell> rest = new ArrayList<>(row.subList(lastSpace >= 0 ? cut + 1 : cut, row.size()));
                rows.add(new ArrayList<>(row.subList(0, cut)));
                row = rest;
                used = 0;
                for (Cell left : row) {
                    used += left.width();
                }
                lastSpace = -1;
            }
        }
        rows.add(row);
        return rows;
    }

    private static Component build(List<List<Cell>> rows, int shown) {
        TextComponent.Builder out = Component.text();
        int left = shown;
        for (int index = 0; index < rows.size(); index++) {
            if (index > 0) {
                out.append(Component.newline());
            }
            List<Cell> row = rows.get(index);
            int written = Math.min(left, row.size());
            left -= written;
            appendRuns(out, row.subList(0, written));
            int hidden = 0;
            for (Cell cell : row.subList(written, row.size())) {
                hidden += cell.width();
            }
            if (hidden >= SPACE) {
                out.append(Component.text(" ".repeat(Math.round(hidden / (float) SPACE))));
            }
        }
        return out.build();
    }

    private static void appendRuns(TextComponent.Builder out, List<Cell> cells) {
        StringBuilder run = new StringBuilder();
        Style style = null;
        for (Cell cell : cells) {
            if (style != null && !style.equals(cell.style())) {
                out.append(Component.text(run.toString(), style));
                run.setLength(0);
            }
            style = cell.style();
            run.appendCodePoint(cell.codePoint());
        }
        if (style != null) {
            out.append(Component.text(run.toString(), style));
        }
    }
}
