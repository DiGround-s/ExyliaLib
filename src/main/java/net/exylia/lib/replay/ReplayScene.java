package net.exylia.lib.replay;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One continuous stretch of a recording, in one place.
 *
 * <p>A duel is a single scene: one arena, one anchor. A recording that follows
 * somebody around is cut into several when they are somewhere else in an
 * instant &mdash; a teleport across the map, a portal, a change of world &mdash;
 * because the frames on either side of that cut belong to two places that have
 * nothing to do with each other. The playback treats a new scene the way a
 * broadcast treats a camera cut.
 *
 * <p>Every position in a scene's ticks is relative to its anchor, and the anchor
 * is kept as it was where the recording was made, so a recording can still be
 * played back in the very place it happened.
 *
 * @param fromTick the first tick that belongs to it; it lasts until the next
 *                 scene starts, or the recording ends
 * @param world    the name of the world it was recorded in, or {@code null}
 *                 when the recording does not say
 * @param x        the anchor, in that world
 * @param y        the anchor, in that world
 * @param z        the anchor, in that world
 * @since 1.241.0
 */
public record ReplayScene(int fromTick, @Nullable String world, double x, double y, double z) {

    /**
     * Where this scene was recorded, if that world is loaded on this server.
     *
     * @return the anchor in its own world, or {@code null}
     */
    public @Nullable Location origin() {
        if (world == null) return null;
        World loaded = Bukkit.getWorld(world);
        return loaded == null ? null : new Location(loaded, x, y, z);
    }

    /** The scene a recording made against one anchor has. */
    public static @NotNull ReplayScene of(@NotNull Location anchor) {
        return new ReplayScene(0, anchor.getWorld() == null ? null : anchor.getWorld().getName(),
                anchor.getX(), anchor.getY(), anchor.getZ());
    }
}
