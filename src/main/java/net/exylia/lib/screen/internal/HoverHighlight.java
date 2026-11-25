package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.Motion;
import net.exylia.lib.screen.Node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The frame cursor's instant hover: a few rects, the colour of a box lit or not, wait out of sight and are moved over
 * the box the pointer enters or leaves, by position, in the same bundle as the arrow. A 1.21.9+ client draws a position
 * the frame it arrives, and a display's look only on its next tick, so the copies are given their looks long before,
 * during the regular render, and only moved on the netty thread. The hover the server draws a tick later lands under
 * the copy, which then goes back out of sight once the client has drawn it: it looks as the copy did, so nothing is
 * seen to change.
 *
 * <p>Only a box whose hover changes nothing but a solid colour is lit this way, and nothing inside it may draw a
 * colour mixed over its own: a copy over it would not look as the hover drawn later does. Everything else is lit on
 * the tick, as before.
 *
 * <p>Internal: free to change without notice.
 */
final class HoverHighlight {

    /** The most copies one screen keeps, counted in its display budget. */
    static final int CAP = 8;
    /** Copies of one look: a box left while the copy over the one left before is still up takes a second. */
    static final int COPIES = 2;
    /** A copy is used only this long after its look was sent: the client draws a look on its next tick, 50 ms at most. */
    static final long READY_NANOS = 100_000_000L;
    /** Ticks after the render that drew a hover before the copy over it goes: that render reaches the client and is drawn. */
    static final int PARK_TICKS = 3;
    /** The longest a copy stays over a box whatever the renders say: a hover that never came. */
    static final long HOLD_NANOS = 1_000_000_000L;
    static final long TICK_NANOS = 50_000_000L;
    /** Ticks a hover glides in when no transition says otherwise ({@code LiveScreen#BOX_GLIDE}). */
    private static final int BOX_GLIDE = 1;

    private HoverHighlight() {
    }

    /**
     * A box a copy can light: its fill as drawn plain ({@code normal}) and hovered ({@code lit}), whether the hover
     * grows it by a pixel each side as a button rises, its fill's layer, and the ticks its hover glides.
     */
    record Lit(Layout.Placed box, int layer, boolean rises, int normal, int lit, int settle) {
    }

    /** A copy's look: a rect of {@code w} by {@code h} blocks of the view plane at a box's layer, in one colour. */
    record Look(double w, double h, int layer, int argb) {
    }

    /** One copy: its display, its look, and the box it stands over now ({@code null} out of sight). */
    static final class Slot {
        final int id;
        Look look;
        long readyAt;
        String box;
        boolean lit;
        int seq;
        long placedAt;
        /** When it goes out of sight, once a render drew its box as it looks; {@code Long.MAX_VALUE} for not yet. */
        long parkAt = Long.MAX_VALUE;
        /** The fill the render must have drawn for the copy to go unseen. */
        Object expect;
        int settle;

        Slot(int id) {
            this.id = id;
        }

        boolean free() {
            return box == null;
        }
    }

    /**
     * The boxes of {@code placed} a copy can light, by key: the ones the pointer can reach (from {@code from} when a
     * dialog is open), whose hover only changes a solid colour to another, with no transform or animation on them or
     * above them, and nothing inside them that a hover changes too (an item lifts, a picture or a see-through colour is
     * mixed over the box's own).
     */
    static Map<String, Lit> eligible(List<Layout.Placed> placed, int from) {
        int n = placed.size();
        boolean[] blocked = new boolean[n];
        for (int i = 0; i < n; i++) {
            Layout.Placed box = placed.get(i);
            Node node = box.node();
            if (node.item() != null) {
                // A hovered box lifts every item whose centre it holds.
                double cx = box.x() + box.w() / 2, cy = box.y() + box.h() / 2;
                for (int j = 0; j < n; j++) {
                    if (placed.get(j).depth() < box.depth() && placed.get(j).holds(cx, cy)) blocked[j] = true;
                }
            }
            if (mixed(node)) {
                for (int up = i; up >= 0; up = parent(placed, up)) blocked[up] = true;
            }
        }
        Map<String, Lit> out = new HashMap<>();
        for (int i = Math.max(0, from); i < n; i++) {
            if (blocked[i]) continue;
            Layout.Placed box = placed.get(i);
            Lit lit = lit(placed, i);
            if (lit != null) out.putIfAbsent(box.key(), lit);
        }
        return out;
    }

