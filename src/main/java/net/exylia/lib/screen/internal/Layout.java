package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.Node;
import net.exylia.lib.text.Centering;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Places a tree of {@link Node}s in the layout area: a small flexbox (main axis, grow, justify, align, padding,
 * gap) with no wrapping. Pure: no Bukkit, no packets, so it is tested on its own.
 *
 * <p>Internal: free to change without notice.
 */
public final class Layout {

    /** A line of text at scale 1, in pixels: the font's line height. */
    public static final double LINE = 10;

    private Layout() {
    }

    /**
     * One placed box, top-left origin, y down, in screen pixels.
     *
     * @param key   stable between renders: the node's id, or its path from the root
     * @param depth how many boxes it sits inside; drawn nearer the eye the deeper it is
     */
    public record Placed(Node node, String key, double x, double y, double w, double h, int depth) {

        public boolean holds(double px, double py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
    }

    /** Every box in the tree, parents before their children, the root filling {@code width} by {@code height}. */
    public static List<Placed> lay(Node root, double width, double height) {
        List<Placed> out = new ArrayList<>();
        place(root, "0", 0, 0, width, height, 0, out);
        return out;
    }

    /** The topmost box under a point that {@code test} accepts, or {@code null}. */
    public static Placed under(List<Placed> placed, double px, double py, Predicate<Node> test) {
        for (int i = placed.size() - 1; i >= 0; i--) {
            Placed box = placed.get(i);
            if (test.test(box.node()) && box.holds(px, py)) return box;
        }
        return null;
    }

    /** How wide a text node draws, in pixels. */
    static double textWidth(Node node) {
        return node.text() == null ? 0 : Centering.pixelWidth(node.text()) * node.scale();
    }

    /** What a node needs, {width, height}, before any growing or stretching. */
    static double[] measure(Node node) {
        double w;
        double h;
        if (node.kind() == Node.Kind.TEXT) {
            w = textWidth(node) + node.padLeft() + node.padRight();
            h = LINE * node.scale() + node.padTop() + node.padBottom();
        } else {
            boolean row = node.kind() == Node.Kind.ROW;
            double main = 0;
            double cross = 0;
            List<Node> children = node.children();
            for (Node child : children) {
                double[] size = measure(child);
                main += row ? size[0] : size[1];
                cross = Math.max(cross, row ? size[1] : size[0]);
            }
            if (children.size() > 1) main += node.gap() * (children.size() - 1);
            w = (row ? main : cross) + node.padLeft() + node.padRight();
            h = (row ? cross : main) + node.padTop() + node.padBottom();
        }
        if (node.width() >= 0) w = node.width();
        if (node.height() >= 0) h = node.height();
        return new double[]{w, h};
    }

    private static void place(Node node, String path, double x, double y, double w, double h, int depth,
                              List<Placed> out) {
        out.add(new Placed(node, node.id() != null ? "#" + node.id() : path, x, y, w, h, depth));
        List<Node> children = node.children();
        if (children.isEmpty()) return;
        boolean row = node.kind() == Node.Kind.ROW;
        double innerX = x + node.padLeft();
        double innerY = y + node.padTop();
        double innerMain = (row ? w - node.padLeft() - node.padRight() : h - node.padTop() - node.padBottom());
        double innerCross = (row ? h - node.padTop() - node.padBottom() : w - node.padLeft() - node.padRight());

        int count = children.size();
        double[] mains = new double[count];
        double[] crosses = new double[count];
        double used = node.gap() * (count - 1);
        double grows = 0;
        for (int i = 0; i < count; i++) {
            double[] size = measure(children.get(i));
            mains[i] = row ? size[0] : size[1];
            crosses[i] = row ? size[1] : size[0];
            used += mains[i];
            grows += children.get(i).grow();
        }
        double spare = innerMain - used;
        if (spare > 0 && grows > 0) {
            for (int i = 0; i < count; i++) mains[i] += spare * children.get(i).grow() / grows;
            spare = 0;
        }
        double gap = node.gap();
        double cursor = 0;
        if (spare > 0) {
            switch (node.justify()) {
                case CENTER -> cursor = spare / 2;
                case END -> cursor = spare;
                case SPACE_BETWEEN -> gap += count > 1 ? spare / (count - 1) : 0;
                default -> { }
            }
        }
        for (int i = 0; i < count; i++) {
            Node child = children.get(i);
            boolean fixedCross = row ? child.height() >= 0 : child.width() >= 0;
            double cross = node.align() == Node.Align.STRETCH && !fixedCross ? innerCross : crosses[i];
            double offset = switch (node.align()) {
                case CENTER -> (innerCross - cross) / 2;
                case END -> innerCross - cross;
                default -> 0;
            };
            double cx = row ? innerX + cursor : innerX + offset;
            double cy = row ? innerY + offset : innerY + cursor;
            place(child, path + "." + i, cx, cy, row ? mains[i] : cross, row ? cross : mains[i], depth + 1, out);
            cursor += mains[i] + gap;
        }
    }
}
