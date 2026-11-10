package net.exylia.lib.leaderboard.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.DataKind;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Owner;
import net.exylia.lib.database.Table;

import java.util.UUID;

/**
 * One player's value on one board, as one server (or every server) sees it,
 * for {@code Leaderboards}.
 *
 * <p>A board whose source differs per server (vanilla statistics) keeps one row
 * per server and adds them up; a board whose source is the same everywhere
 * (a network balance) keeps one row under {@link #SHARED}. Each period keeps the
 * value it started from: what was gained in it is {@code value - base}, while
 * the period's key still names the current period.
 *
 * <p>Public only because the database module compiles records by reflection;
 * nothing outside the library reads or writes it.
 *
 * @param id        {@code board|scope|player}
 * @param board     the board id
 * @param scope     the server that wrote it, or {@link #SHARED}
 * @param player    whose value it is
 * @param name      their name when last seen
 * @param value     the source's value when last sampled
 * @param dayKey    the day {@code dayBase} belongs to
 * @param dayBase   the value the day started from
 * @param weekKey   the week {@code weekBase} belongs to
 * @param weekBase  the value the week started from
 * @param monthKey  the month {@code monthBase} belongs to
 * @param monthBase the value the month started from
 * @param yearKey   the year {@code yearBase} belongs to
 * @param yearBase  the value the year started from
 * @param updated   when the value last changed, epoch millis; breaks ties
 * @since 1.563.0
 */
@Table(value = "exylia_leaderboard_values", cache = false, data = DataKind.PROGRESS)
public record LeaderboardRow(
        @Id(length = 200) String id,
        @Indexed @Column(value = "board", length = 64) String board,
        @Column(value = "scope", length = 96) String scope,
        @Owner @Indexed @Column("player") UUID player,
        @Column(value = "name", length = 32) String name,
        @Column("value") double value,
        @Column(value = "day_key", length = 32) String dayKey,
        @Column("day_base") double dayBase,
        @Column(value = "week_key", length = 32) String weekKey,
        @Column("week_base") double weekBase,
        @Column(value = "month_key", length = 32) String monthKey,
        @Column("month_base") double monthBase,
        @Column(value = "year_key", length = 32) String yearKey,
        @Column("year_base") double yearBase,
        @Column("updated") long updated) {

    /** The scope of a row every server writes, for a source that reads the same everywhere. */
    public static final String SHARED = "*";

    /** The row id of a player on a board, in a scope. */
    public static String id(String board, String scope, UUID player) {
        return board + "|" + scope + "|" + player;
    }

    /** The period key of a span: 0 day, 1 week, 2 month, 3 year. */
    public String key(int period) {
        return switch (period) {
            case 0 -> dayKey;
            case 1 -> weekKey;
            case 2 -> monthKey;
            default -> yearKey;
        };
    }

    /** The value a span started from: 0 day, 1 week, 2 month, 3 year. */
    public double base(int period) {
        return switch (period) {
            case 0 -> dayBase;
            case 1 -> weekBase;
            case 2 -> monthBase;
            default -> yearBase;
        };
    }
}