    private static Lit lit(List<Layout.Placed> placed, int i) {
        Layout.Placed box = placed.get(i);
        Node node = box.node();
        if (box.key().equals("~dim") || box.depth() == Layout.OVERLAY - 1 || box.w() <= 0 || box.h() <= 0) return null;
        if (node.hover() == null || node.hoverTransform() != null || node.preview() != null) return null;
        int normal = solid(node.background());
        int lit = solid(node.hover());
        if (normal == 0 || lit == 0 || normal == lit) return null;
        boolean rises = node.onClick() != null && node.activeTransform() == null;
        if (node.outlineWidth() > 0) {
            int line = ScreenMath.argb(node.outline(), 0);
            int hoverLine = node.hoverOutline() != null ? ScreenMath.argb(node.hoverOutline(), 0) : line;
            // An outline that changes or grows with the hover is drawn by the hover alone.
            if ((line != 0 || hoverLine != 0) && (line != hoverLine || rises)) return null;
        }
        Motion.Transition transition = null;
        for (int up = i; up >= 0; up = parent(placed, up)) {
            Node above = placed.get(up).node();
            if (!above.transform().none() || above.animation() != null) return null;
            if (transition == null) transition = above.transition();
        }
        // A colour that fades in is the hover's own motion: a copy would only skip it.
        if (transition != null && transition.colours() && transition.ticks() > BOX_GLIDE) return null;
        int settle = transition == null ? BOX_GLIDE : Math.max(BOX_GLIDE, transition.ticks());
        return new Lit(box, 2 + box.depth() * 3, rises, normal, lit, settle);
    }

    /** Whether the box draws something mixed over the colour of what holds it, which a hover above changes. */
    private static boolean mixed(Node node) {
        return node.pixels() != null || node.backgroundImage() != null || node.turntable() != null
                || node.preview() != null || seeThrough(node.background()) || seeThrough(node.hover())
                || node.outlineWidth() > 0 && (seeThrough(node.outline()) || seeThrough(node.hoverOutline()));
    }

    private static boolean seeThrough(String colour) {
        if (colour == null) return false;
        if (ScreenMath.gradient(colour) != null) return true;
        int a = ScreenMath.argb(colour, 0) >>> 24;
        return a != 0 && a != 0xFF;
    }

    /** The colour, when it is one and opaque; 0 otherwise. */
    private static int solid(String colour) {
        if (colour == null || ScreenMath.gradient(colour) != null) return 0;
        int argb = ScreenMath.argb(colour, 0);
        return argb >>> 24 == 0xFF ? argb : 0;
    }

    private static int parent(List<Layout.Placed> placed, int i) {
        int up = placed.get(i).parent();
        return up >= 0 && up < i ? up : -1;
    }

    /**
     * The copies of one screen. Guarded by the screen's input lock: the netty thread moves them, the viewer's thread
     * gives them their looks.
     */
    static final class Pool {
        final List<Slot> slots = new ArrayList<>();
        int seq;

        /** What a {@link #prepare} changed: copies to spawn, copies given another look, displays to remove. */
        record Changes(List<Slot> spawned, List<Slot> relooked, List<Integer> removed) {
        }

