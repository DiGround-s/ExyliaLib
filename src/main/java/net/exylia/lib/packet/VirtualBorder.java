package net.exylia.lib.packet;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Collection;

/**
 * One world border, drawn for the players it is shown to.
 *
 * <p>Every change reaches its viewers as a single packet. A resize is a start,
 * an end and a duration: each client animates it on its own, so a border
 * closing in over ten minutes costs one packet, not one per tick.
 *
 * <h2>What the server does</h2>
 * The client stops its player at the edge, but a modified client does not, and
 * an ender pearl, a vehicle or a plugin teleport never asks. So the library
 * hurts a viewer standing outside, the way the vanilla border does — twice a
 * second, {@link #damage per block} past a buffer, with the vanilla
 * "outside the world border" damage type. Set the damage to zero to leave the
 * consequences to the plugin, which can ask {@link #contains}.
 *
 * <h2>Limits</h2>
 * <ul>
 *   <li>A player sees one border. Showing a second one, from any plugin,
 *       replaces the first.
 *   <li>A border belongs to one world. A viewer in another world sees that
 *       world's own border, and this one again when they come back.
 *   <li>Square, like every world border. A circle is particles or fake blocks.
 *   <li>The server's collision is still the world's border: pushing, blocks
 *       placed and portals follow that one.
 * </ul>
 *
 * <p>Without PacketEvents a border can be created and shaped, but shows to
 * nobody and hurts nobody. When its plugin is disabled every border it made is
 * removed. Every method is safe from any thread.
 *
 * @since 1.162.0
 */
public interface VirtualBorder {

    /**
     * Returns the world this border is in.
     *
     * @return the world
     */
    @NotNull World world();

    /**
     * Returns the centre, at Y 0.
     *
     * @return a copy of the centre
     */
    @NotNull Location center();

    /**
     * Moves the centre. A resize in progress keeps going around the new one.
     *
     * @param x the new centre's X
     * @param z the new centre's Z
     */
    void center(double x, double z);

    /**
     * Returns the width right now, part-way through a resize included.
     *
     * @return the width in blocks
     */
    double size();

    /**
     * Sets the width at once.
     *
     * @param size the width in blocks, from 1 to 59,999,968
     * @throws IllegalArgumentException when the size is out of range
     */
    void size(double size);

    /**
     * Grows or shrinks to a width over time, starting from where it is now.
     *
     * @param size the width to reach, from 1 to 59,999,968
     * @param over how long it takes; zero is at once
     * @throws IllegalArgumentException when the size is out of range or the duration negative
     */
    void size(double size, @NotNull Duration over);

    /**
     * When a viewer's screen starts turning red.
     *
     * @param blocks  how close to the edge, in blocks
     * @param seconds how soon a closing edge reaches them, in seconds
     * @throws IllegalArgumentException when either is negative
     */
    void warning(int blocks, int seconds);

    /**
     * How the server hurts a viewer outside.
     *
     * <p>Beyond {@code buffer} blocks outside, a viewer takes
     * {@code perBlock} damage for every further block, at least one, twice a
     * second.
     *
     * @param perBlock damage per block past the buffer; zero hurts nobody
     * @param buffer   blocks outside the edge that are still safe
     * @throws IllegalArgumentException when either is negative
     */
    void damage(double perBlock, double buffer);

    /**
     * Returns whether a position is inside, as the border stands right now.
     *
     * @param at the position
     * @return {@code false} outside, or in another world
     */
    boolean contains(@NotNull Location at);

    /**
     * Shows this border to a player, replacing whatever border they saw.
     *
     * <p>Does nothing once the border is removed.
     *
     * @param viewer the player
     */
    void show(@NotNull Player viewer);

    /**
     * Stops showing this border; the player sees their world's own again.
     *
     * <p>Does nothing when they see another border.
     *
     * @param viewer the player
     */
    void hide(@NotNull Player viewer);

    /**
     * Returns the online players this border is shown to.
     *
     * @return a snapshot
     */
    @NotNull Collection<Player> viewers();

    /** Hides this border from everyone and retires it. */
    void remove();
}
