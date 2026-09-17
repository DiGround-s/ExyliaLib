package net.exylia.lib.replay;

import net.exylia.lib.npc.NpcPose;
import org.jetbrains.annotations.NotNull;

/**
 * Where somebody was, and how they were standing, on one tick.
 *
 * <p>Read out of a {@link Replay} rather than built: this is what the recorder
 * sampled, decoded back into the numbers it came from.
 *
 * <h2>What the numbers are worth</h2>
 * Position is stored to a thousandth of a block and rotation to about a degree
 * and a half, which is the protocol's own resolution for an entity's yaw. Both
 * are what the server itself used, so a hit that landed in the recording lands
 * in the same place here &mdash; this is not an approximation of the fight, it
 * is the state the fight was decided on.
 *
 * <p>The coordinates are relative to the anchor the recording was made against,
 * not to any world. Adding them to an anchor is what puts a frame somewhere.
 *
 * @param x         blocks east of the anchor
 * @param y         blocks above the anchor
 * @param z         blocks south of the anchor
 * @param yaw       degrees, as Minecraft counts them
 * @param pitch     degrees, negative being up
 * @param health    hearts, to the nearest half
 * @param pose      how they were holding themselves
 * @param sprinting whether they were sprinting
 * @param onGround  whether they were standing on something
 * @param using     whether they were holding an item up: a bow being drawn, a
 *                  shield raised, a gapple being eaten
 * @param present   whether they were in the recording at all on this tick;
 *                  a frame that is not present carries nothing else worth
 *                  reading
 * @since 1.175.0
 */
public record ReplayFrame(double x, double y, double z, float yaw, float pitch,
                          double health, @NotNull NpcPose pose,
                          boolean sprinting, boolean onGround, boolean using,
                          boolean present) {

    /**
     * A tick nobody was there for.
     *
     * <p>What a recording gives back for somebody who had not joined yet, had
     * already left, or was dead at that moment. Only {@link #present()} is
     * worth reading on it.
     */
    public static final ReplayFrame ABSENT =
            new ReplayFrame(0, 0, 0, 0, 0, 0, NpcPose.STANDING, false, false, false, false);
}
