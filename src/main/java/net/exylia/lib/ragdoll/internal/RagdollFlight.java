package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.display.Rotation;
import net.exylia.lib.ragdoll.RagdollBurst;
import net.exylia.lib.ragdoll.RagdollPart;
import org.jetbrains.annotations.ApiStatus;

import java.util.random.RandomGenerator;

/**
 * Where one piece of a body is, at every moment a pose is sent.
 *
 * <h2>Solved once, drawn by the client</h2>
 * The whole flight is worked out the moment the body bursts and handed over as
 * a couple of dozen poses with times on them. Nothing is ticked afterwards and
 * nothing is ever teleported: the client is told where a piece will be and
 * draws every frame in between at its own frame rate, so the motion is as
 * smooth as the player's monitor on a server that is not smooth at all.
 *
 * <h2>Deliberately not physics</h2>
 * A piece is a point with a bounce, and the only thing in the world it can hit
 * is the floor its owner was standing on. A piece that passes through a wall
 * for a fifth of a second, once, is a fair price for one that never sticks
 * inside a staircase and never needs the world read off the main thread.
 *
 * <p>Free of Bukkit on purpose, so the part of this that can be wrong quietly
 * &mdash; a piece that sinks through the floor, a spin the client takes the
 * long way round &mdash; is the part that can be asserted.
 */
@ApiStatus.Internal
public final class RagdollFlight {

    /** How often a pose is sent while a piece is in the air. */
    public static final long POSE_MS = 100L;

    /** The step the flight is integrated at, well under the pose interval. */
    private static final double STEP_SECONDS = 0.01;

    /** How high a piece's centre rests above the floor it landed on. */
    private static final double REST_HEIGHT = 0.08;

    /** What a landing takes out of a piece's sideways speed. */
    private static final double FLOOR_FRICTION = 0.55;

    /** Below this, a bounce is a rattle nobody sees, so the piece is done. */
    private static final double SETTLE_SPEED = 1.2;

    /**
     * The fastest a piece may be told to turn.
     *
     * <p>The client takes the shortest arc between two poses, so a pose more
     * than half a turn from the last one spins backwards. At a pose every tenth
     * of a second and three axes turning at once, this is the rate that keeps
     * the worst case comfortably inside that limit.
     */
    public static final double MAX_TURNS_PER_SECOND = 2.4;

    /** Where a head is thrown compared with everything else. */
    private static final double HEAD_LIFT_SPEED = 1.35;

    private RagdollFlight() {
    }

    /**
     * The whole flight of one part: where it is and how it is turned, at every
     * moment a pose is sent.
     */
    public record Flight(long[] times, double[] x, double[] y, double[] z,
                          Rotation[] rotations) {
    }

    /**
     * Solves one part's flight.
     *
     * <p>It stands where it stands until the body bursts, leaves outwards and
     * upwards, falls, bounces off the floor it died on and comes to rest.
     */
    public static Flight solve(RagdollPart part, RagdollBurst burst, double scale,
                               Rotation facing, RandomGenerator random) {
        float[] standing = facing.apply(new float[]{
                part.blockOffsetX() * (float) scale,
                part.blockCentreY() * (float) scale,
                0f});

        // Outwards means away from the middle of the body, which for the head
        // and the chest is no direction at all — so they get one.
        double angle = standing[0] == 0f && standing[2] == 0f
                ? random.nextDouble(Math.PI * 2)
                : Math.atan2(standing[2], standing[0]);
        angle += random.nextDouble(-1, 1) * burst.spread();
        double vary = 1 + random.nextDouble(-1, 1) * burst.spread();
        double speed = burst.speed() * vary;
        double lift = burst.up() * (1 + random.nextDouble(-0.5, 0.5) * burst.spread())
                * (part == RagdollPart.HEAD ? HEAD_LIFT_SPEED : 1.0);

        double[] velocity = {Math.cos(angle) * speed, lift, Math.sin(angle) * speed};
        double[] position = {standing[0], standing[1], standing[2]};

        double turns = Math.clamp(burst.spin() * (1 + random.nextDouble(-1, 1) * burst.spread()),
                -MAX_TURNS_PER_SECOND, MAX_TURNS_PER_SECOND);
        double[] axis = {random.nextDouble(-1, 1), random.nextDouble(-1, 1),
                random.nextDouble(-1, 1)};
        double axisLength = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
        if (axisLength < 1e-4) {
            axis = new double[]{0, 1, 0};
            axisLength = 1;
        }
        for (int index = 0; index < 3; index++) {
            axis[index] /= axisLength;
        }

        long life = burst.lifeMillis();
        long intact = burst.intactMillis();
        int poses = (int) ((life - intact) / POSE_MS) + 2;
        long[] times = new long[poses];
        double[] x = new double[poses];
        double[] y = new double[poses];
        double[] z = new double[poses];
        Rotation[] rotations = new Rotation[poses];

        // Standing whole, at nothing, for as long as the file says.
        times[0] = 0L;
        x[0] = position[0];
        y[0] = position[1];
        z[0] = position[2];
        rotations[0] = Rotation.NONE;

        double restHeight = REST_HEIGHT * scale;
        double flown = 0;
        Rotation resting = null;
        for (int index = 1; index < poses; index++) {
            long at = Math.min(life, intact + (long) (index - 1) * POSE_MS);
            double target = (at - intact) / 1000.0;
            while (flown < target - 1e-9) {
                double step = Math.min(STEP_SECONDS, target - flown);
                velocity[1] -= burst.gravity() * step;
                position[0] += velocity[0] * step;
                position[1] += velocity[1] * step;
                position[2] += velocity[2] * step;
                if (position[1] <= restHeight && velocity[1] < 0) {
                    position[1] = restHeight;
                    if (-velocity[1] < SETTLE_SPEED) {
                        velocity[0] = 0;
                        velocity[1] = 0;
                        velocity[2] = 0;
                        if (resting == null && burst.settle()) {
                            resting = tumble(axis, turns, flown);
                        }
                    } else {
                        velocity[1] = -velocity[1] * burst.bounce();
                        velocity[0] *= FLOOR_FRICTION;
                        velocity[2] *= FLOOR_FRICTION;
                    }
                }
                flown += step;
            }
            times[index] = at;
            x[index] = position[0];
            y[index] = position[1];
            z[index] = position[2];
            rotations[index] = resting != null ? resting : tumble(axis, turns, flown);
        }
        return new Flight(times, x, y, z, rotations);
    }

    /**
     * How far a piece has turned by now.
     *
     * <p>Three axes at once rather than one, because a thing thrown never turns
     * about a single axis and the eye knows it. Built by composing the three
     * the module already has, so no keyframe is ever more than half a turn from
     * the last one and the client never takes the long way round.
     */
    private static Rotation tumble(double[] axis, double turns, double seconds) {
        double angle = turns * Math.PI * 2 * seconds;
        return Rotation.around(Rotation.Axis.X, angle * axis[0])
                .then(Rotation.around(Rotation.Axis.Y, angle * axis[1]))
                .then(Rotation.around(Rotation.Axis.Z, angle * axis[2]));
    }

}
