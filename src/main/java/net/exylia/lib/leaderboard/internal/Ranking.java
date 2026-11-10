package net.exylia.lib.leaderboard.internal;

import net.exylia.lib.leaderboard.LeaderboardView;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A board's standings in one span, sorted once and read many times, and the
 * arithmetic behind them: what a sample does to a row, and what a row counts
 * for in a span. Free of the server, so it is tested on its own.
 *
 * @param entries every ranked player, best first
 * @param byPlayer the same entries by player
 * @since 1.563.0
 */
public record Ranking(List<LeaderboardView.Entry> entries, Map<UUID, LeaderboardView.Entry> byPlayer) {

    /** Nobody ranked: a board not tracked, or not sorted yet. */
    public static final Ranking EMPTY = new Ranking(List.of(), Map.of());

    /** How many periods a row keeps a base for: day, week, month, year. */
    public static final int PERIODS = 4;

    /**
     * What a new sample makes of a row.
     *
     * <p>A period whose key moved on starts from the value last stored, so what
     * changed while nobody sampled (money paid to somebody offline over the
     * reset) counts for the new period. A row seen for the first time starts
     * every period from the value it has now: nothing was gained yet.
     *
     * @param old    the row so far, or {@code null}
     * @param keys   the current key of each period
     * @return {@code old} itself when nothing changed, otherwise the new row
     */
    public static LeaderboardRow sample(@Nullable LeaderboardRow old, String board, String scope, UUID player,
                                        String name, double value, String[] keys, long now) {
        if (old == null) {
            return new LeaderboardRow(LeaderboardRow.id(board, scope, player), board, scope, player, name, value,
                    keys[0], value, keys[1], value, keys[2], value, keys[3], value, now);
        }
        boolean changed = value != old.value() || !name.equals(old.name());
        String[] rowKeys = new String[PERIODS];
        double[] bases = new double[PERIODS];
        for (int period = 0; period < PERIODS; period++) {
            boolean rolled = !keys[period].equals(old.key(period));
            changed |= rolled;
            rowKeys[period] = keys[period];
            bases[period] = rolled ? old.value() : old.base(period);
        }
        if (!changed) return old;
        return new LeaderboardRow(old.id(), board, scope, player, name, value,
                rowKeys[0], bases[0], rowKeys[1], bases[1], rowKeys[2], bases[2], rowKeys[3], bases[3],
                value != old.value() ? now : old.updated());
    }

    /**
     * What a row counts for in a span.
     *
     * @param period {@code -1} for all time, else 0 day, 1 week, 2 month, 3 year
     * @param key    the current key of that period; ignored for all time
     */
    public static double counted(LeaderboardRow row, int period, @Nullable String key) {
        if (period < 0) return row.value();
        return row.key(period).equals(key) ? row.value() - row.base(period) : 0;
    }

    /**
     * Sorts a board's rows into one span's standings.
     *
     * <p>A player's rows from every server are added up. Players who count
     * zero are not ranked. Ties go to whoever reached the value first (the
     * oldest last change), then to the lower id, so the order never flickers
     * between two sorts.
     *
     * @param rows   every row of the board
     * @param period {@code -1} for all time, else 0 day, 1 week, 2 month, 3 year
     * @param key    the current key of that period; ignored for all time
     */
    public static Ranking rank(Collection<LeaderboardRow> rows, int period, @Nullable String key) {
        Map<UUID, Total> totals = new HashMap<>();
        for (LeaderboardRow row : rows) {
            totals.computeIfAbsent(row.player(), Total::new).add(row, counted(row, period, key));
        }
        List<Total> ranked = new ArrayList<>(totals.size());
        for (Total total : totals.values()) {
            if (total.value != 0 && Double.isFinite(total.value)) ranked.add(total);
        }
        ranked.sort(Comparator.comparingDouble((Total total) -> total.value).reversed()
                .thenComparingLong(total -> total.updated)
                .thenComparing(total -> total.player));
        List<LeaderboardView.Entry> entries = new ArrayList<>(ranked.size());
        Map<UUID, LeaderboardView.Entry> byPlayer = new HashMap<>(ranked.size() * 2);
        for (Total total : ranked) {
            LeaderboardView.Entry entry = new LeaderboardView.Entry(entries.size() + 1, total.player, total.name, total.value);
            entries.add(entry);
            byPlayer.put(total.player, entry);
        }
        return new Ranking(List.copyOf(entries), Map.copyOf(byPlayer));
    }

    /** One player's rows added up. */
    private static final class Total {
        final UUID player;
        double value;
        long updated = Long.MIN_VALUE;
        String name = "";

        Total(UUID player) {
            this.player = player;
        }

        void add(LeaderboardRow row, double counted) {
            value += counted;
            if (row.updated() >= updated) {
                updated = row.updated();
                if (row.name() != null && !row.name().isEmpty()) name = row.name();
            }
        }
    }
}
