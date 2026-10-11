package net.exylia.lib.api.crates;

import net.exylia.lib.api.ExyliaAPI;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Reading and paying crate keys in ExyliaCrates.
 *
 * <pre>{@code
 * ExyliaAPI.get(CratesService.class).ifPresent(crates -> crates.giveKeys(player, "vote", 1));
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaCrates enables. Reach it through {@link ExyliaAPI#get(Class)}, and treat
 * an empty result as "this server has no crates" rather than as a failure.
 *
 * <p>Keys are a balance kept per player and crate, the same on every server of
 * a network sharing ExyliaCrates' database. Key items carried in an inventory
 * are items like any other and are not part of it.
 *
 * @since 1.10.0
 */
public interface CratesService {

    /**
     * Whether a crate exists.
     *
     * @param crateId the crate id
     * @return {@code true} for a crate this server knows
     */
    boolean exists(@NotNull String crateId);

    /**
     * How many keys for a crate a player holds in their balance.
     *
     * <p>Read from memory: a balance is held per online player, so this answers
     * {@code 0} for somebody offline rather than reading the database on the
     * calling thread.
     *
     * @param player  the player
     * @param crateId the crate id
     * @return the keys in their balance, {@code 0} when they have none, are
     *         offline or the crate does not exist
     */
    int keys(@NotNull UUID player, @NotNull String crateId);

    /**
     * Adds keys for a crate to a player's balance.
     *
     * <p>For a vote listener, a store or an event payout. Works on a player who
     * is offline or on another server: the amount is added in the database
     * itself, so it never overwrites keys granted elsewhere meanwhile. The
     * player is told nothing.
     *
     * @param player  the player
     * @param crateId the crate id
     * @param amount  how many keys to add, at least {@code 1}
     * @return {@code true} when the crate exists and the keys are on their way,
     *         {@code false} for an unknown crate or a non-positive amount
     */
    boolean giveKeys(@NotNull UUID player, @NotNull String crateId, int amount);
}
