package net.exylia.lib.guide;

import org.jetbrains.annotations.Nullable;

/**
 * The way a player can walk to a place, from {@link PluginGuides#path}.
 *
 * @param route       the walkable route from where they stood, at foot height, ready for
 *                    {@link PluginGuides#trail}; {@code null} when no step of it was found
 * @param reached     whether the route ends at the target; {@code false} means it ends at the
 *                    nearest place the search could walk to
 * @param unreachable whether the target is known not to be reachable from where the player stood
 *                    in the terrain searched (walled off, across lava, up a cliff), as opposed to
 *                    the search stopping at its budget, its range or an unloaded chunk
 * @since 1.396.0
 */
public record GuidePath(@Nullable Route route, boolean reached, boolean unreachable) {

    /** Nothing was searched: no PacketEvents-free world access, another world, or too far. */
    public static final GuidePath NONE = new GuidePath(null, false, false);
}
