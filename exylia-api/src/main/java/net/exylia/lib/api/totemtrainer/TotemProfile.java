package net.exylia.lib.api.totemtrainer;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * One player's duel record and lifetime pop statistics.
 *
 * <p>{@code winrate} is stored rather than derived because the leaderboard
 * sorts on it in the database. A {@code bestPopMillis} of zero means the player
 * has never popped a totem, not that they did it instantly.
 *
 * @param player         the player
 * @param name           their name at the last save, so a board can name
 *                       somebody offline
 * @param matches        duels finished
 * @param wins           duels won
 * @param losses         duels lost
 * @param winrate        wins as a percentage of duels finished
 * @param rounds         rounds played across every duel
 * @param roundWins      rounds taken
 * @param roundLosses    rounds lost
 * @param totalPops      totems re-equipped in time, ever
 * @param bestPopMillis  the fastest re-equip ever, or {@code 0} for none
 * @param totalPopMillis every reaction added together
 * @param currentStreak  the run of duel wins standing now
 * @param bestStreak     the longest run of duel wins ever held
 * @param updatedAt      when the row was last written, in epoch milliseconds
 * @since 1.0.0
 */
public record TotemProfile(
        @NotNull UUID player,
        @NotNull String name,
        int matches,
        int wins,
        int losses,
        double winrate,
        int rounds,
        int roundWins,
        int roundLosses,
        int totalPops,
        long bestPopMillis,
        long totalPopMillis,
        int currentStreak,
        int bestStreak,
        long updatedAt) {

    /**
     * The mean reaction across every totem this player has ever popped.
     *
     * @return the average in milliseconds, {@code 0} when they have popped none
     */
    public long averagePopMillis() {
        return totalPops == 0 ? 0 : totalPopMillis / totalPops;
    }
}
