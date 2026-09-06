package net.exylia.lib.api.betcore;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What one move did to the round.
 *
 * <p>{@code ILLEGAL} is not an error: a player clicking a square that is taken,
 * or clicking at all when it is not their turn, is ordinary and is answered
 * with a message rather than an exception. {@code reasonKey} names which
 * message.
 *
 * <p>A {@code WIN} names the <em>seat</em> that won, not a player: the rules
 * never learn who is sitting in it.
 *
 * @param status     what happened
 * @param winnerSeat the winning seat on {@code WIN}, otherwise {@code -1}
 * @param reasonKey  why the move was refused on {@code ILLEGAL}, else {@code null}
 *
 * @since 1.0.0
 */
public record MoveResult(@NotNull Status status, int winnerSeat, @Nullable String reasonKey) {

    public enum Status {
        /** The move was refused. Nothing changed. */
        ILLEGAL,
        /** The move landed and the round goes on. */
        CONTINUE,
        /** The move landed and won the round. */
        WIN,
        /** The move landed and the round cannot be won. */
        DRAW
    }

    /** The move landed and the round goes on. */
    public static final MoveResult CONTINUE = new MoveResult(Status.CONTINUE, -1, null);

    /** The round is over and nobody won it. */
    public static final MoveResult DRAW = new MoveResult(Status.DRAW, -1, null);

    /** The move was refused, for the reason this message key names. */
    @NotNull
    public static MoveResult illegal(@NotNull String reasonKey) {
        return new MoveResult(Status.ILLEGAL, -1, reasonKey);
    }

    /** That seat won the round. */
    @NotNull
    public static MoveResult win(int seat) {
        return new MoveResult(Status.WIN, seat, null);
    }

    /** Whether the round is over, however it ended. */
    public boolean isRoundOver() {
        return status == Status.WIN || status == Status.DRAW;
    }
}
