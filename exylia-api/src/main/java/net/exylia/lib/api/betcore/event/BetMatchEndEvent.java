package net.exylia.lib.api.betcore.event;

import net.exylia.lib.api.betcore.BetMatch;
import net.exylia.lib.api.betcore.EndReason;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A match ended: paid out to a winner, or refunded. Fired once per match, offers and duels that
 * expired or were cancelled included.
 *
 * @since exylia-api 2.0.0
 */
public class BetMatchEndEvent extends BetEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final BetMatch match;
    private final UUID winner;
    private final EndReason reason;
    private final BigDecimal prize;
    private final BigDecimal fee;

    public BetMatchEndEvent(@NotNull BetMatch match, @Nullable UUID winner, @NotNull EndReason reason,
                            @NotNull BigDecimal prize, @NotNull BigDecimal fee) {
        this.match = match;
        this.winner = winner;
        this.reason = reason;
        this.prize = prize;
        this.fee = fee;
    }

    public @NotNull BetMatch getMatch() {
        return match;
    }

    /** Who was paid the pot, or {@code null} when every stake was refunded. */
    public @Nullable UUID getWinner() {
        return winner;
    }

    public @NotNull EndReason getReason() {
        return reason;
    }

    /** What the winner was paid; zero on a refund. */
    public @NotNull BigDecimal getPrize() {
        return prize;
    }

    /** What the house kept; zero on a refund. */
    public @NotNull BigDecimal getFee() {
        return fee;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
