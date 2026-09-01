package net.exylia.lib.packet;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Map;

/**
 * Blocks outlined for one player, seen through everything in the way.
 *
 * <p>What a fake block cannot do: terrain still hides it. Each position gets
 * an invisible display entity with the glow flag, which the client draws as a
 * coloured outline on top of the world — the ore behind a wall is visible
 * from the other side of it.
 *
 * <p>Nothing is placed and nothing is spawned server-side: the entities exist
 * only in the viewer's client, are not ticked, not saved, and have no hitbox.
 * The module remembers what each viewer was shown so {@link #clear} can take
 * it away, and forgets it when they leave or change world.
 *
 * @since 1.78.0
 */
public interface GlowingBlocks {

    /**
     * Outlines blocks for one player.
     *
     * <p>A position already outlined for this viewer keeps the outline it has;
     * clear it first to change its colour.
     *
     * @param viewer the player
     * @param blocks the outline colour for each position; positions in a world
     *               the viewer is not in are ignored
     */
    void show(@NotNull Player viewer, @NotNull Map<Location, TextColor> blocks);

    /**
     * Takes away every outline shown to this viewer.
     *
     * @param viewer the player
     */
    void clear(@NotNull Player viewer);

    /**
     * Takes away some outlines.
     *
     * @param viewer    the player
     * @param positions positions to clear; ones never outlined are ignored
     */
    void clear(@NotNull Player viewer, @NotNull Collection<Location> positions);
}
