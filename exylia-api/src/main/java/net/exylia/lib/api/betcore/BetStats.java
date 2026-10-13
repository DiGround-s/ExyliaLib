package net.exylia.lib.api.betcore;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * How a player has done at one game.
 *
 * @since exylia-api 2.0.0
 */
public record BetStats(@NotNull UUID player, @NotNull String gameId, int wins, int losses, int draws) {

    public int played() {
        return wins + losses + draws;
    }

    /** Wins out of games played, from 0 to 100. */
    public double winRate() {
        return played() == 0 ? 0 : wins * 100.0 / played();
    }
}
