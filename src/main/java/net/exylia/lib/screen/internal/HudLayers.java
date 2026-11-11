package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.Node;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Node} tree flattened into what a HUD draws: the same layout a screen uses, turned into rects, words and
 * pictures listed back to front. Pure, so it is tested without a client.
 *
 * <p>A HUD's displays are see-through (a wall nearer than them must not hide them), and a client draws see-through
 * displays in the order it was sent them, with no depth between them: the list is that order. Every rect is a glyph,
 * never a display background, because a 1.21.9+ client draws every see-through background after every see-through
 * glyph, which would bury the words under their own panel.
 *
 * <p>Internal: free to change without notice.
 */
@ApiStatus.Internal
public final class HudLayers {

    /** What one layer is. */
    public enum Kind { RECT, TEXT, PICTURE }

    /**
     * One layer, in layout pixels from the widget's top left, y down.
     *
     * @param x      its centre across
     * @param y      its centre down
     * @param w      a rect's width, or a picture's cell
     * @param h      a rect's height
     * @param argb   a rect's colour
     * @param text   the words, or a picture's rows of glyphs
     * @param lines  how many lines the words or rows take
     * @param scale  the words' size against the widget's
     * @param flags  the words' text flags (alignment)
     * @param dx     a picture copy's nudge across, in font pixels
     * @param dy     a picture copy's nudge down, in font pixels
     */
    public record Layer(Kind kind, double x, double y, double w, double h, int argb, Component text, int lines,
                        double scale, int flags, int dx, int dy) {
    }

    /** What a tree drew: its layers and its size, in layout pixels. */
    public record Drawn(List<Layer> layers, double width, double height) {
    }

    /** Font pixels from one picture glyph to the next across, and from one row to the next down. */
    public static final int PICTURE_ADVANCE = ScreenPackets.IMAGE_ADVANCE;
    public static final int PICTURE_LINE = ScreenPackets.IMAGE_LINE;

    private HudLayers() {
    }

    /** Lays {@code root} out at its own size and lists what to draw, back to front. */
    public static Drawn of(Node root) {
        double[] size = Layout.measure(root);
        List<Layout.Placed> placed = Layout.lay(root, size[0], size[1]);
        List<Layer> out = new ArrayList<>();
        for (Layout.Placed box : placed) draw(box, out);
        return new Drawn(out, size[0], size[1]);
    }

    private static void draw(Layout.Placed box, List<Layer> out) {
        Node node = box.node();
        double cx = box.x() + box.w() / 2, cy = box.y() + box.h() / 2;
        int line = ScreenMath.argb(node.outline(), 0);
        String colour = node.background();
        ScreenMath.Gradient gradient = ScreenMath.gradient(colour);
        int fill = gradient != null ? 0 : ScreenMath.argb(colour, 0);
        if (node.radius() > 0 && box.w() > 0 && box.h() > 0 && (line != 0 || fill != 0 || gradient != null)) {
            rounded(box, node, line, fill, gradient, out);
        } else {
            if (line != 0 && node.outlineWidth() > 0 && box.w() > 0 && box.h() > 0) {
                // Four edges, never a rect behind the fill: a see-through fill would show it through its middle.
                double t = node.outlineWidth();
                double w = box.w() + 2 * t, h = box.h() + 2 * t;
                out.add(rect(cx, box.y() - t / 2, w, t, line));
                out.add(rect(cx, box.y() + box.h() + t / 2, w, t, line));
                out.add(rect(box.x() - t / 2, cy, t, box.h(), line));
                out.add(rect(box.x() + box.w() + t / 2, cy, t, box.h(), line));
            }
            if (box.w() > 0 && box.h() > 0) {
                if (gradient != null) {
                    paint(box, gradient, box.x(), box.y(), box.x() + box.w(), box.y() + box.h(), out);
                } else if (fill != 0) {
                    out.add(rect(cx, cy, box.w(), box.h(), fill));
                }
            }
        }
        if (node.pixels() != null) {
            double innerW = box.w() - node.padLeft() - node.padRight();
            double innerH = box.h() - node.padTop() - node.padBottom();
            double cell = Math.min(innerW / node.pixelWidth(), innerH / node.pixelHeight());
            if (cell > 0) {
                double ix = box.x() + node.padLeft() + innerW / 2;
                double iy = box.y() + node.padTop() + innerH / 2;
                Component rows = Pictures.glyphs(node.pixels(), node.pixelWidth(), node.pixelHeight(), 0);
                int gapX = ScreenPackets.IMAGE_ADVANCE - ScreenPackets.IMAGE_GLYPH;
                int gapY = ScreenPackets.IMAGE_LINE - ScreenPackets.IMAGE_GLYPH;
                // Four copies nudged by the gaps between glyphs, so the picture has no seams.
                for (int copy = 0; copy < 4; copy++) {
                    out.add(new Layer(Kind.PICTURE, ix, iy, cell, 0, 0, rows, node.pixelHeight(), 1, 0,
                            (copy & 1) * gapX, (copy >> 1) * gapY));
                }
            }
        }
        if (node.text() != null) {
            Layout.Wrapped wrapped = box.wrapped();
            Component words = wrapped != null ? wrapped.text() : node.text();
            int lines = wrapped != null ? wrapped.lines() : 1;
            double inner = box.w() - node.padLeft() - node.padRight();
            double wide = wrapped != null ? wrapped.width() * node.scale() : Layout.textWidth(node);
            double fit = wide > inner && inner > 0 ? inner / wide : 1;
            double spare = Math.max(0, inner - wide * fit);
            double shift = switch (node.textAlign()) {
                case START, STRETCH -> -spare / 2;
                case END -> spare / 2;
                case CENTER -> 0;
            };
            double tx = box.x() + node.padLeft() + inner / 2 + shift;
            double ty = box.y() + node.padTop() + (box.h() - node.padTop() - node.padBottom()) / 2;
            int flags = (lines > 1 ? switch (node.textAlign()) {
                case CENTER -> 0;
                case END -> 0x10;
                default -> 0x08;
            } : 0);
            out.add(new Layer(Kind.TEXT, tx, ty, 0, 0, 0, words, lines, node.scale() * fit, flags, 0, 0));
        }
    }

