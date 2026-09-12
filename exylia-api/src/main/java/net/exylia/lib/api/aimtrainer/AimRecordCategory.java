package net.exylia.lib.api.aimtrainer;

/**
 * What a drill's leaderboard is sorted by.
 *
 * <p>Only {@link #RATING} compares two players who set their targets up
 * differently: it carries the difficulty of the settings it was set with. The
 * rest are raw bests.
 *
 * @since 1.5.0
 */
public enum AimRecordCategory {
    RATING,
    SCORE,
    ACCURACY,
    STREAK,
    HITS,
    REACTION
}
