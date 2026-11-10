package net.exylia.lib.leaderboard.internal;

import net.exylia.lib.leaderboard.LeaderboardView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** What a sample does to a row's bases, and how rows sort into one span. */
class RankingTest {

    private static final String[] THIS_WEEK = {"2026-11-10#0", "2026-11-09#0", "2026-11-01#0", "2026-01-01#0"};
    private static final String[] NEXT_DAY = {"2026-11-11#0", "2026-11-09#0", "2026-11-01#0", "2026-01-01#0"};
    private static final int DAY = 0;
    private static final int WEEK = 1;

    private static final UUID ANNA = new UUID(0, 1);
    private static final UUID BRUNO = new UUID(0, 2);
    private static final UUID CARLA = new UUID(0, 3);

    private static LeaderboardRow row(UUID player, String scope, double value, String[] keys, long at) {
        return Ranking.sample(null, "kills", scope, player, "p" + player.getLeastSignificantBits(), value, keys, at);
    }

    @Test
    void aFirstSampleHasGainedNothingYet() {
        LeaderboardRow first = row(ANNA, "*", 40, THIS_WEEK, 1);
        assertEquals(40, Ranking.counted(first, -1, null));
        assertEquals(0, Ranking.counted(first, WEEK, THIS_WEEK[WEEK]));
    }

    @Test
    void gainsCountFromThePeriodsBase() {
        LeaderboardRow first = row(ANNA, "*", 40, THIS_WEEK, 1);
        LeaderboardRow later = Ranking.sample(first, "kills", "*", ANNA, "p1", 46, THIS_WEEK, 2);
        assertEquals(6, Ranking.counted(later, DAY, THIS_WEEK[DAY]));
        assertEquals(6, Ranking.counted(later, WEEK, THIS_WEEK[WEEK]));
        assertEquals(2, later.updated());
    }

    @Test
    void aNewDayStartsFromTheLastStoredValueAndKeepsTheWeek() {
        LeaderboardRow monday = Ranking.sample(row(ANNA, "*", 40, THIS_WEEK, 1), "kills", "*", ANNA, "p1", 46, THIS_WEEK, 2);
        // Paid 10 while offline over the reset: it belongs to the new day.
        LeaderboardRow tuesday = Ranking.sample(monday, "kills", "*", ANNA, "p1", 56, NEXT_DAY, 3);
        assertEquals(10, Ranking.counted(tuesday, DAY, NEXT_DAY[DAY]));
        assertEquals(16, Ranking.counted(tuesday, WEEK, NEXT_DAY[WEEK]));
    }

    @Test
    void aRowFromAnEarlierPeriodCountsNothingUntilSampled() {
        LeaderboardRow monday = Ranking.sample(row(ANNA, "*", 40, THIS_WEEK, 1), "kills", "*", ANNA, "p1", 46, THIS_WEEK, 2);
        assertEquals(0, Ranking.counted(monday, DAY, NEXT_DAY[DAY]));
        // An admin reset bumps the count in the key: the same week, started over.
        assertEquals(0, Ranking.counted(monday, WEEK, "2026-11-09#1"));
    }

    @Test
    void anUnchangedSampleIsTheSameRow() {
        LeaderboardRow first = row(ANNA, "*", 40, THIS_WEEK, 1);
        assertSame(first, Ranking.sample(first, "kills", "*", ANNA, first.name(), 40, THIS_WEEK, 9));
    }

    @Test
    void tiesGoToWhoeverGotThereFirstThenTheLowerId() {
        List<LeaderboardRow> rows = List.of(
                row(BRUNO, "*", 10, THIS_WEEK, 50),
                row(CARLA, "*", 10, THIS_WEEK, 20),
                row(ANNA, "*", 10, THIS_WEEK, 50),
                row(new UUID(0, 4), "*", 30, THIS_WEEK, 99));
        List<LeaderboardView.Entry> entries = Ranking.rank(rows, -1, null).entries();
        assertEquals(List.of(new UUID(0, 4), CARLA, ANNA, BRUNO), entries.stream().map(LeaderboardView.Entry::id).toList());
        assertEquals(List.of(1, 2, 3, 4), entries.stream().map(LeaderboardView.Entry::rank).toList());
    }

    @Test
    void eachServersRowsAddUpAndZeroIsNotRanked() {
        List<LeaderboardRow> rows = List.of(
                row(ANNA, "survival", 30, THIS_WEEK, 1),
                row(ANNA, "lobby", 5, THIS_WEEK, 2),
                row(BRUNO, "survival", 0, THIS_WEEK, 1));
        Ranking ranking = Ranking.rank(rows, -1, null);
        assertEquals(1, ranking.entries().size());
        assertEquals(35, ranking.byPlayer().get(ANNA).value());
        assertEquals(0, Ranking.rank(rows, WEEK, THIS_WEEK[WEEK]).entries().size());
    }
}
