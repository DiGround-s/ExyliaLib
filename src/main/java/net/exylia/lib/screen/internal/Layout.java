package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.Node;
import net.exylia.lib.text.Centering;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    /** An item's side when no size is given, in pixels: a slot. */
    public static final double ITEM = 16;
    /** The depth an overlay's dimmer is drawn at; the overlay sits one deeper. Far above any page. */
    public static final int OVERLAY = 30;
    /** The dimmer behind an overlay: see-through, drawn as it is. */
    public static final String DIMMER = "#b4050309";

    private Layout() {
    }

    /**
     * One placed box, top-left origin, y down, in screen pixels.
     *
     * @param key   stable between renders: the node's id, or its path from the root
     * @param depth how many boxes it sits inside; drawn nearer the eye the deeper it is
     */
    public record Placed(Node node, String key, double x, double y, double w, double h, int depth, int parent,
                         Wrapped wrapped) {

        /** A box with no parent in its list and no wrapped text. */
        public Placed(Node node, String key, double x, double y, double w, double h, int depth) {
            this(node, key, x, y, w, h, depth, -1, null);
        }

        public boolean holds(double px, double py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }

        /** Whether the point is inside the box grown by {@code margin} on every side. */
        public boolean holds(double px, double py, double margin) {
            return px >= x - margin && px <= x + w + margin && py >= y - margin && py <= y + h + margin;
        }

        /** The same box under another key, at another place and depth, its parent index moved by {@code shift}. */
        public Placed moved(String key, double dx, double dy, int depthBy, int shift) {
            return new Placed(node, key, x + dx, y + dy, w, h, depth + depthBy, parent < 0 ? -1 : parent + shift, wrapped);
        }
    }

    /**
     * A text node's words broken onto lines: what is drawn ({@code text}, lines joined by newlines), how many lines
     * and the widest one, in font pixels.
     */
    public record Wrapped(net.kyori.adventure.text.Component text, int lines, double width) {
    }

    /** Every box in the tree, parents before their children, the root filling {@code width} by {@code height}. */
    public static List<Placed> lay(Node root, double width, double height) {
        List<Placed> out = new ArrayList<>();
        List<Node> overlays = new ArrayList<>();
        // Each node is measured once a layout, however deep it sits.
        Ctx ctx = new Ctx();
        room(root, width, ctx);
        place(root, "0", 0, 0, width, height, 0, -1, out, overlays, ctx);
        // The last overlay wins: one dialog at a time, centred over everything.
        if (!overlays.isEmpty()) {
            Node top = overlays.get(overlays.size() - 1);
            out.add(new Placed(dimmer(top), "~dim", 0, 0, width, height, OVERLAY - 1));
            room(top, width, ctx);
            double[] size = measure(top, ctx);
            double w = Math.min(size[0], width);
            double h = Math.min(size[1], height);
            place(top, "~", (width - w) / 2, (height - h) / 2, w, h, OVERLAY, -1, out, new ArrayList<>(), ctx);
        }
        return out;
    }

    private static Node dimmer(Node top) {
        Node dimmer = Node.box().background(DIMMER);
        if (top.onDismiss() != null) dimmer.onClick(event -> top.onDismiss().accept(event.session()));
        return dimmer;
    }

    /**
     * {@code laid} (a {@link #lay} of a tree with the same {@link #fingerprint}) holding {@code root}'s nodes, so
     * their handlers are the new render's, without laying anything out again; {@code null} if the trees differ.
     */
    public static List<Placed> relink(List<Placed> laid, Node root) {
        List<Node> nodes = new ArrayList<>(laid.size());
        List<Node> overlays = new ArrayList<>();
        walk(root, nodes, overlays);
        if (!overlays.isEmpty()) {
            Node top = overlays.get(overlays.size() - 1);
            nodes.add(dimmer(top));
            walk(top, nodes, new ArrayList<>());
        }
        if (nodes.size() != laid.size()) return null;
        List<Placed> out = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            Placed was = laid.get(i);
            out.add(new Placed(nodes.get(i), was.key(), was.x(), was.y(), was.w(), was.h(), was.depth(), was.parent(),
                    was.wrapped()));
        }
        return out;
    }

    /** The nodes in the order {@link #place} lays them: flow children first, then the absolute ones. */
    private static void walk(Node node, List<Node> out, List<Node> overlays) {
        out.add(node);
        for (Node child : node.children()) {
            if (child.overlay()) overlays.add(child);
            else if (child.absolute() == null) walk(child, out, overlays);
        }
        for (Node child : node.children()) {
            if (!child.overlay() && child.absolute() != null) walk(child, out, overlays);
        }
    }

    /** What one layout remembers: sizes measured, the room text may wrap in, and wrapped text. */
    private static final class Ctx {
        final Map<Node, double[]> sizes = new IdentityHashMap<>();
        final Map<Node, Double> room = new IdentityHashMap<>();
        final Map<Node, Wrapped> wrapped = new IdentityHashMap<>();
    }

    /**
     * How wide each box can be at most, top down, for text that wraps to the room it has: a box's own width, or
     * what its parent has inside its padding. ponytail: a row hands every child its whole inner width, so a
     * paragraph beside other boxes wraps late and is drawn smaller to fit; give it a width or max-width there.
     */
    private static void room(Node node, double available, Ctx ctx) {
        double own = node.width() >= 0 ? node.width() : available;
        double[] m = node.margin();
        own = Math.max(0, own - (node.width() >= 0 ? 0 : m[1] + m[3]));
        ctx.room.put(node, own);
        double inner = Math.max(0, own - node.padLeft() - node.padRight());
        for (Node child : node.children()) room(child, inner, ctx);
    }

    /**
     * A hash of everything layout and drawing read from a tree: two trees with the same one lay out and draw the
     * same. Handlers count only by whether they are there.
     */
    public static long fingerprint(Node node) {
        long h = node.kind().ordinal();
        h = mix(h, Objects.hashCode(node.id()));
        h = mix(h, Objects.hashCode(node.key()));
        h = mix(h, Objects.hashCode(node.text()));
        h = mix(h, Objects.hashCode(node.item()));
        h = mix(h, node.pixels() == null ? 0 : Arrays.hashCode(node.pixels()));
        h = mix(h, node.pixelWidth() * 4099L + node.pixelHeight());
        h = mix(h, node.backgroundImage() == null ? 0 : Arrays.hashCode(node.backgroundImage().pixels()));
        h = mix(h, Objects.hashCode(node.turntable()));
        h = mix(h, Objects.hashCode(node.background()));
        h = mix(h, Objects.hashCode(node.hover()));
        h = mix(h, Objects.hashCode(node.outline()));
        h = mix(h, Objects.hashCode(node.hoverOutline()));
        h = mix(h, Objects.hashCode(node.tooltip()));
        h = mix(h, Objects.hash(node.transform(), node.hoverTransform(), node.activeTransform(), node.activeBackground(),
                node.transition(), node.enter(), node.leave(), node.animation(), node.layer(), node.wrap(),
                node.shadow(), node.decoration(), node.refresh(), node.onShiftClick() != null));
        h = mix(h, Arrays.hashCode(node.margin()));
        h = mix(h, node.absolute() == null ? 7 : Arrays.hashCode(node.absolute()));
        for (double d : new double[]{node.width(), node.height(), node.grow(), node.padTop(), node.padRight(),
                node.padBottom(), node.padLeft(), node.gap(), node.scale(), node.outlineWidth()}) {
            h = mix(h, Double.doubleToLongBits(d));
        }
        h = mix(h, node.align().ordinal() * 64L + node.textAlign().ordinal() * 8L + node.justify().ordinal());
        h = mix(h, (node.overlay() ? 1 : 0) | (node.onClick() != null ? 2 : 0) | (node.onScroll() != null ? 4 : 0)
                | (node.onDismiss() != null ? 8 : 0));
        h = mix(h, node.children().size());
        for (Node child : node.children()) h = mix(h, fingerprint(child));
        return h;
    }

    private static long mix(long h, long value) {
        h = (h ^ value) * 0x9E3779B97F4A7C15L;
        return h ^ (h >>> 31);
    }

    /** Where the overlay's boxes start in a {@link #lay} result, or -1 with none on screen. */
    public static int overlayStart(List<Placed> placed) {
        for (int i = 0; i < placed.size(); i++) if (placed.get(i).depth() == OVERLAY - 1) return i;
        return -1;
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
        Ctx ctx = new Ctx();
        room(node, node.width() >= 0 ? node.width() : 1000, ctx);
        return measure(node, ctx);
    }

    /** A node's size with its margins, as its parent lays it. */
    private static double[] outer(Node node, Ctx ctx) {
        double[] size = measure(node, ctx);
        double[] m = node.margin();
        return new double[]{size[0] + m[1] + m[3], size[1] + m[0] + m[2]};
    }

    /** A wrapping text node's lines, broken to its own wrap width or to the room it has, whichever is less. */
    private static Wrapped wrapped(Node node, Ctx ctx) {
        Wrapped known = ctx.wrapped.get(node);
        if (known != null) return known;
        double room = ctx.room.getOrDefault(node, node.wrap()) - node.padLeft() - node.padRight();
        double width = Math.max(10, Math.min(node.wrap(), room) / Math.max(0.01, node.scale()));
        Wrapped out = TextWrap.wrap(node.text(), width);
        ctx.wrapped.put(node, out);
        return out;
    }

    private static double[] measure(Node node, Ctx ctx) {
        Map<Node, double[]> sizes = ctx.sizes;
        double[] known = sizes.get(node);
        if (known != null) return known;
        double w;
        double h;
        if (node.kind() == Node.Kind.IMAGE) {
            w = node.pixelWidth() + node.padLeft() + node.padRight();
            h = node.pixelHeight() + node.padTop() + node.padBottom();
        } else if (node.kind() == Node.Kind.ITEM) {
            w = ITEM + node.padLeft() + node.padRight();
            h = ITEM + node.padTop() + node.padBottom();
        } else if (node.kind() == Node.Kind.TEXT && node.wrap() > 0 && node.text() != null) {
            Wrapped lines = wrapped(node, ctx);
            w = lines.width() * node.scale() + node.padLeft() + node.padRight();
            h = LINE * node.scale() * lines.lines() + node.padTop() + node.padBottom();
        } else if (node.kind() == Node.Kind.TEXT) {
            w = textWidth(node) + node.padLeft() + node.padRight();
            h = LINE * node.scale() + node.padTop() + node.padBottom();
        } else {
            boolean row = node.kind() == Node.Kind.ROW;
            double main = 0;
            double cross = 0;
            List<Node> children = node.children();
            children = children.stream().filter(child -> !child.overlay() && child.absolute() == null).toList();
            for (Node child : children) {
                double[] size = outer(child, ctx);
                main += row ? size[0] : size[1];
                cross = Math.max(cross, row ? size[1] : size[0]);
            }
            if (children.size() > 1) main += node.gap() * (children.size() - 1);
            w = (row ? main : cross) + node.padLeft() + node.padRight();
            h = (row ? cross : main) + node.padTop() + node.padBottom();
        }
        if (node.width() >= 0) w = node.width();
        if (node.height() >= 0) h = node.height();
        double[] size = {w, h};
        sizes.put(node, size);
        return size;
    }

    private static void place(Node node, String path, double x, double y, double w, double h, int depth, int parent,
                              List<Placed> out, List<Node> overlays, Ctx ctx) {
        // Children are keyed under their nearest id, so a row that a track= id follows keeps its boxes when it moves.
        String key = node.id() != null ? "#" + node.id() : path;
        int self = out.size();
        Wrapped lines = node.kind() == Node.Kind.TEXT && node.wrap() > 0 && node.text() != null ? wrapped(node, ctx) : null;
        out.add(new Placed(node, key, x, y, w, h, depth, parent, lines));
        List<Node> children = new ArrayList<>();
        List<Integer> absolutes = new ArrayList<>();
        for (int i = 0; i < node.children().size(); i++) {
            Node child = node.children().get(i);
            if (child.overlay()) overlays.add(child);
            else if (child.absolute() != null) absolutes.add(i);
            else children.add(child);
        }
        flow(node, key, x, y, w, h, depth, self, children, out, overlays, ctx);
        for (int i : absolutes) {
            Node child = node.children().get(i);
            double[] at = child.absolute();
            double[] size = measure(child, ctx);
            double[] m = child.margin();
            double cw = !Double.isNaN(at[1]) && !Double.isNaN(at[3]) && child.width() < 0 ? w - at[1] - at[3] - m[1] - m[3] : size[0];
            double ch = !Double.isNaN(at[0]) && !Double.isNaN(at[2]) && child.height() < 0 ? h - at[0] - at[2] - m[0] - m[2] : size[1];
            double cx = !Double.isNaN(at[3]) ? x + at[3] + m[3] : !Double.isNaN(at[1]) ? x + w - at[1] - m[1] - cw : x + m[3];
            double cy = !Double.isNaN(at[0]) ? y + at[0] + m[0] : !Double.isNaN(at[2]) ? y + h - at[2] - m[2] - ch : y + m[0];
            place(child, key + "." + i, cx, cy, Math.max(0, cw), Math.max(0, ch), depth + 1 + child.layer(), self, out,
                    overlays, ctx);
        }
    }

    /** The children in the flow: main axis, grow, shrink, justify and align. */
    private static void flow(Node node, String key, double x, double y, double w, double h, int depth, int self,
                             List<Node> children, List<Placed> out, List<Node> overlays, Ctx ctx) {
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
            double[] size = outer(children.get(i), ctx);
            mains[i] = row ? size[0] : size[1];
            crosses[i] = row ? size[1] : size[0];
            used += mains[i];
            grows += children.get(i).grow();
        }
        double spare = innerMain - used;
        if (spare < 0 && grows > 0) {
            // Too little room: the boxes that grow give it back, in proportion to their size, as flex-shrink
            // does. Fixed boxes and text keep theirs.
            double shrinkable = 0;
            for (int i = 0; i < count; i++) if (children.get(i).grow() > 0) shrinkable += mains[i];
            if (shrinkable > 0) {
                double cut = Math.min(-spare, shrinkable);
                for (int i = 0; i < count; i++) {
                    if (children.get(i).grow() > 0) mains[i] = Math.max(0, mains[i] - cut * mains[i] / shrinkable);
                }
                spare += cut;
            }
        }
        if (spare < 0) {
            // Still too little: words give way last, drawn smaller to fit the room they get (never past the box).
            double words = 0;
            for (int i = 0; i < count; i++) if (children.get(i).text() != null) words += mains[i];
            if (words > 0) {
                double cut = Math.min(-spare, words);
                for (int i = 0; i < count; i++) {
                    if (children.get(i).text() != null) mains[i] = Math.max(0, mains[i] - cut * mains[i] / words);
                }
                spare += cut;
            }
        }
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
            double[] m = child.margin();
            // Margins sit outside the box: along the main axis they were counted in its size, across it they are taken off.
            double crossMargin = row ? m[0] + m[2] : m[1] + m[3];
            boolean fixedCross = row ? child.height() >= 0 : child.width() >= 0;
            double cross = node.align() == Node.Align.STRETCH && !fixedCross ? innerCross - crossMargin : crosses[i] - crossMargin;
            // Nothing that sizes itself is wider than the box it stands in.
            if (!fixedCross && innerCross > 0) cross = Math.min(cross, innerCross - crossMargin);
            double offset = switch (node.align()) {
                case CENTER -> (innerCross - cross - crossMargin) / 2;
                case END -> innerCross - cross - crossMargin;
                default -> 0;
            };
            double cx = row ? innerX + cursor + m[3] : innerX + offset + m[3];
            double cy = row ? innerY + offset + m[0] : innerY + cursor + m[0];
            double main = Math.max(0, mains[i] - (row ? m[1] + m[3] : m[0] + m[2]));
            place(child, key + "." + i, cx, cy, row ? main : Math.max(0, cross), row ? Math.max(0, cross) : main,
                    depth + 1 + child.layer(), self, out, overlays, ctx);
            cursor += mains[i] + gap;
        }
    }
}
