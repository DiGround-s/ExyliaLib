package net.exylia.lib.api.clans;

import org.jetbrains.annotations.NotNull;

/**
 * One clan's totals, summed across every member.
 *
 * @param clanId          the clan
 * @param kills           kills by every member
 * @param deaths          deaths of every member
 * @param playTimeSeconds seconds every member has been online
 * @since 1.0.0
 */
public record ClanStats(
        @NotNull String clanId,
        long kills,
        long deaths,
        long playTimeSeconds) {

    /**
     * Kills per death.
     *
     * <p>A clan that has never died reports its kill count rather than
     * infinity: a leaderboard has to sort it and a menu has to print it.
     *
     * @return the ratio
     */
    public double kdr() {
        return deaths == 0 ? kills : (double) kills / deaths;
    }
}
