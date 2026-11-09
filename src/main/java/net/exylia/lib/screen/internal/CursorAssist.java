package net.exylia.lib.screen.internal;

import java.util.List;

/**
 * What cursor assist does to the real pointer, every strength in one place: it slows a slow move over a clickable box,
 * bends a slow move slightly toward the box it is heading for, and lets a click just off the box that was lit go to it.
 * All in layout pixels; a client sends one move per tick.
 *
 * <p>Pure, so it is tested without a screen. {@link LiveScreen} decides which boxes it may see: never a game board, a
 * dangerous button or anything else marked {@code assist="false"}. The bend is always a part of the player's own move
 * and only ever toward where they are already heading, so moving away is never resisted and it cannot step from box to
 * box. The cursor modes only change what is drawn; this moves the pointer, whichever mode draws it.
 *
 * <p>Internal: free to change without notice.
 */
final class CursorAssist {

    /** How much of a very slow move is kept over a clickable box. */
    static final double FRICTION = 0.7;
    /** A move this long keeps all of its travel over a box, so a deliberate move leaves at full speed. */
    static final double FRICTION_FASTEST = 12;
    /** At most about this part of a slow move is added toward the box it heads for. */
    static final double MAGNET = 0.5;
    /** How far from a box its magnet reaches. */
    static final double MAGNET_RANGE = 12;
    /** A move this long gets no bend at all. */
    static final double MAGNET_FASTEST = 6;
    /** How far past a lit box a click still goes to it, and for how long after leaving it. */
    static final double TOLERANCE_PIXELS = 8;
    static final long TOLERANCE_MILLIS = 200;
    /** A clickable box over this part of the screen is a background, not a button: no friction or magnet for it. */
    static final double LARGEST_SHARE = 0.25;

    private CursorAssist() {
    }

    /**
     * The move to make from ({@code x}, {@code y}) when the mouse asks for ({@code dx}, {@code dy}).
     *
     * @param over the clickable box under the pointer, {@code null} for none
     * @param near the clickable boxes it may bend toward
     */
    static double[] move(double x, double y, double dx, double dy, Layout.Placed over, List<Layout.Placed> near) {
        if (over != null) {
            double scale = friction(Math.hypot(dx, dy));
            return new double[]{dx * scale, dy * scale};
        }
        return magnet(x, y, dx, dy, near);
    }

    /** How much of a move this long is kept over a clickable box. */
    static double friction(double length) {
        return FRICTION + (1 - FRICTION) * Math.min(1, length / FRICTION_FASTEST);
    }

    private static double[] magnet(double x, double y, double dx, double dy, List<Layout.Placed> near) {
        double length = Math.hypot(dx, dy);
        if (length == 0 || length >= MAGNET_FASTEST) return new double[]{dx, dy};
        double toX = 0, toY = 0, closest = Double.MAX_VALUE, heading = 0;
        for (Layout.Placed box : near) {
            double boxX = Math.max(box.x(), Math.min(x, box.x() + box.w())) - x;
            double boxY = Math.max(box.y(), Math.min(y, box.y() + box.h())) - y;
            double distance = Math.hypot(boxX, boxY);
            if (distance == 0 || distance > MAGNET_RANGE || distance >= closest) continue;
            double cos = (dx * boxX + dy * boxY) / (length * distance);
            // Moving away or past it: nothing holds the pointer back.
            if (cos <= 0) continue;
            toX = boxX;
            toY = boxY;
            closest = distance;
            heading = cos;
        }
        if (heading == 0) return new double[]{dx, dy};
        double pull = Math.min(closest, MAGNET * length * heading * (1 - length / MAGNET_FASTEST));
        return new double[]{dx + toX / closest * pull, dy + toY / closest * pull};
    }

    /** Whether a click at ({@code x}, {@code y}), {@code age} millis after leaving {@code box}, still goes to it. */
    static boolean forgives(Layout.Placed box, double x, double y, long age) {
        return age <= TOLERANCE_MILLIS && box.holds(x, y, TOLERANCE_PIXELS);
    }
}
