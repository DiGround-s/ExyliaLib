package net.exylia.lib.api.events;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * One player's counters, either across every event or within one of them.
 *
 * <p>The same four numbers answer both questions, so one record serves
 * {@link EventsService#stats(UUID)} and
 * {@link EventsService#eventStats(UUID, String)}. Which scope a value
 * belongs to is decided by the call that returned it, not by the record.
 *
 * @param player      the player
 * @param kills       kills
 * @param deaths      deaths
 * @param wins        events won
 * @param gamesPlayed events joined
 * @since 1.0.0
 */
public record EventStats(
        @NotNull UUID player,
        int kills,
        int deaths,
        int wins,
        int gamesPlayed) {

    /**
     * Kills per death.
     *
     * <p>A player who has never died reports their kill count rather than
     * infinity: a leaderboard has to sort it and a menu has to print it.
     *
     * @return the ratio
     */
    public double kdr() {
        return deaths == 0 ? kills : (double) kills / deaths;
    }

    /**
     * Wins as a percentage of games played.
     *
     * @return the win rate from {@code 0} to {@code 100}, {@code 0} when they
     *         have played nothing
     */
    public double winRate() {
        return gamesPlayed == 0 ? 0 : ((double) wins / gamesPlayed) * 100;
    }
}
