package net.exylia.lib.api.ffa;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One player's counters in one arena.
 *
 * <p>Counters are kept per arena, and a row under
 * {@link FfaService#GLOBAL_ARENA} holds the same player's totals across all of
 * them. That is why {@code arenaId} is part of the record rather than implied:
 * a player has as many of these as they have arenas played, plus one.
 *
 * <p>{@code kdr} and {@code killsPerMinute} are stored rather than derived
 * because the database sorts leaderboards by them, and they follow the same
 * rule the plugin uses everywhere: a player who has never died reports their
 * kill count rather than infinity, so a leaderboard can sort it and a menu can
 * print it.
 *
 * @param player                whose counters these are
 * @param playerName            the last name seen, or {@code null} when the player has never been online since the row was written
 * @param arenaId               which arena they were earned in
 * @param kills                 kills
 * @param deaths                deaths
 * @param currentStreak         kills since the last death
 * @param bestStreak            the highest that counter ever reached
 * @param assists               kills somebody else finished
 * @param damageDealt           total damage dealt
 * @param playTimeSeconds       seconds spent in the arena, alive or spectating
 * @param aliveTimeSeconds      seconds spent alive
 * @param kdr                   kills per death
 * @param killsPerMinute        kills per minute alive
 * @since 1.0.0
 */
public record FfaStats(
        @NotNull UUID player,
        @Nullable String playerName,
        @NotNull String arenaId,
        int kills,
        int deaths,
        int currentStreak,
        int bestStreak,
        int assists,
        double damageDealt,
        long playTimeSeconds,
        long aliveTimeSeconds,
        double kdr,
        double killsPerMinute) {
}
