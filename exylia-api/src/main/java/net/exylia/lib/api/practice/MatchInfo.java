package net.exylia.lib.api.practice;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.UUID;
import java.util.Set;

/**
 * A match in progress, as it was when you asked.
 *
 * <p>A snapshot rather than a live view: a match changes several times a second
 * and stops existing shortly after it ends, so ask again rather than holding
 * one. A caller that wants to act on the fighters should look them up by UUID
 * at the moment it acts.
 *
 * <p>Players are UUIDs and not {@code Player} objects for the same reason: a
 * fighter can log out mid-match, and a record holding a stale player object
 * would keep that player's whole entity alive.
 *
 * <p>The bot modes are here too. They report one player and an opponent that
 * does not exist, because a bot is not a player and this plugin does not model
 * it — see {@link MatchMode#againstBots()}.
 *
 * @param id             the match id, unique for this match's life
 * @param kitId          the kit being fought with
 * @param arenaId        the arena it is being fought in
 * @param mode           how the match was put together
 * @param status         where it is in its life
 * @param ranked         whether the result will move ELO
 * @param currentRound   the round being fought, {@code 1} for a single-round match
 * @param totalRounds    how many rounds decide it
 * @param players        everybody fighting, alive or eliminated
 * @param alivePlayers   the ones still in it
 * @param spectators     everybody watching, whether eliminated or never in it
 * @param startedAt      when the fighting started, as epoch milliseconds, or
 *                       {@code 0} while the match is still loading
 * @param durationMillis how long it has been running
 * @since 1.0.0
 */
public record MatchInfo(
        @NotNull String id,
        @NotNull String kitId,
        @NotNull String arenaId,
        @NotNull MatchMode mode,
        @NotNull MatchStatus status,
        boolean ranked,
        int currentRound,
        int totalRounds,
        @NotNull @Unmodifiable List<UUID> players,
        @NotNull @Unmodifiable List<UUID> alivePlayers,
        @NotNull @Unmodifiable Set<UUID> spectators,
        long startedAt,
        long durationMillis) {

    /**
     * Whether the match is being fought right now.
     *
     * <p>What anything acting on live fighters should check, rather than the
     * match merely existing: it exists while the arena is still being prepared
     * and for a moment after the last hit lands.
     *
     * @return {@code true} when the status is {@link MatchStatus#ACTIVE}
     */
    public boolean isActive() {
        return status == MatchStatus.ACTIVE;
    }
}
