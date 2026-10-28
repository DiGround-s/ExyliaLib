package net.exylia.lib.guide;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/**
 * A marker on the player's locator bar (Minecraft 1.21.6 and newer) and on
 * Lunar's or Feather's minimap, whichever they have.
 *
 * @since 1.391.0
 */
public interface GuideWaypoint extends GuideHandle {

    /**
     * Points it somewhere else, in place.
     *
     * @param target the new place
     */
    void move(@NotNull Location target);

    /**
     * Whether anything at all draws it for this player: a locator bar that
     * understands custom waypoints, or a modded client.
     *
     * <p>{@code false} on an older client with no Lunar or Feather, or a server
     * older than 1.21.6 with no client integration. The handle still works;
     * nothing is drawn.
     */
    boolean isDrawn();
}
