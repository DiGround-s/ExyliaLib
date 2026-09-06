package net.exylia.lib.api.capture;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * One player's capture totals.
 *
 * <p>The same shape covers both scopes: {@code eventConfigId} is empty for a
 * player's totals across every event, and names the config for one event's row.
 * Two records would have been the same six fields twice.
 *
 * <p>A player who has never taken part still has one of these, with every
 * counter at zero, so a menu or a placeholder never has to handle an absent
 * row.
 *
 * @param player            whose totals these are
 * @param eventConfigId     which event, or empty for every event together
 * @param wins              events won
 * @param captures          zones taken
 * @param timeCapturedSeconds seconds spent holding a zone
 * @param points            points earned
 * @param leaderboardPoints the score leaderboards are sorted by
 * @since 1.0.0
 */
public record CaptureStats(
        @NotNull UUID player,
        @NotNull String eventConfigId,
        int wins,
        int captures,
        long timeCapturedSeconds,
        long points,
        long leaderboardPoints) {
}
