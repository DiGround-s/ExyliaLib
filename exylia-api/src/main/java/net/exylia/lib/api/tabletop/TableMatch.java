package net.exylia.lib.api.tabletop;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A match as it stood when it was read: a copy, never updated afterwards.
 *
 * @param id        a short id, as the admin cancel command takes it
 * @param gameId    the game being played
 * @param state     where the match is
 * @param currency  the ExyliaLib economy id of the currency staked
 * @param stake     what each seat pays
 * @param pot       every stake held so far
 * @param seats     the players, host first
 * @param invitee   the one player a duel was sent to, or {@code null} for an offer anybody may take
 * @param arena     the arena it is played in, or {@code null} before it has one
 * @param createdAt when it was created, epoch millis
 * @param startedAt when it started being played, epoch millis, or {@code 0}
 * @since exylia-api 3.0.0
 */
public record TableMatch(@NotNull String id, @NotNull String gameId, @NotNull MatchState state,
                       @NotNull String currency, @NotNull BigDecimal stake, @NotNull BigDecimal pot,
                       @NotNull List<TableSeat> seats, @Nullable UUID invitee, @Nullable String arena,
                       long createdAt, long startedAt) {

    /** The player who created it. */
    public @NotNull TableSeat host() {
        return seats.getFirst();
    }

    /** Whether it was sent to one player rather than listed. */
    public boolean isDuel() {
        return invitee != null;
    }

    /** The seat a player holds, or {@code -1}. */
    public int seatOf(@NotNull UUID player) {
        for (int i = 0; i < seats.size(); i++) {
            if (seats.get(i).player().equals(player)) return i;
        }
        return -1;
    }
}
