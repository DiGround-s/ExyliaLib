package net.exylia.lib.progression;

import org.jetbrains.annotations.Nullable;

/**
 * Offline income: what a player earned while away, at a share of their pace for up to a cap (30% for 8 hours, say).
 * The plugin keeps when it last counted the player, turns {@link #minutes} into its own earnings and words the
 * "while you were away" summary from {@link #hours} and {@link #minutesPart}.
 *
 * @param awayMillis how long the player was away, capped
 * @param minutes    minutes of full-pace income they earned: the capped time times the share
 */
public record Offline(long awayMillis, double minutes) {

    private static final long HOUR = 3_600_000L;

    /**
     * What a player last counted at {@code seen} earned by {@code now}.
     *
     * @param capMillis the most away time that pays
     * @param share     the share of the pace earned away, 0 to 1 (0.3 for 30%)
     * @return null when there is nothing to pay: never counted, a clock gone back, or under a minute of income
     */
    public static @Nullable Offline of(long seen, long now, long capMillis, double share) {
        if (seen <= 0 || seen >= now) return null;
        long away = Math.min(now - seen, Math.max(0, capMillis));
        double minutes = away / 60_000.0 * Math.max(0, share);
        return minutes < 1 ? null : new Offline(away, minutes);
    }

    /** Whole hours away. */
    public long hours() {
        return awayMillis / HOUR;
    }

    /** Minutes past the whole hours away. */
    public long minutesPart() {
        return awayMillis / 60_000L % 60;
    }
}
