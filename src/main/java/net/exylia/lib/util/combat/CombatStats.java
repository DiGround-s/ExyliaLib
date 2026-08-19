package net.exylia.lib.util.combat;

/**
 * What a combat plugin counted about a player.
 *
 * <p>Only some plugins keep these. A provider that does not is honest about it:
 * {@link Combat#statsOf} returns empty rather than a record full of zeroes, so
 * a scoreboard can tell "no kills" from "nobody is counting".
 *
 * @param kills         how many players they killed
 * @param deaths        how many times they died
 * @param streak        their current kill streak
 * @param highestStreak the longest streak they ever had
 * @param combatLogs    how many times they logged out while tagged
 * @param points        whatever the plugin calls points
 * @since 1.36.0
 */
public record CombatStats(
        int kills,
        int deaths,
        int streak,
        int highestStreak,
        int combatLogs,
        int points
) {

    /**
     * Returns kills divided by deaths.
     *
     * <p>Computed here rather than taken from the plugin, because plugins
     * disagree on what to do with zero deaths and a leaderboard that mixes two
     * answers is worse than one that picks a side. A player who never died
     * counts as having died once, so ten kills and no deaths is 10.0 rather
     * than infinity.
     *
     * @return the ratio
     */
    public double ratio() {
        return deaths <= 0 ? kills : (double) kills / deaths;
    }
}
