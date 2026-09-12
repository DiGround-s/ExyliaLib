package net.exylia.lib.api.aimtrainer.event;

import net.exylia.lib.api.aimtrainer.AimRules;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A solo drill was accepted for a player. Fired before they are moved.
 *
 * @since 1.5.0
 */
public class AimTrainingStartEvent extends AimTrainerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final AimRules rules;

    public AimTrainingStartEvent(@NotNull Player player, @NotNull AimRules rules) {
        this.player = player;
        this.rules = rules;
    }

    public @NotNull Player player() {
        return player;
    }

    public @NotNull AimRules rules() {
        return rules;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
