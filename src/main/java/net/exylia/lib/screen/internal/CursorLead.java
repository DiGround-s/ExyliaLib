package net.exylia.lib.screen.internal;

/**
 * Where the prediction cursor mode draws the arrow: ahead of the real pointer along its smoothed velocity, by about the
 * lag between the mouse and the arrow. Only what is drawn: clicks and hover read the real pointer.
 *
 * <p>Pure, so it is tested over a recorded trace. A client sends nothing once the mouse stops, so {@link #rest} ends it
 * exactly on the real pointer; a change of direction drops the velocity it had, so nothing is flung the wrong way.
 *
 * <p>Internal: free to change without notice.
 */
final class CursorLead {

    /** How a player's arrow follows the mouse. Only the drawing differs. */
    enum Mode {
        /** Glides one tick to each new place: smooth, the most behind. */
        NORMAL("Normal", "Glides after your mouse"),
        /** Lands at once on each new place: the least behind without guessing, in visible steps. */
        RAW("Raw", "Moves at once, in small steps"),
        /** Glides to where the mouse is heading: close to the hand, and it settles back when you stop. */
        PREDICTION("Prediction", "Glides ahead of your mouse");

        final String label;
        final String about;

        Mode(String label, String about) {
            this.label = label;
            this.about = about;
        }

        Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }

        /** The mode called {@code name}; {@link #NORMAL} for anything else. */
        static Mode of(String name) {
            for (Mode mode : values()) {
                if (mode.name().equalsIgnoreCase(name)) return mode;
            }
            return NORMAL;
        }
    }

    /** About what a client's next tick, the round trip and the glide add, in milliseconds. */
    static final double LEAD_MILLIS = 60;
    /** How much of the velocity the newest move decides: a hand moves smoothly and a packet's gap does not. */
    private static final double NEWEST = 0.6;
    /** The widest gap between two moves still counted as one movement: a client ticks every 50. */
    private static final long LONGEST_GAP = 120;

    private double lastX, lastY, vx, vy;
    private long lastAt = -1;

    /** Takes the pointer's new place and says where to draw it, at most {@code bound} from it. */
    double[] moved(double x, double y, long now, double bound) {
        long gap = now - lastAt;
        if (lastAt >= 0 && gap > 0 && gap <= LONGEST_GAP) {
            double nowX = (x - lastX) / gap, nowY = (y - lastY) / gap;
            if (nowX * vx + nowY * vy < 0) {
                vx = nowX;
                vy = nowY;
            } else {
                vx += (nowX - vx) * NEWEST;
                vy += (nowY - vy) * NEWEST;
            }
        } else if (gap > LONGEST_GAP || lastAt < 0) {
            vx = 0;
            vy = 0;
        }
        lastX = x;
        lastY = y;
        lastAt = now;
        double leadX = vx * LEAD_MILLIS, leadY = vy * LEAD_MILLIS;
        double length = Math.hypot(leadX, leadY);
        double scale = length > bound ? bound / length : 1;
        return new double[]{x + leadX * scale, y + leadY * scale};
    }

    /** The mouse stopped: the velocity is gone, and the arrow belongs on the pointer. */
    void rest() {
        vx = 0;
        vy = 0;
        lastAt = -1;
    }
}
