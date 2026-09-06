package net.exylia.lib.api.totemtrainer;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;

/**
 * One duel between two players, as it was when you asked.
 *
 * <p>A snapshot, not a live view. The running match is mutated on the thread
 * that owns it and lives in the plugin's own classloader, so this is a copy of
 * the fields a third party can act on. Ask
 * {@link TotemTrainerService#match(UUID)} again rather than holding one across
 * ticks.
 *
 * <p>The rounds already played are deliberately not here: a match listing would
 * copy every player's per-round statistics for a screen that only wants the
 * score. Listen for {@link net.exylia.lib.api.totemtrainer.event.RoundEndEvent}
 * for the detail of a round as it is scored.
 *
 * @param id           the match id
 * @param arenaId      the arena being fought in
 * @param playerA      the player who sent the duel
 * @param playerB      the player who accepted it
 * @param nameA        the first player's name, frozen when the match was built
 * @param nameB        the second player's name
 * @param scoreA       rounds the first player has taken
 * @param scoreB       rounds the second player has taken
 * @param modeId       which training mode the duel is played under
 * @param ticks        the interval between hits, in ticks
 * @param bestOf       how many rounds the series runs to at most
 * @param roundsToWin  how many one player has to take
 * @param roundsPlayed rounds already scored, draws included
 * @param state        where the match is in its life
 * @param winner       who took the series, empty until it is decided
 * @param createdAt    when the match was built, in epoch milliseconds
 * @param startedAt    when both players arrived, or {@code 0}
 * @param endedAt      when it was decided, or {@code 0}
 * @since 1.0.0
 */
public record TotemMatch(
        @NotNull UUID id,
        @NotNull String arenaId,
        @NotNull UUID playerA,
        @NotNull UUID playerB,
        @NotNull String nameA,
        @NotNull String nameB,
        int scoreA,
        int scoreB,
        @NotNull String modeId,
        int ticks,
        int bestOf,
        int roundsToWin,
        int roundsPlayed,
        @NotNull MatchState state,
        @NotNull Optional<UUID> winner,
        long createdAt,
        long startedAt,
        long endedAt) {

    /**
     * Whether a player is one of the two fighting.
     *
     * @param player the player
     * @return {@code true} when the match is theirs
     */
    public boolean has(@NotNull UUID player) {
        return player.equals(playerA) || player.equals(playerB);
    }

    /**
     * The other player, given one of them.
     *
     * @param player one of the two
     * @return the opponent, or empty when that player is not in this match
     */
    @NotNull
    public Optional<UUID> opponentOf(@NotNull UUID player) {
        if (player.equals(playerA)) return Optional.of(playerB);
        return player.equals(playerB) ? Optional.of(playerA) : Optional.empty();
    }

    /**
     * How many rounds a player has taken.
     *
     * @param player one of the two
     * @return their score, {@code 0} when they are not in this match
     */
    public int scoreOf(@NotNull UUID player) {
        if (player.equals(playerA)) return scoreA;
        return player.equals(playerB) ? scoreB : 0;
    }

    /**
     * How long the match has been going, or how long it lasted.
     *
     * @return milliseconds from the start to the end, or to now while it runs
     */
    public long durationMillis() {
        long end = endedAt > 0 ? endedAt : System.currentTimeMillis();
        return end - (startedAt > 0 ? startedAt : createdAt);
    }

    /**
     * How the series is written where a player reads it.
     *
     * @return the format label, for example {@code BO5}
     */
    @NotNull
    public String formatLabel() {
        return "BO" + bestOf;
    }
}
