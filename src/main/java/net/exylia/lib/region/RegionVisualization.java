package net.exylia.lib.region;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Cancellable view of one player's region outline.
 *
 * <p>The handle retains only stable identifiers and never retains a Bukkit player.
 * Closing it is idempotent and may be done from any thread.
 *
 * @since 1.23.0
 */
public interface RegionVisualization extends AutoCloseable {

    /**
     * Returns the UUID of the player receiving this outline.
     *
     * @return player UUID
     */
    @NotNull UUID playerId();

    /**
     * Returns the region resolved for every rendered frame.
     *
     * @return stable region identifier
     */
    @NotNull RegionId regionId();

    /**
     * Returns whether this visualization can still render future frames.
     *
     * @return {@code true} until closed, expired, or stopped by lifecycle conditions
     */
    boolean active();

    /** Stops this visualization and cancels its timer. */
    @Override
    void close();
}
