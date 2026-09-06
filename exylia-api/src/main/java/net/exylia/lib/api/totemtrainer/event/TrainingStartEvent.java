package net.exylia.lib.api.totemtrainer.event;

import net.exylia.lib.api.totemtrainer.TrainingRules;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A solo session was accepted; the player is about to be moved and equipped.
 *
 * @since 1.0.0
 */
public final class TrainingStartEvent extends TotemTrainerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final TrainingRules rules;

    public TrainingStartEvent(@NotNull Player player, @NotNull TrainingRules rules) {
        this.player = player;
        this.rules = rules;
    }

    public @NotNull Player player() {
        return player;
    }

    public @NotNull TrainingRules rules() {
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
