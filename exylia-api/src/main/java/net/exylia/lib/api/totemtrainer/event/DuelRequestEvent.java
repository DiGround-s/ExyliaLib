package net.exylia.lib.api.totemtrainer.event;

import net.exylia.lib.api.totemtrainer.TrainingRules;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is about to challenge another to a duel.
 *
 * <p>Fired once both players have been found free and before the request is
 * stored or either of them is told about it, so cancelling leaves no trace: no
 * pending request, no expiry, no message. Telling the challenger why is the
 * canceller's job. A request that is sent does not start anything by itself;
 * {@link MatchStartEvent} follows only if it is accepted.
 *
 * @since 1.3.0
 */
public final class DuelRequestEvent extends TotemTrainerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player from;
    private final Player to;
    private final TrainingRules rules;
    private final int bestOf;
    private boolean cancelled;

    public DuelRequestEvent(@NotNull Player from, @NotNull Player to,
                            @NotNull TrainingRules rules, int bestOf) {
        this.from = from;
        this.to = to;
        this.rules = rules;
        this.bestOf = bestOf;
    }

    /** Who is challenging. */
    public @NotNull Player from() {
        return from;
    }

    /** Who is being challenged. */
    public @NotNull Player to() {
        return to;
    }

    /** What every round of the duel would run under. */
    public @NotNull TrainingRules rules() {
        return rules;
    }

    /** The length of the series. */
    public int bestOf() {
        return bestOf;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
