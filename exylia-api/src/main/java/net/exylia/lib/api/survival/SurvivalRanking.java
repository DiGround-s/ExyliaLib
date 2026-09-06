package net.exylia.lib.api.survival;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * One row of a statistic's leaderboard.
 *
 * <p>{@code value} is a {@code double} whatever the statistic counts, because
 * the same leaderboard machinery ranks kills, money and hours played. The
 * plugin formats it per statistic for its own menus; a caller that needs the
 * same formatting should read the number and format it itself rather than
 * depend on the plugin's configuration.
 *
 * @param player     whose row this is
 * @param playerName the name recorded when the row was written
 * @param value      what they scored
 * @param rank       their position, starting at {@code 1}
 * @since 1.0.0
 */
public record SurvivalRanking(
        @NotNull UUID player,
        @NotNull String playerName,
        double value,
        int rank) {
}
