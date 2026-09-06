package net.exylia.lib.api.events;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Set;
import java.util.UUID;

/**
 * One running event, as it was when you asked.
 *
 * <p>A snapshot, not a live view: an event's player set, state and clock change
 * every tick, so the values here are the ones that were current at the moment of
 * the lookup. Ask again rather than holding one across ticks.
 *
 * <p>{@code id} is the id of this run and lives only as long as the event does;
 * {@code configId} is the id of the configuration it was started from and is the
 * same across every run of it. A leaderboard, a statistic or a menu entry keys
 * on {@code configId}; a join or a force-end names the {@code id}.
 *
 * @param id               this run's id, unique while it lasts
 * @param configId         the configuration it was started from, empty when the
 *                         event was built without one
 * @param type             which minigame this is, for example {@code tntrun};
 *                         empty when the configuration is gone
 * @param displayName      what a menu shows
 * @param description      the one-line description, empty when there is none
 * @param state            where it is in its life
 * @param minPlayers       how many it needs before it starts
 * @param maxPlayers       how many it will hold
 * @param players          everyone playing, eliminated players included
 * @param spectators       everyone watching
 * @param alivePlayers     how many of the players are still in it
 * @param remainingSeconds seconds left on the clock, as the event counts it; an
 *                         event with no time limit never counts it down
 * @since 1.0.0
 */
public record GameEvent(
        @NotNull String id,
        @NotNull String configId,
        @NotNull String type,
        @NotNull String displayName,
        @NotNull String description,
        @NotNull GameState state,
        int minPlayers,
        int maxPlayers,
        @NotNull @Unmodifiable Set<UUID> players,
        @NotNull @Unmodifiable Set<UUID> spectators,
        int alivePlayers,
        int remainingSeconds) {

    /**
     * How many players are in the event.
     *
     * @return the player count, spectators excluded
     */
    public int playerCount() {
        return players.size();
    }

    /**
     * Whether the event will take anybody else.
     *
     * <p>Being full is not the only reason a join is refused — the state has to
     * allow it too — so this is what greys a button out, not what decides.
     *
     * @return {@code true} when it is at its player limit
     */
    public boolean isFull() {
        return players.size() >= maxPlayers;
    }
}
