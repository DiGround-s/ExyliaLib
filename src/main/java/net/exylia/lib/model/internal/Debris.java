package net.exylia.lib.model.internal;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Rotation;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A model's cubes falling apart for real: thrown, tumbling, bouncing off the
 * floor and the walls around them, sliding to a stop, then melting where they lie.
 *
 * <p>Every flight is worked out on the server the moment the model breaks,
 * against the blocks around it, and handed to the display module as poses the
 * client glides between: a cube costs a few packets however long it flies, and
 * the server spends nothing on it afterwards.
 *
 * <p>Pure: the blocks are asked through {@link Solid}, and the dice are handed
 * in, so a flight can be asserted to the pixel.
 */
@ApiStatus.Internal
public final class Debris {

    /** Blocks a second squared: what an item falls at. */
    static final double GRAVITY = 16;
    /** Steps a second the flight is worked out in. */
    static final int STEPS = 40;
    /** How often a pose is handed to the client, in steps: every tenth of a second. */
    static final int POSE_EVERY = 4;
    /** The longest a cube flies before it is made to lie still. */
    static final long MAX_FLIGHT_MS = 2_200;
    /** How long it lies still before it melts, and how long the melting takes. */
    static final long REST_MS = 350;
    static final long MELT_MS = 450;
    /** The share of its speed a cube keeps off the floor, and off a wall. */
    static final double BOUNCE = 0.38;
    static final double WALL_BOUNCE = 0.3;
    /** Below this, in blocks a second, a cube landing stops bouncing. */
    static final double SETTLE = 0.9;
    /** The share of its sliding speed a cube keeps each second on the ground. */
    static final double FRICTION = 0.04;
    /** The most cubes one break works out; the rest fly the simple way. */
    public static final int MAX_PIECES = 160;

    /** How solid a block is. */
    @FunctionalInterface
    public interface Solid {
        /**
         * @return the height of the block's top above its own bottom, {@code 0-1}; {@code 0} for nothing to land on
         */
        double top(int x, int y, int z);
    }

    private Debris() {
    }

