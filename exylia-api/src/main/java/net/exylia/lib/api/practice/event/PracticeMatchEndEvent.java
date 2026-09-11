package net.exylia.lib.api.practice.event;

import net.exylia.lib.api.practice.MatchInfo;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A match has been decided.
 *
 * <p>Fired once per match, the moment the result is known: the result titles
 * are going up and the statistics are being written, and the players are still
 * in the arena for the few seconds before they are sent back to the lobby. A
 * match that ended before its countdown finished — a fighter leaving while it
 * loaded, an integration cancelling it — fires this too, with a
 * {@link MatchInfo#startedAt()} of {@code 0}.
 *
 * <p>Winners and losers are players. In a bot mode the bots are neither, so a
 * win against them names the players as winners and nobody as losers, and a
 * loss the other way round; neither is a draw.
 *
 * @since 1.3.0
 */
public class PracticeMatchEndEvent extends PracticeEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /**
     * How the match was decided.
     *
     * @since 1.3.0
     */
    public enum Reason {
        /** The last opponent standing was killed. */
        DEATH,
        /** A kit that is won on hits had its hit target reached. */
        HITS_TO_WIN,
        /** A kit that is won on points had its point target reached. */
        POINTS_TO_WIN,
        /** Somebody left, disconnected, or could not be moved into the arena. */
        FORFEIT,
        /**
         * Ended with no result, by a party leader, by an integration, or by the
         * bot plugin going away mid-fight. Nothing is recorded for anybody.
         */
        CANCELLED
    }

    private final MatchInfo match;
    private final Reason reason;
    private final List<UUID> winners;
    private final List<UUID> losers;
    private final Map<UUID, Integer> eloChanges;

    public PracticeMatchEndEvent(@NotNull MatchInfo match, @NotNull Reason reason,
                                 @NotNull List<UUID> winners, @NotNull List<UUID> losers,
                                 @NotNull Map<UUID, Integer> eloChanges) {
        this.match = match;
        this.reason = reason;
        this.winners = List.copyOf(winners);
        this.losers = List.copyOf(losers);
        this.eloChanges = Map.copyOf(eloChanges);
    }

    /** The match, as it was when it was decided. */
    public @NotNull MatchInfo match() {
        return match;
    }

    /** How it was decided. */
    public @NotNull Reason reason() {
        return reason;
    }

    /** The players on the winning side, eliminated or not. Empty on a draw. */
    public @NotNull @Unmodifiable List<UUID> winners() {
        return winners;
    }

    /** The players on every other side. Empty on a draw. */
    public @NotNull @Unmodifiable List<UUID> losers() {
        return losers;
    }

    /**
     * Whether nobody won: a draw, or a cancelled match.
     *
     * @return {@code true} when there are neither winners nor losers
     */
    public boolean isDraw() {
        return winners.isEmpty() && losers.isEmpty();
    }

    /**
     * How far each player's ELO on the kit moved, negative on a loss.
     *
     * <p>The same number their match history will show. Empty for every match
     * that moves no ELO: unranked, drawn, cancelled, or against bots.
     *
     * @return the change per player
     */
    public @NotNull @Unmodifiable Map<UUID, Integer> eloChanges() {
        return eloChanges;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
