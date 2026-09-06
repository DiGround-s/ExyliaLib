package net.exylia.lib.api.totemtrainer;

/**
 * What a training leaderboard sorts by.
 *
 * <p>Every one of these is a best rather than a total, which is what lets a
 * board built over rows kept per tick speed read as a board over players: the
 * row that sorts highest for a player is that player's best, whatever speed
 * they set it at.
 *
 * <p>Only {@link #RATING} compares across speeds honestly, because it carries
 * the weight of the interval the score was set at. The rest are raw bests that
 * a slower interval inflates on its own, so a board over every speed names the
 * speed on each row and picking one speed is what makes them comparable.
 *
 * @since 1.0.0
 */
public enum RecordCategory {

    /** The best score, weighted by how fast the interval was. Highest first. */
    RATING,

    /** The most totems popped in one session. Highest first. */
    BEST_POPS,

    /** The lowest average reaction time. Fastest first. */
    FASTEST_AVERAGE,

    /** The longest run without a miss. Highest first. */
    BEST_STREAK,

    /** The longest a session ever lasted. Highest first. */
    LONGEST_RUN;

    /**
     * Whether the smallest number wins, as a reaction time does.
     *
     * @return {@code true} when lower is better
     */
    public boolean ascending() {
        return this == FASTEST_AVERAGE;
    }
}
