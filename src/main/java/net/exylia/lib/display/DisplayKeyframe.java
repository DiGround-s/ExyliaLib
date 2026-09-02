package net.exylia.lib.display;

import org.jetbrains.annotations.NotNull;

/**
 * One pose a display passes through, and when.
 *
 * <p>The client is told the pose and how long it has to reach it, and draws
 * every frame in between itself. That is the whole reason this module exists:
 * a sword crossing four blocks over a second is two packets, not twenty, and it
 * moves at the viewer's frame rate rather than the server's tick rate.
 *
 * <p>Offsets are relative to where the display was spawned, in blocks. Scale is
 * a multiplier of the model's normal size.
 *
 * @param atMillis how long after the display appeared this pose is reached
 * @param x        offset east of the spawn point
 * @param y        offset above the spawn point
 * @param z        offset south of the spawn point
 * @param rotation how the model is turned
 * @param scaleX   width multiplier
 * @param scaleY   height multiplier
 * @param scaleZ   depth multiplier
 * @since 1.85.0
 */
public record DisplayKeyframe(long atMillis, float x, float y, float z,
                              @NotNull Rotation rotation,
                              float scaleX, float scaleY, float scaleZ) {

    /**
     * The same pose with an extra rotation applied first.
     *
     * <p>What a ring of blades needs: every blade shares one animation and
     * differs only by the direction it faces, so the animation is built once
     * and turned per point rather than rebuilt per point.
     *
     * @param first the rotation applied before this pose's own
     * @return the turned pose
     */
    public @NotNull DisplayKeyframe turnedBy(@NotNull Rotation first) {
        return new DisplayKeyframe(atMillis, x, y, z, first.then(rotation), scaleX, scaleY, scaleZ);
    }

    /**
     * The same pose moved.
     *
     * <p>Used when a shape's point becomes the display's own offset, so the
     * whole ring can share one motion and still land in twelve places.
     *
     * @param dx east
     * @param dy up
     * @param dz south
     * @return the moved pose
     */
    public @NotNull DisplayKeyframe movedBy(double dx, double dy, double dz) {
        return new DisplayKeyframe(atMillis, (float) (x + dx), (float) (y + dy), (float) (z + dz),
                rotation, scaleX, scaleY, scaleZ);
    }
}
