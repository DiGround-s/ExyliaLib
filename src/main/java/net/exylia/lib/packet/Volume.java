package net.exylia.lib.packet;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/**
 * An upright box turned round the vertical axis: a square {@code reach} to each side of a middle,
 * turned by a yaw, from {@code bottom} to {@code top}. The shape of an
 * {@link net.exylia.lib.stage.Enclosure}'s room and of the square {@link Movement#confine} keeps a
 * player in. Pure maths, safe from any thread.
 *
 * @param x      the middle, across
 * @param z      the middle, deep
 * @param yaw    the turn, in degrees, as a {@link Location}'s yaw
 * @param reach  how far each side is from the middle, in blocks
 * @param bottom the lowest height inside
 * @param top    the highest height inside
 * @since 1.420.0
 */
public record Volume(double x, double z, float yaw, double reach, double bottom, double top) {

    /** A box round {@code centre}, from {@code below} under it to {@code above} over it. */
    public static @NotNull Volume around(@NotNull Location centre, double reach, double below, double above) {
        return new Volume(centre.getX(), centre.getZ(), centre.getYaw(), reach, centre.getY() - below, centre.getY() + above);
    }

    /** Whether a point stands inside the square, at any height. */
    public boolean holdsColumn(double px, double pz) {
        return across(px, pz) <= reach && deep(px, pz) <= reach;
    }

    /** Whether a point stands inside the square, at any height, the square grown by {@code margin} on each side. */
    public boolean holdsColumn(double px, double pz, double margin) {
        return across(px, pz) <= reach + margin && deep(px, pz) <= reach + margin;
    }

    /** Whether a point is inside. */
    public boolean holds(double px, double py, double pz) {
        return py >= bottom && py <= top && holdsColumn(px, pz);
    }

    /**
     * Whether a box (an entity's or a block's) reaches into this one: their heights overlap and its
     * middle stands within the square grown by half its width, which holds for every turn of the square.
     */
    public boolean touches(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (maxY <= bottom || minY >= top) return false;
        double half = Math.max(maxX - minX, maxZ - minZ) / 2;
        // Half the box's diagonal covers it whichever way the square is turned.
        return holdsColumn((minX + maxX) / 2, (minZ + maxZ) / 2, half * Math.sqrt(2));
    }

    /** How far a point is from the middle along the square's own across axis, unsigned. */
    private double across(double px, double pz) {
        double yawRad = Math.toRadians(yaw);
        return Math.abs((px - x) * Math.cos(yawRad) + (pz - z) * Math.sin(yawRad));
    }

    /** How far along its deep axis, unsigned. */
    private double deep(double px, double pz) {
        double yawRad = Math.toRadians(yaw);
        return Math.abs((pz - z) * Math.cos(yawRad) - (px - x) * Math.sin(yawRad));
    }
}
