package net.exylia.lib.api.betcore.event;

import net.exylia.lib.api.betcore.EndReason;
import net.exylia.lib.api.betcore.BetMatch;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A match is over.
 *
 * <p>Fired after the money has been settled and the records written, so a
 * listener reading either sees the finished numbers rather than the ones from
 * before this match.
 *
 * @since 1.0.0
 */
public class BetMatchEndEvent extends BetEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final BetMatch match;
    private final UUID winner;
    private final EndReason reason;

    public BetMatchEndEvent(@NotNull BetMatch match, @Nullable UUID winner, @NotNull EndReason reason) {
        this.match = match;
        this.winner = winner;
        this.reason = reason;
    }

    public @NotNull BetMatch getMatch() {
        return match;
    }

    /** Who won, or {@code null} for a draw or a cancelled match. */
    public @Nullable UUID getWinner() {
        return winner;
    }

    public @NotNull EndReason getReason() {
        return reason;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
