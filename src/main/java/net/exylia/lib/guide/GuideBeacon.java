package net.exylia.lib.guide;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/**
 * A tall beam of light over a place, with a label saying how far it is.
 *
 * <pre>{@code
 * GuideBeacon beacon = guides.beacon(player, smithy, BeaconStyle.of(Material.CYAN_STAINED_GLASS
 *         .createBlockData(), 0x59A4FF), metres -> Text.of("{info}" + Math.round(metres) + " m").build());
 * beacon.pulse();          // when the step starts, or when a hint fires
 * }</pre>
 *
 * @since 1.391.0
 */
public interface GuideBeacon extends GuideHandle {

    /** What the beacon is drawing at the moment. */
    enum State {
        /** The beam stands at the target, which is within the player's view. */
        AT_TARGET,
        /** The target is too far to draw; a smaller marker stands between the player and it. */
        MARKER,
        /** The target is in another world; nothing is drawn. */
        ELSEWHERE
    }

    /**
     * Moves it to another place, in place.
     *
     * @param target where it stands now
     */
    void retarget(@NotNull Location target);

    /**
     * Draws attention to it: the beam swells for a moment and settles.
     *
     * <p>Two packets. Safe from any thread.
     */
    void pulse();

    /** The place it marks. */
    @NotNull Location target();

    /** What it is drawing now. */
    @NotNull State state();

    /** How far the player is from the target, in blocks; {@code NaN} in another world. */
    double distance();
}
