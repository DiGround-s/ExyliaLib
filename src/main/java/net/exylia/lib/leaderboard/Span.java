package net.exylia.lib.leaderboard;

import net.exylia.lib.util.PeriodClock;
import org.jetbrains.annotations.Nullable;

/**
 * Which stretch of time a leaderboard ranks.
 *
 * <p>{@link #ALL_TIME} ranks the source's current value; every other span ranks
 * what was gained since that period of the board's {@link PeriodClock} began.
 *
 * @since 1.563.0
 */
public enum Span {
    ALL_TIME(null), DAILY(PeriodClock.Period.DAILY), WEEKLY(PeriodClock.Period.WEEKLY),
    MONTHLY(PeriodClock.Period.MONTHLY), YEARLY(PeriodClock.Period.YEARLY);

    private final @Nullable PeriodClock.Period period;

    Span(@Nullable PeriodClock.Period period) {
        this.period = period;
    }

    /** The clock period this span follows, or {@code null} for {@link #ALL_TIME}. */
    public @Nullable PeriodClock.Period period() {
        return period;
    }

    /** {@code -1} for all time, else the period's index: 0 day, 1 week, 2 month, 3 year. */
    int index() {
        return ordinal() - 1;
    }
}