        /**
         * Gives the copies the looks {@code wanted} asks for, most wanted first: each look once, then a second copy of
         * each, {@code room} copies at most (and {@link #CAP}). A copy over a box keeps its look; a free one whose look
         * is no longer wanted takes another, the least wanted going first, and past the room it is removed.
         */
        Changes prepare(List<Look> wanted, int room, long now, java.util.function.IntSupplier ids) {
            int cap = Math.max(0, Math.min(CAP, room));
            Map<Look, Integer> want = new LinkedHashMap<>();
            int total = 0;
            for (int copy = 1; copy <= COPIES && total < cap; copy++) {
                for (Look look : wanted) {
                    if (total >= cap) break;
                    int has = want.getOrDefault(look, 0);
                    if (has >= copy) continue;
                    want.put(look, has + 1);
                    total++;
                }
            }
            List<Slot> stale = new ArrayList<>();
            for (Slot slot : slots) {
                Integer left = want.get(slot.look);
                if (left != null && left > 0) {
                    want.put(slot.look, left - 1);
                } else if (slot.free()) {
                    stale.add(slot);
                }
            }
            List<Slot> spawned = new ArrayList<>(), relooked = new ArrayList<>();
            for (Map.Entry<Look, Integer> entry : want.entrySet()) {
                for (int c = 0; c < entry.getValue(); c++) {
                    Slot slot;
                    if (!stale.isEmpty()) {
                        slot = stale.remove(stale.size() - 1);
                        relooked.add(slot);
                    } else if (slots.size() < cap) {
                        slot = new Slot(ids.getAsInt());
                        slots.add(slot);
                        spawned.add(slot);
                    } else {
                        continue;
                    }
                    slot.look = entry.getKey();
                    slot.readyAt = now + READY_NANOS;
                }
            }
            List<Integer> removed = new ArrayList<>();
            for (Slot slot : stale) {
                if (slots.size() <= cap) break;
                slots.remove(slot);
                removed.add(slot.id);
            }
            return new Changes(spawned, relooked, removed);
        }

        /** A free copy of {@code look} the client already draws as it, or {@code null}. */
        Slot take(Look look, long now) {
            for (Slot slot : slots) {
                if (slot.free() && slot.look.equals(look) && now - slot.readyAt >= 0) return slot;
            }
            return null;
        }

        /** The copy over {@code box}, or {@code null}. */
        Slot on(String box) {
            for (Slot slot : slots) {
                if (box.equals(slot.box)) return slot;
            }
            return null;
        }

        /** Marks {@code slot} as standing over {@code box}. */
        void place(Slot slot, String box, boolean lit, Object expect, int settle, long now) {
            slot.box = box;
            slot.lit = lit;
            slot.seq = ++seq;
            slot.placedAt = now;
            slot.parkAt = Long.MAX_VALUE;
            slot.expect = expect;
            slot.settle = settle;
        }

        /** Marks {@code slot} as out of sight. */
        static void park(Slot slot) {
            slot.box = null;
            slot.parkAt = Long.MAX_VALUE;
            slot.expect = null;
        }

        /**
         * A render up to placement {@code seen} decided the hover, and {@code drawn} says what fill it gave each box:
         * a copy over a box drawn as it looks goes once that render is drawn on the client, any other at once (the
         * returned ones). A copy already timed is checked again, so a box that changed under it shows at once.
         */
        List<Slot> decide(int seen, java.util.function.Function<String, Object> drawn, long now) {
            List<Slot> wrong = new ArrayList<>();
            for (Slot slot : slots) {
                if (slot.free()) continue;
                boolean timed = slot.parkAt != Long.MAX_VALUE;
                if (!timed && slot.seq > seen) continue;
                if (!slot.expect.equals(drawn.apply(slot.box))) {
                    wrong.add(slot);
                } else if (!timed) {
                    slot.parkAt = now + (PARK_TICKS + slot.settle) * TICK_NANOS;
                }
            }
            return wrong;
        }

        /** The copies due to go out of sight at {@code now}: timed, or held past {@link #HOLD_NANOS}. */
        List<Slot> due(long now) {
            List<Slot> out = new ArrayList<>();
            for (Slot slot : slots) {
                if (!slot.free() && (slot.parkAt != Long.MAX_VALUE && now - slot.parkAt >= 0 || now - slot.placedAt > HOLD_NANOS)) out.add(slot);
            }
            return out;
        }

        /** Every copy's display, the pool emptied. */
        List<Integer> clear() {
            List<Integer> ids = new ArrayList<>(slots.size());
            for (Slot slot : slots) ids.add(slot.id);
            slots.clear();
            return ids;
        }
    }
}
