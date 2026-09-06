package net.exylia.lib.api.betcore.event;

import net.exylia.lib.api.betcore.BetMatch;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A round finished. The series may or may not have.
 *
 * @since 1.0.0
 */
public class BetMatchRoundEndEvent extends BetEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final BetMatch match;
    private final int round;
    private final UUID winner;

    public BetMatchRoundEndEvent(@NotNull BetMatch match, int round, @Nullable UUID winner) {
        this.match = match;
        this.round = round;
        this.winner = winner;
    }

    public @NotNull BetMatch getMatch() {
        return match;
    }

    public int getRound() {
        return round;
    }

    /** Who took the round, or {@code null} when it was drawn. */
    public @Nullable UUID getWinner() {
        return winner;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
