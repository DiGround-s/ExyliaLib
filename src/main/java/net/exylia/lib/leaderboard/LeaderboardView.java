package net.exylia.lib.leaderboard;

import net.exylia.lib.format.Formats;
import net.exylia.lib.format.Numbers;
import net.exylia.lib.leaderboard.internal.Ranking;
import net.exylia.lib.util.PeriodClock;
import net.exylia.lib.util.TimeFormats;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * One board's standings in one span, read from memory.
 *
 * <p>Cheap to hold and to call from any thread, every tick: it reads the last
 * sort, which {@link Leaderboards} redoes on its own timer. A view of a board
 * nobody tracks yet is empty, and fills once the board is tracked and sorted,
 * so a hologram can keep the view it was given.
 *
 * <pre>{@code
 * LeaderboardView weekly = boards.view("kills", Span.WEEKLY);
 * weekly.at(1).map(entry -> entry.name() + " " + entry.formatted("compact")).orElse("—");
 * weekly.rankOf(viewer.getUniqueId());   // the viewer's own place
 * }</pre>
 *
 * @since 1.563.0
 */
public final class LeaderboardView {

    /**
     * One ranked player.
     *
     * @param rank  their place, from 1; never shared, ties are already broken
     * @param id    who they are
     * @param name  their name when last seen
     * @param value their value in the view's span: the total, or what they gained
     */
    public record Entry(int rank, @NotNull UUID id, @NotNull String name, double value) {

        /** The value written in a style; see {@link LeaderboardView#format}. */
        public @NotNull String formatted(@Nullable String style) {
            return format(value, style);
        }
    }

    private final String board;
    private final Span span;

    LeaderboardView(String board, Span span) {
        this.board = board;
        this.span = span;
    }

    /** The board's id. */
    public @NotNull String board() {
        return board;
    }

    /** The span ranked. */
    public @NotNull Span span() {
        return span;
    }

    /** The first {@code count} players, best first; fewer when fewer are ranked. */
    public @NotNull List<Entry> top(int count) {
        List<Entry> entries = ranking().entries();
        return entries.subList(0, Math.max(0, Math.min(count, entries.size())));
    }

    /** The player in a place, from 1. */
    public @NotNull Optional<Entry> at(int rank) {
        List<Entry> entries = ranking().entries();
        return rank < 1 || rank > entries.size() ? Optional.empty() : Optional.of(entries.get(rank - 1));
    }

    /** A player's entry; empty when they count zero in this span or were never seen. */
    public @NotNull Optional<Entry> entryOf(@NotNull UUID player) {
        return Optional.ofNullable(ranking().byPlayer().get(player));
    }

    /** A player's place; empty when they are not ranked. */
    public @NotNull OptionalInt rankOf(@NotNull UUID player) {
        Entry entry = ranking().byPlayer().get(player);
        return entry == null ? OptionalInt.empty() : OptionalInt.of(entry.rank());
    }

    /** A player's value in this span; empty when they are not ranked. */
    public @NotNull OptionalDouble valueOf(@NotNull UUID player) {
        Entry entry = ranking().byPlayer().get(player);
        return entry == null ? OptionalDouble.empty() : OptionalDouble.of(entry.value());
    }

    /** How many players are ranked. */
    public int size() {
        return ranking().entries().size();
    }

    /** How long until this span starts over; empty for {@link Span#ALL_TIME}. */
    public @NotNull Optional<Duration> remaining() {
        PeriodClock.Period period = span.period();
        return period == null ? Optional.empty() : Optional.of(Duration.ofMillis(Leaderboards.clockInUse().remaining(period)));
    }

    private Ranking ranking() {
        return Leaderboards.ranking(board, span);
    }

    /**
     * A leaderboard value written for players.
     *
     * <ul>
     *   <li>{@code compact}: {@code 1.2K}, following {@code formats.yml}</li>
     *   <li>{@code money}: the server's money format</li>
     *   <li>{@code duration}: milliseconds as {@code 1d 4h 12m}, for {@link LeaderboardSource#playtime()}</li>
     *   <li>blank or {@code null}: whole numbers grouped, {@code 12,345}, two decimals when it has some</li>
     *   <li>anything else: a {@link DecimalFormat} pattern such as {@code #,##0.##}, the same on every host</li>
     * </ul>
     *
     * @param value the value
     * @param style the style, or a pattern
     * @return the text; a broken pattern falls back to the default style
     */
    public static @NotNull String format(double value, @Nullable String style) {
        String chosen = style == null ? "" : style.trim();
        switch (chosen.toLowerCase(Locale.ROOT)) {
            case "compact":
                return Formats.compact(value);
            case "money":
                return Formats.money(value);
            case "duration":
                return TimeFormats.render(value / 1000.0, TimeFormats.Style.FULL);
            case "":
                return value == Math.rint(value) && Math.abs(value) < 9e15
                        ? Numbers.grouped((long) value) : Numbers.grouped(value, 2);
            default:
                try {
                    return new DecimalFormat(chosen, DecimalFormatSymbols.getInstance(Locale.ROOT)).format(value);
                } catch (IllegalArgumentException broken) {
                    return format(value, "");
                }
        }
    }
}
