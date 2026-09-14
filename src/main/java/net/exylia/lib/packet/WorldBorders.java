package net.exylia.lib.packet;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * World borders some players see and the world does not have.
 *
 * <p>A border here is a packet to each of its viewers and nothing else: the
 * world keeps its own border, every other player keeps seeing that one, and
 * any number of borders can share a world. One arena, one match, one zone —
 * each with its own edge, shrinking on its own clock.
 *
 * <pre>{@code
 * VirtualBorder zone = Packets.of(this).worldBorders().create(arena.center(), 200);
 * match.players().forEach(zone::show);
 *
 * zone.size(20, Duration.ofMinutes(2));   // closes in, drawn by each client
 * // ...
 * zone.remove();                          // everyone sees the world's border again
 * }</pre>
 *
 * <p>See {@link VirtualBorder} for what a border does on the server and what
 * it does not.
 *
 * @since 1.162.0
 */
public interface WorldBorders {

    /**
     * Creates a border nobody sees yet.
     *
     * <p>It warns and hurts like the vanilla border until told otherwise: a
     * red screen five blocks or fifteen seconds from the edge, and 0.2 hearts
     * of damage per block for players more than five blocks outside.
     *
     * @param center its centre; only the world, X and Z are read
     * @param size   its width in blocks, from 1 to 59,999,968
     * @return the border
     * @throws IllegalArgumentException when the centre has no world or the size is out of range
     */
    @NotNull VirtualBorder create(@NotNull Location center, double size);

    /**
     * Returns the border a player sees, whichever plugin made it.
     *
     * @param viewer the player
     * @return the border, or {@code null} while they see their world's own
     */
    @Nullable VirtualBorder seenBy(@NotNull Player viewer);
}
