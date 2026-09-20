package net.exylia.lib.api.events.custom;

import org.bukkit.Location;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One place an admin marked on an arena, as the game reads it back.
 *
 * <p>A {@link MinigameMarkerKind#POINT} marker has a {@link #center()} and the
 * box a player has to stand in to count as being at it; an
 * {@link MinigameMarkerKind#AREA} marker has both as well, its centre being the
 * middle of the box. Both are answered the same way so a handler that only
 * wants to know "is the player at the goal" never has to ask which kind it was.
 *
 * @param role   the role its marker was declared under
 * @param center somewhere to stand at it
 * @param area   the box it covers
 * @since 1.7.0
 */
public record MinigamePlace(
        @NotNull String role,
        @NotNull Location center,
        @NotNull BoundingBox area) {

    /**
     * Whether a place is inside this one.
     *
     * @param location where to test; {@code null} is nowhere
     * @return {@code true} when it is inside the box, and in the same world
     */
    public boolean contains(@Nullable Location location) {
        if (location == null || location.getWorld() == null) return false;
        if (!location.getWorld().equals(center.getWorld())) return false;
        return area.contains(location.toVector());
    }
}
