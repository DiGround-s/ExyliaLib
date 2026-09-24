package net.exylia.lib.util.mob.internal;

import net.exylia.lib.util.mob.MobSkill;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Where a cast lands, as numbers: no world, no entity, so every rule is tested
 * without a server.
 *
 * <p>What the aim needs from the moment the wind-up starts is taken then, in a
 * {@link Lock}: the mob's position and facing, and where its target stood. The
 * players are looked up at impact against those, which is what lets somebody
 * step out of a cone, a line or a circle on the ground.
 */
public final class MobAim {

    /** The reach of an aim that finds its own players when the skill has no radius. */
    public static final double DEFAULT_REACH = 16;

    /** A cone's angle when the skill does not say, in degrees. */
    public static final double DEFAULT_CONE = 60;

    /** A line's width when the skill does not say, in blocks. */
    public static final double DEFAULT_WIDTH = 1.6;

    /** Who stands on a {@link MobSkill.Aim#GROUND} spot, for a skill with no area of its own. */
    public static final double SPOT = 1.5;

    /** The farthest an aim may land from the mob: its region's, on Folia. */
    public static final double MAX_DISTANCE = 24;

    /** How far above or below a line or a cone a player may stand and still be in it. */
    private static final double HEIGHT = 2.5;

    private MobAim() {
        throw new AssertionError("No instances.");
    }

    /**
     * What an aim keeps from the start of the wind-up.
     *
     * @param origin where the mob stood
     * @param yaw    where it faced, in degrees, Minecraft's convention (0 is +z)
     * @param point  where its target stood, capped at {@link #MAX_DISTANCE}; {@code null}
     *               when it had no target
     */
    public record Lock(@NotNull Location origin, float yaw, @Nullable Location point) {
    }

    /**
     * Takes what the aim needs, as the wind-up starts.
     *
     * @param origin the mob's location
     * @param target where its target stands, or {@code null}
     * @return the lock; the yaw faces the target when there is one
     */
    public static @NotNull Lock lock(@NotNull Location origin, @Nullable Location target) {
        Location from = origin.clone();
        if (target == null) return new Lock(from, from.getYaw(), null);
        Location point = cap(from, target.clone());
        return new Lock(from, yaw(from.toVector(), point.toVector(), from.getYaw()), point);
    }

    /** A point pulled back along the way from the origin to at most {@link #MAX_DISTANCE}. */
    static Location cap(Location origin, Location point) {
        Vector offset = point.toVector().subtract(origin.toVector());
        if (offset.lengthSquared() <= MAX_DISTANCE * MAX_DISTANCE) return point;
        Vector capped = origin.toVector().add(offset.normalize().multiply(MAX_DISTANCE));
        return new Location(point.getWorld(), capped.getX(), capped.getY(), capped.getZ());
    }

    /**
     * The yaw that looks from one point at another, or {@code fallback} when they
     * are level with each other on top.
     */
    public static float yaw(@NotNull Vector from, @NotNull Vector to, float fallback) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (dx * dx + dz * dz < 1.0E-6) return fallback;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** The flat direction a yaw faces. */
    public static @NotNull Vector facing(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vector(-Math.sin(radians), 0, Math.cos(radians));
    }

    /**
     * Whether a point is inside a cone lying on the ground.
     *
     * @param apex    the tip
     * @param yaw     where it opens towards
     * @param reach   how long it is
     * @param degrees how wide it opens, in total; half each side
     * @param point   the point
     * @return whether it is inside
     */
    public static boolean inCone(@NotNull Vector apex, float yaw, double reach, double degrees, @NotNull Vector point) {
        Vector offset = point.clone().subtract(apex);
        if (Math.abs(offset.getY()) > HEIGHT) return false;
        offset.setY(0);
        double distance = offset.length();
        if (distance > reach) return false;
        // Standing on the tip is standing in it.
        if (distance < 1.0E-3) return true;
        double cos = offset.multiply(1 / distance).dot(facing(yaw));
        return cos >= Math.cos(Math.toRadians(Math.min(360, degrees) / 2));
    }

    /**
     * Whether a point is on a band lying on the ground between two points.
     *
     * @param from  one end
     * @param to    the other end
     * @param width the band's width; half each side
     * @param point the point
     * @return whether it is on it
     */
    public static boolean onLine(@NotNull Vector from, @NotNull Vector to, double width, @NotNull Vector point) {
        double t;
        double ax = to.getX() - from.getX();
        double az = to.getZ() - from.getZ();
        double length = ax * ax + az * az;
        double px = point.getX() - from.getX();
        double pz = point.getZ() - from.getZ();
        t = length < 1.0E-9 ? 0 : Math.max(0, Math.min(1, (px * ax + pz * az) / length));
        double dx = px - ax * t;
        double dz = pz - az * t;
        double y = from.getY() + (to.getY() - from.getY()) * t;
        if (Math.abs(point.getY() - y) > HEIGHT) return false;
        return dx * dx + dz * dz <= (width / 2) * (width / 2);
    }

    /** The far end of a line: {@code reach} blocks from the origin, the way the lock faces. */
    public static @NotNull Location lineEnd(@NotNull Lock lock, double reach) {
        return lock.origin().clone().add(facing(lock.yaw()).multiply(reach));
    }

    /** How far an aim that finds its own players reaches: the skill's radius, else {@link #DEFAULT_REACH}. */
    public static double reach(@NotNull MobSkill skill) {
        return skill.radius() > 0 ? skill.radius() : DEFAULT_REACH;
    }

    /** A cone's full angle or a line's width, the skill's own or the default. */
    public static double spread(@NotNull MobSkill skill) {
        double spread = skill.cast().spread();
        if (spread > 0) return spread;
        return skill.cast().aim() == MobSkill.Aim.CONE ? DEFAULT_CONE : DEFAULT_WIDTH;
    }

    /** Whether the aim looks for its own players around the mob. */
    public static boolean findsPlayers(@NotNull MobSkill.Aim aim) {
        return switch (aim) {
            case NEAREST, FARTHEST, RANDOM, ALL, CONE, LINE -> true;
            default -> false;
        };
    }
}
