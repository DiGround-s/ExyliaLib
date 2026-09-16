package net.exylia.lib.camera.internal;

import net.exylia.lib.camera.CameraShot;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * A shot turned into the positions the camera actually visits.
 *
 * <h2>Solved once, not followed</h2>
 * Every position in the shot is worked out the moment it starts, from where the
 * subject is standing and which way they are facing at that moment. Nothing is
 * read from the world again while it plays.
 *
 * <p>That is what makes the module cheap and what makes it safe. Cheap, because
 * the driver that sends the packets never touches an entity or a block; safe,
 * because reading either from the timer would be a read of a region this thread
 * does not own, which is the shape of bug that only appears on Folia and only
 * under load. It is also the same decision the ragdoll module made, and for the
 * same reason: a body's whole flight is solved before the first piece moves.
 *
 * <p>What it costs is that the camera does not follow a subject who walks out
 * from under it. That is the right trade for what this is for — a shot is a few
 * seconds long, and whoever is being filmed is standing still for it, either
 * because they are frozen or because the emote they asked for is cancelled the
 * moment they move.
 */
@ApiStatus.Internal
final class CameraPath {

    /**
     * How far short of a wall the camera stops, in blocks.
     *
     * <p>Sitting exactly on the surface puts the client's near plane inside it,
     * which draws the inside of the block across the whole screen.
     */
    private static final double WALL_CLEARANCE = 0.35;

    /** Closer than this the camera is inside the subject, so the wall wins nothing. */
    private static final double MIN_DISTANCE = 0.45;

    private CameraPath() {
        throw new AssertionError("No instances.");
    }

    /** One moment of the shot, in world terms. */
    record Frame(double x, double y, double z, float yaw, float pitch) {
    }

    /**
     * Walks the shot and works out where the camera is at every step.
     *
     * <p>Called from whichever thread asked for the shot, which is the one that
     * owns the subject: it reads the world.
     *
     * @param shot    the choreography
     * @param subject where it is filming, and which way that faces
     * @return one frame per {@link CameraShot#SAMPLE_MILLIS}, starting at zero
     */
    static List<Frame> of(CameraShot shot, Location subject) {
        long duration = shot.durationMillis();
        int count = (int) (duration / CameraShot.SAMPLE_MILLIS) + 1;
        List<Frame> frames = new ArrayList<>(count);
        World world = subject.getWorld();
        double facing = Math.toRadians(subject.getYaw());
        for (int step = 0; step < count; step++) {
            double[] where = shot.at(Math.min(duration, step * CameraShot.SAMPLE_MILLIS));
            frames.add(frame(world, subject, facing, where));
        }
        return frames;
    }

    /**
     * Where the camera is, and which way it looks, for one moment of the shot.
     *
     * <p>The angle is measured from the subject's own back, so {@code yaw=0} is
     * over their shoulder however they happen to be standing. Minecraft's yaw
     * runs clockwise from south, which is why the direction that walks away from
     * a facing of {@code a} is {@code (sin a, -cos a)} rather than its negative.
     */
    private static Frame frame(World world, Location subject, double facing, double[] where) {
        // In the order CameraShot.at documents: distance, yaw, pitch, height.
        double wanted = where[0];
        double angle = facing + Math.toRadians(where[1]);
        double pitch = Math.toRadians(where[2]);
        double aimX = subject.getX();
        double aimY = subject.getY() + where[3];
        double aimZ = subject.getZ();

        Vector out = new Vector(Math.sin(angle) * Math.cos(pitch),
                Math.sin(pitch),
                -Math.cos(angle) * Math.cos(pitch));
        double distance = clear(world, aimX, aimY, aimZ, out, wanted);

        double x = aimX + out.getX() * distance;
        double y = aimY + out.getY() * distance;
        double z = aimZ + out.getZ() * distance;
        // Straight back down the ray it was placed along. Taken from the vector
        // rather than from the two points, so a camera pulled in to a wall still
        // aims at the subject and not at the block it stopped against.
        float lookYaw = (float) Math.toDegrees(Math.atan2(out.getX(), -out.getZ()));
        float lookPitch = (float) Math.toDegrees(Math.asin(out.getY()));
        return new Frame(x, y, z, lookYaw, lookPitch);
    }

    /**
     * How far the camera may go before it is inside something.
     *
     * <p>Passable blocks are ignored: a camera that jumps to the subject's nose
     * because it clipped a flower is worse than one that sees through the
     * flower.
     */
    private static double clear(World world, double x, double y, double z,
                                Vector direction, double wanted) {
        if (world == null) {
            return wanted;
        }
        try {
            RayTraceResult hit = world.rayTraceBlocks(new Location(world, x, y, z), direction,
                    wanted, FluidCollisionMode.NEVER, true);
            if (hit == null) {
                return wanted;
            }
            double reached = hit.getHitPosition().distance(new Vector(x, y, z));
            return Math.max(MIN_DISTANCE, Math.min(wanted, reached - WALL_CLEARANCE));
        } catch (Throwable unreadable) {
            // An unloaded chunk, or a thread that does not own this region. The
            // shot is worth more than the clearance: a camera in a wall for a
            // frame is survivable, a cinematic that refuses to play is not.
            return wanted;
        }
    }
}
