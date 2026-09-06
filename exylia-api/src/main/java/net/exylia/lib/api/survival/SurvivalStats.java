package net.exylia.lib.api.survival;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * A player's combat counters.
 *
 * <p>The four the plugin stores itself. The wider statistics system it also
 * runs can rank anything an administrator names — a placeholder, an economy
 * balance, a formula — and those are read one at a time through
 * {@link SurvivalService#leaderboard(String)} rather than fixed into a record.
 *
 * @param player          whose counters these are
 * @param kills           players killed
 * @param deaths          times died
 * @param bestKillStreak  the highest streak ever reached
 * @param currentStreak   kills since the last death
 * @since 1.0.0
 */
public record SurvivalStats(
        @NotNull UUID player,
        int kills,
        int deaths,
        int bestKillStreak,
        int currentStreak) {
}
