package net.exylia.lib.model.internal;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The geometry of a model's own boxes: whether a player's hit could reach one,
 * and whether a projectile flew through one this tick.
 *
 * <p>Pure: Bukkit's bounding boxes and vectors are plain maths, so all of it
 * can be asserted without a server.
 */
@ApiStatus.Internal
final class Boxes {

    /**
     * How far past a player's interaction range a hit on a box is still believed:
     * the box moves between the client's frame and the server's tick.
     */
    static final double SLACK = 0.75;

    private Boxes() {
    }

    /** A box standing on its bottom centre. */
    static BoundingBox box(double x, double y, double z, double width, double height) {
        double half = width / 2;
        return new BoundingBox(x - half, y, z - half, x + half, y + height, z + half);
    }

    /** The distance from a point to the nearest part of a box; {@code 0} inside it. */
    static double distance(BoundingBox box, double x, double y, double z) {
        double dx = Math.max(Math.max(box.getMinX() - x, 0), x - box.getMaxX());
        double dy = Math.max(Math.max(box.getMinY() - y, 0), y - box.getMaxY());
        double dz = Math.max(Math.max(box.getMinZ() - z, 0), z - box.getMaxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The point of a box nearest another point. */
    static Vector nearest(BoundingBox box, double x, double y, double z) {
        return new Vector(Math.clamp(x, box.getMinX(), box.getMaxX()), Math.clamp(y, box.getMinY(), box.getMaxY()),
                Math.clamp(z, box.getMinZ(), box.getMaxZ()));
    }

    /**
     * Whether a hit from an eye could reach a box: within the player's
     * interaction range, and a little slack for the box having moved.
     */
    static boolean reaches(BoundingBox box, Vector eye, double range) {
        return distance(box, eye.getX(), eye.getY(), eye.getZ()) <= range + SLACK;
    }

    /**
     * Where a projectile moving this tick from {@code from} by {@code velocity}
     * enters a box, or {@code null} when it does not.
     */
    static @Nullable Vector crossed(BoundingBox box, Vector from, Vector velocity) {
        double length = velocity.length();
        if (box.contains(from)) {
            return from.clone();
        }
        if (length < 1.0E-6) {
            return null;
        }
        RayTraceResult hit = box.rayTrace(from, velocity.clone().multiply(1 / length), length);
        return hit == null ? null : hit.getHitPosition();
    }
}
