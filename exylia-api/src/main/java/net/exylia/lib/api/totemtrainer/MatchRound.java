package net.exylia.lib.api.totemtrainer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One played round of a duel.
 *
 * <p>An empty winner is a draw, which is replayed rather than moved past: both
 * players dropped their totem in the same tick and nobody earned the round. The
 * drawn attempt is still a round — it happened and its pops count — it just does
 * not advance the series, so a round number can appear twice.
 *
 * @param number    which round of the series this was, counting from {@code 1}
 * @param startedAt when it began, in epoch milliseconds
 * @param endedAt   when it was scored, in epoch milliseconds
 * @param winner    who took it, empty for a draw
 * @param summaries how each player did, keyed by their id
 * @since 1.0.0
 */
public record MatchRound(
        int number,
        long startedAt,
        long endedAt,
        @NotNull Optional<UUID> winner,
        @NotNull @Unmodifiable Map<UUID, Performance> summaries) {

    /**
     * Defensive copy, so a round handed to a listener cannot be edited under
     * the plugin that made it.
     */
    public MatchRound {
        summaries = Map.copyOf(summaries);
    }

    /**
     * How one player did in this round.
     *
     * @param player the player
     * @return their summary, or empty when they did not play this round
     */
    @NotNull
    public Optional<Performance> summaryOf(@NotNull UUID player) {
        return Optional.ofNullable(summaries.get(player));
    }
}
