package net.exylia.lib.api.capture;

import org.jetbrains.annotations.NotNull;

/**
 * One clan's capture totals.
 *
 * <p>Only written when an event runs in clan mode, which needs a clan plugin to
 * be installed: on a server without one every event scores individually and
 * these stay at zero.
 *
 * @param clanId            which clan
 * @param wins              events won
 * @param captures          zones taken
 * @param points            points earned
 * @param leaderboardPoints the score leaderboards are sorted by
 * @since 1.0.0
 */
public record CaptureClanStats(
        @NotNull String clanId,
        int wins,
        int captures,
        long points,
        long leaderboardPoints) {
}