    private static Layer rect(double x, double y, double w, double h, int argb) {
        return new Layer(Kind.RECT, x, y, w, h, argb, null, 0, 1, 0, 0, 0);
    }

    /**
     * Rounded corners (1.585.0): the outline and the fill cut into rows that never overlap, each corner a pixel
     * staircase. Rows alike are merged, so a card of radius 2 with an outline is about a dozen rects.
     */
    private static void rounded(Layout.Placed box, Node node, int line, int fill, ScreenMath.Gradient gradient,
                                List<Layer> out) {
        int t = line != 0 && node.outlineWidth() > 0 ? (int) Math.max(1, Math.round(node.outlineWidth())) : 0;
        double ox = box.x() - t, oy = box.y() - t, wide = box.w() + 2 * t, tall = box.h() + 2 * t;
        int r = (int) Math.min(Math.round(node.radius()), Math.floor(Math.min(wide, tall) / 2));
        int inner = Math.max(0, r - t);
        int zone = Math.max(r, t);
        // Each corner row: {outer inset, inner inset or -1 when the whole row is outline}.
        List<int[]> rows = new ArrayList<>();
        for (int j = 0; j < zone; j++) {
            int outer = j < r ? inset(r, j) : 0;
            int in = j < t ? -1 : (j - t < inner ? inset(inner, j - t) : 0);
            rows.add(new int[]{outer, in});
        }
        // Top rows, merged where alike.
        int j = 0;
        while (j < zone) {
            int k = j + 1;
            while (k < zone && java.util.Arrays.equals(rows.get(k), rows.get(j))) k++;
            row(ox, oy + j, oy + k, wide, t, rows.get(j), line, fill, gradient, box, out);
            j = k;
        }
        double middleTop = oy + zone, middleBottom = oy + tall - zone;
        if (middleBottom > middleTop) {
            row(ox, middleTop, middleBottom, wide, t, new int[]{0, 0}, line, fill, gradient, box, out);
        }
        j = zone;
        while (j > 0) {
            int k = j - 1;
            while (k > 0 && java.util.Arrays.equals(rows.get(k - 1), rows.get(j - 1))) k--;
            row(ox, oy + tall - j, oy + tall - k, wide, t, rows.get(j - 1), line, fill, gradient, box, out);
            j = k;
        }
    }

    /** One band of rows from {@code y0} to {@code y1}: outline on both sides and the fill between. */
    private static void row(double ox, double y0, double y1, double wide, int t, int[] cut, int line, int fill,
                            ScreenMath.Gradient gradient, Layout.Placed box, List<Layer> out) {
        int outer = cut[0];
        double left = ox + outer, right = ox + wide - outer;
        if (right <= left) return;
        if (cut[1] < 0) {
            if (line != 0) out.add(span(left, y0, right, y1, line));
            return;
        }
        double fillLeft = ox + t + cut[1], fillRight = ox + wide - t - cut[1];
        if (line != 0 && fillLeft > left) {
            out.add(span(left, y0, Math.min(fillLeft, right), y1, line));
            if (fillRight < right) out.add(span(Math.max(fillRight, left), y0, right, y1, line));
        }
        if (fillRight > fillLeft) {
            if (gradient != null) paint(box, gradient, fillLeft, y0, fillRight, y1, out);
            else if (fill != 0) out.add(span(fillLeft, y0, fillRight, y1, fill));
        }
    }

    /** How far row {@code j} of a corner of radius {@code r} stands in: a pixel circle (4 → 4, 2, 1, 1). */
    static int inset(int r, int j) {
        double dy = r - j;
        return (int) Math.ceil(r - Math.sqrt(Math.max(0, (double) r * r - dy * dy)) - 1e-9);
    }

    private static Layer span(double x0, double y0, double x1, double y1, int argb) {
        return rect((x0 + x1) / 2, (y0 + y1) / 2, x1 - x0, y1 - y0, argb);
    }

    /**
     * The part of {@code box}'s gradient inside {@code x0..x1}, {@code y0..y1}, as bands, one each five pixels
     * along it. Edge to edge: a see-through band reaching into the next would draw a darker seam where they overlap.
     */
    private static void paint(Layout.Placed box, ScreenMath.Gradient gradient, double x0, double y0, double x1,
                              double y1, List<Layer> out) {
        boolean vertical = gradient.vertical();
        double origin = vertical ? box.y() : box.x();
        double length = vertical ? box.h() : box.w();
        int n = ScreenMath.bands(length);
        int start = 0;
        for (int i = 1; i <= n; i++) {
            int colour = gradient.at((start + 0.5) / n);
            if (i < n && gradient.at((i + 0.5) / n) == colour) continue;
            double from = origin + length * start / n, to = origin + length * i / n;
            start = i;
            if (vertical) {
                double a = Math.max(from, y0), b = Math.min(to, y1);
                if (b > a) out.add(span(x0, a, x1, b, colour));
            } else {
                double a = Math.max(from, x0), b = Math.min(to, x1);
                if (b > a) out.add(span(a, y0, b, y1, colour));
            }
        }
    }
}
