package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.Rotation;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Drops the poses the client would have drawn anyway.
 *
 * <h2>Why a body is sampled finely and sent coarsely</h2>
 * The client draws a straight line, and the shortest turn, between any two
 * poses it is given. A piece that is holding still, or moving at one speed in
 * one direction, therefore needs its two ends and nothing in between; a piece
 * going round a curve or through an overshoot needs every tick of it. Sampling
 * everything every tick and then throwing away each pose that the line between
 * its neighbours already passes through keeps both: the curves stay curves, and
 * a leg that stood still for a second while the arms waved costs two packets
 * rather than twenty.
 *
 * <p>Greedy, and quadratic in the length of a straight run at worst: a run is
 * extended one pose at a time, and every pose it would skip is checked against
 * it. A run is a few dozen poses, so this is nothing next to the packets it
 * saves.
 */
@ApiStatus.Internal
public final class KeyframeThinning {

    /** How far a skipped pose may sit from the line, in blocks. */
    private static final double POSITION = 0.02;

    /** How far it may be turned from the turn the client would take, in radians. */
    private static final double ANGLE = Math.toRadians(3);

    /** How far its size may differ, in blocks. */
    private static final double SIZE = 0.015;

    private KeyframeThinning() {
    }

    /**
     * The same movement in as few poses as the client needs to draw it.
     *
     * @param poses every pose, in time order
     * @return the poses worth sending; the first and the last are always kept
     */
    public static List<DisplayKeyframe> thin(List<DisplayKeyframe> poses) {
        int count = poses.size();
        if (count <= 2) {
            return poses;
        }
        List<DisplayKeyframe> kept = new ArrayList<>();
        kept.add(poses.get(0));
        int anchor = 0;
        for (int end = 2; end < count; end++) {
            if (!drawnAnyway(poses, anchor, end)) {
                kept.add(poses.get(end - 1));
                anchor = end - 1;
            }
        }
        kept.add(poses.get(count - 1));
        return kept;
    }

    /** Whether every pose between two is where the client would put it anyway. */
    private static boolean drawnAnyway(List<DisplayKeyframe> poses, int from, int to) {
        DisplayKeyframe start = poses.get(from);
        DisplayKeyframe end = poses.get(to);
        long span = end.atMillis() - start.atMillis();
        for (int index = from + 1; index < to; index++) {
            DisplayKeyframe middle = poses.get(index);
            double at = span <= 0 ? 1 : (double) (middle.atMillis() - start.atMillis()) / span;
            double dx = start.x() + (end.x() - start.x()) * at - middle.x();
            double dy = start.y() + (end.y() - start.y()) * at - middle.y();
            double dz = start.z() + (end.z() - start.z()) * at - middle.z();
            if (dx * dx + dy * dy + dz * dz > POSITION * POSITION) {
                return false;
            }
            if (Math.abs(start.scaleX() + (end.scaleX() - start.scaleX()) * at - middle.scaleX()) > SIZE
                    || Math.abs(start.scaleY() + (end.scaleY() - start.scaleY()) * at - middle.scaleY()) > SIZE
                    || Math.abs(start.scaleZ() + (end.scaleZ() - start.scaleZ()) * at - middle.scaleZ()) > SIZE) {
                return false;
            }
            if (angle(slerp(start.rotation(), end.rotation(), at), middle.rotation()) > ANGLE) {
                return false;
            }
        }
        return true;
    }

    /** The turn the client takes between two rotations: the short way round. */
    static Rotation slerp(Rotation from, Rotation to, double at) {
        double dot = from.x() * to.x() + from.y() * to.y() + from.z() * to.z() + from.w() * to.w();
        double sign = dot < 0 ? -1 : 1;
        dot = Math.abs(dot);
        double fromShare;
        double toShare;
        if (dot > 0.9995) {
            fromShare = 1 - at;
            toShare = at;
        } else {
            double theta = Math.acos(dot);
            double sin = Math.sin(theta);
            fromShare = Math.sin((1 - at) * theta) / sin;
            toShare = Math.sin(at * theta) / sin;
        }
        toShare *= sign;
        double x = from.x() * fromShare + to.x() * toShare;
        double y = from.y() * fromShare + to.y() * toShare;
        double z = from.z() * fromShare + to.z() * toShare;
        double w = from.w() * fromShare + to.w() * toShare;
        double length = Math.sqrt(x * x + y * y + z * z + w * w);
        return new Rotation((float) (x / length), (float) (y / length), (float) (z / length),
                (float) (w / length));
    }

    /** How far apart two rotations are, in radians. */
    static double angle(Rotation one, Rotation other) {
        double dot = Math.abs(one.x() * other.x() + one.y() * other.y()
                + one.z() * other.z() + one.w() * other.w());
        return 2 * Math.acos(Math.min(1, dot));
    }
}
