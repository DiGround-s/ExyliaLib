package net.exylia.lib.api.aimtrainer.event;

import net.exylia.lib.api.aimtrainer.AimMatch;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Both players arrived in the arena and the first countdown is about to run.
 *
 * @since 1.5.0
 */
public class AimMatchStartEvent extends AimTrainerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final AimMatch match;

    public AimMatchStartEvent(@NotNull AimMatch match) {
        this.match = match;
    }

    public @NotNull AimMatch match() {
        return match;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
