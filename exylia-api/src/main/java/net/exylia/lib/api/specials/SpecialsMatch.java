package net.exylia.lib.api.specials;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;

/**
 * A one-versus-one duel, as it was when you asked.
 *
 * <p>A snapshot. The plugin mutates its own match object as the duel runs, so
 * {@link #state()} and {@link #durationMillis()} are the values at the moment
 * of the lookup rather than a live view.
 *
 * <p>Players are ids rather than {@link org.bukkit.entity.Player} objects: a
 * duel outlives a disconnect long enough to be settled and cleaned up, and a
 * held player reference for somebody who left is a leak.
 *
 * @param matchId        the id of this duel, unique for its lifetime
 * @param playerOne      the player who started it
 * @param playerTwo      the player who was challenged
 * @param arenaId        the arena it is being fought in
 * @param itemId         the special item that opened it
 * @param state          where it is in its life
 * @param durationMillis how long it has been fighting, {@code 0} before the
 *                       fight phase begins and frozen at the final length once
 *                       it ends
 * @since 1.0.0
 */
public record SpecialsMatch(
        @NotNull String matchId,
        @NotNull UUID playerOne,
        @NotNull UUID playerTwo,
        @NotNull String arenaId,
        @NotNull String itemId,
        @NotNull MatchState state,
        long durationMillis) {

    /**
     * The other player in the duel.
     *
     * @param player one of the two
     * @return their opponent, or empty when the given player is not in this
     *         match
     */
    @NotNull
    public Optional<UUID> opponent(@NotNull UUID player) {
        if (player.equals(playerOne)) return Optional.of(playerTwo);
        if (player.equals(playerTwo)) return Optional.of(playerOne);
        return Optional.empty();
    }

    /**
     * Whether a player is one of the two fighting.
     *
     * @param player the player
     * @return {@code true} when they are in this match
     */
    public boolean contains(@NotNull UUID player) {
        return player.equals(playerOne) || player.equals(playerTwo);
    }
}
