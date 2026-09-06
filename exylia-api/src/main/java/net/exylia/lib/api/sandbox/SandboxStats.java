package net.exylia.lib.api.sandbox;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * What a player has done inside the sandbox.
 *
 * <p>Sandbox time only: the playtime counts the minutes spent in a sandbox
 * world, not the minutes spent on the server, because that is the number the
 * sandbox's own leaderboards are about.
 *
 * <p>A player nothing has loaded yet reads as zeroes rather than as an absent
 * value. Their real counters arrive with the load the read starts, and a
 * scoreboard that would rather print a zero than nothing is the only thing that
 * asks.
 *
 * @param player          the player
 * @param kills           kills in the sandbox
 * @param deaths          deaths in the sandbox
 * @param playtimeMillis  milliseconds spent inside sandbox worlds
 * @since 1.0.0
 */
public record SandboxStats(
        @NotNull UUID player,
        int kills,
        int deaths,
        long playtimeMillis) {

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
}
