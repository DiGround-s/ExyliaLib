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
        if (line != 0 && node.outlineWidth() > 0 && box.w() > 0 && box.h() > 0) {
            // Four edges, never a rect behind the fill: a see-through fill would show it through its middle.
            double t = node.outlineWidth();
            double w = box.w() + 2 * t, h = box.h() + 2 * t;
            out.add(rect(cx, box.y() - t / 2, w, t, line));
            out.add(rect(cx, box.y() + box.h() + t / 2, w, t, line));
            out.add(rect(box.x() - t / 2, cy, t, box.h(), line));
            out.add(rect(box.x() + box.w() + t / 2, cy, t, box.h(), line));
        }
        String colour = node.background();
        ScreenMath.Gradient gradient = ScreenMath.gradient(colour);
        if (box.w() > 0 && box.h() > 0) {
            if (gradient != null) {
                bands(gradient, box, out);
            } else {
                int argb = ScreenMath.argb(colour, 0);
                if (argb != 0) out.add(rect(cx, cy, box.w(), box.h(), argb));
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

    /** A gradient as bands, one each five pixels along it. */
    private static void bands(ScreenMath.Gradient gradient, Layout.Placed box, List<Layer> out) {
        double length = gradient.vertical() ? box.h() : box.w();
        int n = ScreenMath.bands(length);
        int start = 0;
        for (int i = 1; i <= n; i++) {
            int colour = gradient.at((start + 0.5) / n);
            if (i < n && gradient.at((i + 0.5) / n) == colour) continue;
            // Edge to edge: a see-through band reaching into the next would draw a darker seam where they overlap.
            double from = (double) start / n, to = (double) i / n;
            if (gradient.vertical()) {
                out.add(rect(box.x() + box.w() / 2, box.y() + box.h() * (from + to) / 2, box.w(), box.h() * (to - from),
                        colour));
            } else {
                out.add(rect(box.x() + box.w() * (from + to) / 2, box.y() + box.h() / 2, box.w() * (to - from), box.h(),
                        colour));
            }
            start = i;
        }
    }
}
