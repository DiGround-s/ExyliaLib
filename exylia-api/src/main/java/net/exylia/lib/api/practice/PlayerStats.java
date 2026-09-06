package net.exylia.lib.api.practice;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * One player's counters on one kit, in one season.
 *
 * <p>Seasons reset: these are the numbers since the current season began, not
 * the numbers since the player joined. A row appears the first time the player
 * finishes a match on the kit, so a player with no row simply has not played it.
 *
 * <p>A player's overall standing is the same record with a {@code kitId} of
 * {@code global} — see {@link PracticeService#globalStats}. It is stored the
 * same way, which is what lets one query answer both.
 *
 * @param playerUuid        whose numbers these are
 * @param playerName        the name they last played under
 * @param kitId             the kit, or {@code global} for the summed row
 * @param season            which season they were earned in
 * @param kills             kills
 * @param deaths            deaths
 * @param wins              matches won
 * @param losses            matches lost
 * @param draws             matches that ended without a decision
 * @param currentStreak     the win streak the player is on now
 * @param bestStreak        the longest win streak they have ever held
 * @param elo               their current ELO, which the visible rank is derived from
 * @param peakElo           the highest ELO they have reached this season
 * @param rankedGamesPlayed how many ranked matches they have finished
 * @param damageDealt       total damage dealt
 * @param damageTaken       total damage taken
 * @param bestCombo         the longest uninterrupted hit streak they have landed
 * @param timePlayed        total time in matches, in milliseconds
 * @param winRate           wins as a share of matches played, from 0 to 1
 * @param kdr               kills divided by deaths
 * @since 1.0.0
 */
public record PlayerStats(
        @NotNull UUID playerUuid,
        @NotNull String playerName,
        @NotNull String kitId,
        int season,
        int kills,
        int deaths,
        int wins,
        int losses,
        int draws,
        int currentStreak,
        int bestStreak,
        int elo,
        int peakElo,
        int rankedGamesPlayed,
        double damageDealt,
        double damageTaken,
        int bestCombo,
        long timePlayed,
        double winRate,
        double kdr) {

    /** The kit id the row that sums every kit is stored under. */
    public static final String GLOBAL_KIT = "global";
}
