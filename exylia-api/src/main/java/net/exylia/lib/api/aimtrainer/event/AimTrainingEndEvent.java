package net.exylia.lib.api.aimtrainer.event;

import net.exylia.lib.api.aimtrainer.AimPerformance;
import net.exylia.lib.api.aimtrainer.AimRules;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A solo drill ended. Fired with the final numbers, before they are written.
 *
 * <p>A {@link Reason#COMPLETED} drill keeps the player in the arena for a
 * moment, from where a punch starts another; every other reason has them on
 * their way home.
 *
 * @since 1.5.0
 */
public class AimTrainingEndEvent extends AimTrainerEvent {

    /** Why it ended. */
    public enum Reason {
        /** The drill ran its course. */
        COMPLETED,
        /** The player asked to leave. */
        LEFT,
        /** Another plugin, an administrator or a shutdown took the player back. */
        CANCELLED,
        /** The player disconnected. */
        DISCONNECTED,
        /** Practice found them a match while they trained. */
        MATCH_FOUND
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final AimRules rules;
    private final AimPerformance performance;
    private final Reason reason;

    public AimTrainingEndEvent(@NotNull Player player, @NotNull AimRules rules, @NotNull AimPerformance performance,
                               @NotNull Reason reason) {
        this.player = player;
        this.rules = rules;
        this.performance = performance;
        this.reason = reason;
    }

    public @NotNull Player player() {
        return player;
    }

    public @NotNull AimRules rules() {
        return rules;
    }

    public @NotNull AimPerformance performance() {
        return performance;
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
