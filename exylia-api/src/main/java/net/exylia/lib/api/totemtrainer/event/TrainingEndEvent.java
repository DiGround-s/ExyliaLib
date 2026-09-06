package net.exylia.lib.api.totemtrainer.event;

import net.exylia.lib.api.totemtrainer.Performance;
import net.exylia.lib.api.totemtrainer.TrainingRules;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A solo session is over; the numbers are final and about to be stored.
 *
 * @since 1.0.0
 */
public final class TrainingEndEvent extends TotemTrainerEvent {

    /** Why the session ended. */
    public enum Reason {
        /** No totem in hand when a hit landed. */
        FAILED,
        /** A bounded mode ran out of totems. */
        COMPLETED,
        /** The player left on purpose. */
        LEFT,
        DISCONNECTED,
        /** Another plugin took the player, or the server is stopping. */
        CANCELLED
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final TrainingRules rules;
    private final Performance summary;
    private final Reason reason;

    public TrainingEndEvent(@NotNull Player player, @NotNull TrainingRules rules,
                            @NotNull Performance summary, @NotNull Reason reason) {
        this.player = player;
        this.rules = rules;
        this.summary = summary;
        this.reason = reason;
    }

    public @NotNull Player player() {
        return player;
    }

    public @NotNull TrainingRules rules() {
        return rules;
    }

    public @NotNull Performance summary() {
        return summary;
    }

    public @NotNull Reason reason() {
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
