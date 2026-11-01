package net.exylia.lib.spatial.internal;

/**
 * Which way a preview's body faces, moment by moment: turning on its own at a speed, or held on the side
 * the viewer turned it to, gliding there, until a pause runs out and it turns on its own again from
 * where it stands. Degrees from the front (towards the viewer), growing the way the body turns on its own
 * at a positive speed. Pure maths on a clock the caller reads; no server.
 *
 * <p>Internal: free to change without notice.
 */
public final class Turntable {

    /** How long a turn the viewer asked for glides. */
    public static final long GLIDE_MILLIS = 450L;

    private final double speed;
    private final long pauseMillis;
    /** Where it stood when it last started turning on its own, and since when. */
    private double from;
    private long since;
    /** A glide the viewer asked for: from, to, started at; and when the hold it ends in runs out. */
    private double glideFrom;
    private double glideTo;
    private long glideAt = -1;
    private long holdUntil = -1;

    /**
     * @param start       the side it stands on first, in degrees from the front
     * @param speed       degrees a second it turns on its own; 0 stands still
     * @param pauseMillis how long a side the viewer chose is held
     * @param now         the clock now
     */
    public Turntable(double start, double speed, long pauseMillis, long now) {
        this.from = start;
        this.since = now;
        this.speed = speed;
        this.pauseMillis = Math.max(0, pauseMillis);
    }

    /** Where it faces at {@code now}. */
    public double angle(long now) {
        if (glideAt >= 0) {
            if (now < holdUntil) {
                double progress = Math.min(1, (now - glideAt) / (double) GLIDE_MILLIS);
                // Eased out: quick off the mark, settling gently on the side.
                double eased = 1 - Math.pow(1 - progress, 3);
                return glideFrom + (glideTo - glideFrom) * eased;
            }
            // The hold ran out: it turns on its own again, from the side it was left on.
            from = glideTo;
            since = holdUntil;
            glideAt = -1;
            holdUntil = -1;
        }
        return from + speed * (now - since) / 1000.0;
    }

    /** Whether it holds a side the viewer chose, its own turning stopped. */
    public boolean holding(long now) {
        angle(now);
        return glideAt >= 0;
    }

    /** Turns it by {@code degrees} from where it faces (or is gliding to), and holds it there. */
    public void turnBy(double degrees, long now) {
        double here = angle(now);
        double base = glideAt >= 0 ? glideTo : here;
        glide(here, base + degrees, now);
    }

    /** Turns it on to the next quarter (front, side, back, other side) the {@code direction} way, and holds it. */
    public void nextSide(int direction, long now) {
        double here = angle(now);
        double base = glideAt >= 0 ? glideTo : here;
        int step = direction < 0 ? -1 : 1;
        // The next quarter strictly past where it is: a body a hair off the side moves on, not back to it.
        double quarter = step > 0 ? Math.floor(base / 90 + 1e-6) + 1 : Math.ceil(base / 90 - 1e-6) - 1;
        glide(here, quarter * 90, now);
    }

    private void glide(double here, double to, long now) {
        glideFrom = here;
        glideTo = to;
        glideAt = now;
        holdUntil = now + Math.max(GLIDE_MILLIS, pauseMillis);
    }
}
