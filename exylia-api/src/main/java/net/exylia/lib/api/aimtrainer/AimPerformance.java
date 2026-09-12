package net.exylia.lib.api.aimtrainer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Map;

/**
 * How a session, a round or a match side went.
 *
 * <p>Times are milliseconds; a best of zero means nothing was measured. The
 * score is the drill's own currency, the rating is the score times the
 * difficulty, and the rating is what the boards rank.
 *
 * @param kind                  what was played
 * @param hits                  targets hit
 * @param misses                clicks that hit nothing, or hit an unlit target
 * @param expired               flick targets that ran out of time
 * @param falseStarts           reaction clicks before the target lit
 * @param shots                 hits plus misses
 * @param accuracy              hits over shots, 0 to 100
 * @param bestStreak            most hits in a row
 * @param averageFlickMillis    time between consecutive hits, averaged
 * @param bestFlickMillis       fastest of those
 * @param averageReactionMillis reaction time, averaged and corrected for ping
 * @param bestReactionMillis    fastest reaction
 * @param onTargetPercent       share of a tracking session spent on the target, 0 to 100
 * @param longestLockMillis     longest unbroken stretch on the target
 * @param precisionPercent      how central the hits landed, 0 to 100
 * @param averagePing           the player's ping over the session
 * @param durationMillis        how long it ran
 * @param score                 the drill's own score
 * @param rating                the score times the difficulty
 * @param difficulty            what the settings were worth
 * @param grades                hits per grade
 * @since 1.5.0
 */
public record AimPerformance(@NotNull AimDrillKind kind, int hits, int misses, int expired, int falseStarts,
                             int shots, double accuracy, int bestStreak, long averageFlickMillis,
                             long bestFlickMillis, long averageReactionMillis, long bestReactionMillis,
                             double onTargetPercent, long longestLockMillis, double precisionPercent,
                             int averagePing, long durationMillis, int score, int rating, double difficulty,
                             @NotNull @Unmodifiable Map<AimGrade, Integer> grades) {

    public AimPerformance {
        grades = Map.copyOf(grades);
    }
}
