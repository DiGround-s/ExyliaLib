package net.exylia.lib.api.totemtrainer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Map;

/**
 * How a session, a round or one side of a match went.
 *
 * <p>Times are milliseconds. A {@code bestMillis} of zero means no pop was
 * recorded rather than an instantaneous one, which is the distinction a
 * scoreboard has to make before it divides by anything.
 *
 * @param pops           totems re-equipped in time
 * @param fails          hits taken with no totem in hand
 * @param bestMillis     the fastest re-equip, or {@code 0} when there was none
 * @param worstMillis    the slowest re-equip
 * @param averageMillis  the mean re-equip across every pop
 * @param totalMillis    every reaction added together
 * @param currentStreak  the run of pops standing when this ended
 * @param bestStreak     the longest run of pops without a miss
 * @param durationMillis how long the session or round lasted
 * @param score          0 to 100, as the server's performance profile scores it
 * @param grades         how many pops landed in each grade
 * @since 1.0.0
 */
public record Performance(
        int pops,
        int fails,
        long bestMillis,
        long worstMillis,
        long averageMillis,
        long totalMillis,
        int currentStreak,
        int bestStreak,
        long durationMillis,
        int score,
        @NotNull @Unmodifiable Map<PerformanceGrade, Integer> grades) {

    /**
     * Defensive copy, so a summary handed to a listener cannot be edited under
     * the plugin that made it.
     */
    public Performance {
        grades = Map.copyOf(grades);
    }

    /**
     * How many pops landed in one grade.
     *
     * @param grade the grade
     * @return the count, {@code 0} when none did
     */
    public int gradeCount(@NotNull PerformanceGrade grade) {
        return grades.getOrDefault(grade, 0);
    }

    /**
     * The fastest re-equip in seconds, for display.
     *
     * @return the best reaction in seconds
     */
    public double bestSeconds() {
        return bestMillis / 1000.0;
    }

    /**
     * The mean re-equip in seconds, for display.
     *
     * @return the average reaction in seconds
     */
    public double averageSeconds() {
        return averageMillis / 1000.0;
    }

    /**
     * How long it lasted, in seconds.
     *
     * @return the duration in seconds
     */
    public double durationSeconds() {
        return durationMillis / 1000.0;
    }
}