    /**
     * One cube's flight.
     *
     * @param from   the cube as it was posed: offset from {@code origin}, turn and size as sent
     * @param extent how big it looks, in blocks, x y z: a head is sent at twice the size it looks
     * @param origin where the model stood, in the world, x y z
     * @param push   which way it was pushed from, blocks a second; zeroes for none
     * @param centre the model's middle, offset from {@code origin}
     * @param solid  the blocks
     * @param random the dice
     * @return the motion, relative to {@code origin}
     */
    public static DisplayMotion fly(DisplayKeyframe from, double[] extent, double[] origin, double[] push,
                                    double[] centre, Solid solid, Random random) {
        double sx = Math.abs(extent[0]);
        double sy = Math.abs(extent[1]);
        double sz = Math.abs(extent[2]);
        double size = Math.max(0.02, (sx + sy + sz) / 3);
        // A tumbling cube lands on whichever face: somewhere between its thinnest and its average half.
        double radius = Math.clamp((Math.min(Math.min(sx, sy), sz) + size) * 0.25, 0.03, 0.5);

        // Out from the middle, up, and away from whatever struck it, lighter cubes further.
        double outX = from.x() - centre[0];
        double outZ = from.z() - centre[2];
        double out = Math.hypot(outX, outZ);
        if (out < 1e-3) {
            double angle = random.nextDouble() * Math.PI * 2;
            outX = Math.cos(angle);
            outZ = Math.sin(angle);
            out = 1;
        }
        double light = 1 / Math.sqrt(Math.max(0.15, size));
        double spread = 1.1 + random.nextDouble() * 1.3;
        double vx = outX / out * spread * light * 0.7 + push[0] * light + (random.nextDouble() - 0.5) * 0.6;
        double vz = outZ / out * spread * light * 0.7 + push[2] * light + (random.nextDouble() - 0.5) * 0.6;
        double vy = 2.2 + random.nextDouble() * 2.6 + Math.max(0, from.y() - centre[1]) * 0.8 + push[1];

        double[] axis = {random.nextDouble() - 0.5, random.nextDouble() - 0.5, random.nextDouble() - 0.5};
        double axisLength = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
        if (axisLength < 1e-6) {
            axis = new double[]{0, 1, 0};
            axisLength = 1;
        }
        for (int index = 0; index < 3; index++) {
            axis[index] /= axisLength;
        }
        // Radians a second, small cubes spinning faster; never past half a turn between two poses.
        double spin = Math.min(Math.PI * 4, (2 + random.nextDouble() * 4) * light);

        double x = origin[0] + from.x();
        double y = origin[1] + from.y();
        double z = origin[2] + from.z();
        double angle = 0;
        double dt = 1.0 / STEPS;
        int maxSteps = (int) (MAX_FLIGHT_MS * STEPS / 1000);
        boolean grounded = false;
        int stillSteps = 0;
        List<DisplayKeyframe> poses = new ArrayList<>();
        poses.add(from.atMillis() == 0 ? from : copy(from, 0, from.x(), from.y(), from.z(), from.rotation()));
        int step = 0;
        for (; step < maxSteps; step++) {
            if (!grounded) {
                vy -= GRAVITY * dt;
            }
            // Across, then along, then up: a wall stops one axis and leaves the others alone.
            double nx = x + vx * dt;
            if (hits(solid, nx + Math.signum(vx) * radius, y, z)) {
                vx = -vx * WALL_BOUNCE;
                spin *= 0.7;
            } else {
                x = nx;
            }
            double nz = z + vz * dt;
            if (hits(solid, x, y, nz + Math.signum(vz) * radius)) {
                vz = -vz * WALL_BOUNCE;
                spin *= 0.7;
            } else {
                z = nz;
            }
            double ny = y + vy * dt;
            if (vy <= 0) {
                double floor = floor(solid, x, ny - radius, z);
                if (!Double.isNaN(floor) && ny - radius < floor) {
                    ny = floor + radius;
                    if (-vy < SETTLE) {
                        vy = 0;
                        grounded = true;
                    } else {
                        vy = -vy * BOUNCE;
                        vx *= 0.7;
                        vz *= 0.7;
                        spin *= 0.55;
                    }
                } else if (grounded && Double.isNaN(floor(solid, x, ny - radius - 0.05, z))) {
                    // Slid off an edge.
                    grounded = false;
                }
            } else if (hits(solid, x, ny + radius, z)) {
                vy = -vy * 0.3;
                ny = y;
            }
            y = ny;
            if (grounded) {
                double keep = Math.pow(FRICTION, dt);
                vx *= keep;
                vz *= keep;
                spin *= keep;
            }
            angle += spin * dt;
            boolean still = grounded && Math.hypot(vx, vz) < 0.05 && spin < 0.2;
            stillSteps = still ? stillSteps + 1 : 0;
            if ((step + 1) % POSE_EVERY == 0) {
                poses.add(copy(from, (step + 1) * 1000L / STEPS, x - origin[0], y - origin[1], z - origin[2],
                        turned(from.rotation(), axis, angle)));
            }
            if (stillSteps >= STEPS / 5) {
                step++;
                break;
            }
        }
        long landed = step * 1000L / STEPS;
        DisplayKeyframe last = poses.get(poses.size() - 1);
        if (last.atMillis() < landed) {
            last = copy(from, landed, x - origin[0], y - origin[1], z - origin[2], turned(from.rotation(), axis, angle));
            poses.add(last);
        }
        // Lies still a moment, then melts into the ground it lies on.
        poses.add(copy(last, landed + REST_MS, last.x(), last.y(), last.z(), last.rotation()));
        float flatY = (float) (last.y() - radius + 0.01);
        poses.add(new DisplayKeyframe(landed + REST_MS + MELT_MS, last.x(), flatY, last.z(), last.rotation(),
                last.scaleX() * 0.9f, 0.01f, last.scaleZ() * 0.9f));
        return DisplayMotion.of(poses, landed + REST_MS + MELT_MS + 50);
    }

    /** Whether a point is inside a block's solid part. */
    static boolean hits(Solid solid, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        double top = solid.top(bx, by, bz);
        return top > 0 && y - by < top;
    }

    /** The top of what a point is in or just above, or {@code NaN} when it is in the air. */
    static double floor(Solid solid, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        double top = solid.top(bx, by, bz);
        if (top > 0) {
            return by + top;
        }
        return Double.NaN;
    }

    private static Rotation turned(Rotation start, double[] axis, double angle) {
        double half = angle / 2;
        double sin = Math.sin(half);
        return start.then(new Rotation((float) (axis[0] * sin), (float) (axis[1] * sin), (float) (axis[2] * sin),
                (float) Math.cos(half)));
    }

    private static DisplayKeyframe copy(DisplayKeyframe from, long at, double x, double y, double z, Rotation turn) {
        return new DisplayKeyframe(at, (float) x, (float) y, (float) z, turn, from.scaleX(), from.scaleY(),
                from.scaleZ());
    }
}
