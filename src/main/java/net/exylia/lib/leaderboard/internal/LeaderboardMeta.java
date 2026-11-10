package net.exylia.lib.leaderboard.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.DataKind;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Table;

/**
 * How many times each period of a board was reset by an admin, for
 * {@code Leaderboards}.
 *
 * <p>The count is part of every period key, so a reset is one row written here:
 * every row of the board then names an older period, counts nothing, and starts
 * over at its next sample, on every server, with no row rewritten.
 *
 * <p>Public only because the database module compiles records by reflection.
 *
 * @param id    the board id
 * @param day   resets of the daily span
 * @param week  resets of the weekly span
 * @param month resets of the monthly span
 * @param year  resets of the yearly span
 * @since 1.563.0
 */
@Table(value = "exylia_leaderboards", cache = false, data = DataKind.PROGRESS)
public record LeaderboardMeta(
        @Id(length = 64) String id,
        @Column("day_resets") int day,
        @Column("week_resets") int week,
        @Column("month_resets") int month,
        @Column("year_resets") int year) {

    /** A board never reset. */
    public static LeaderboardMeta fresh(String board) {
        return new LeaderboardMeta(board, 0, 0, 0, 0);
    }

    /** Resets of a period: 0 day, 1 week, 2 month, 3 year. */
    public int resets(int period) {
        return switch (period) {
            case 0 -> day;
            case 1 -> week;
            case 2 -> month;
            default -> year;
        };
    }

    /** This with one more reset of a period, or of every period for {@code -1}. */
    public LeaderboardMeta reset(int period) {
        return new LeaderboardMeta(id,
                day + (period == 0 || period < 0 ? 1 : 0),
                week + (period == 1 || period < 0 ? 1 : 0),
                month + (period == 2 || period < 0 ? 1 : 0),
                year + (period == 3 || period < 0 ? 1 : 0));
    }
}
