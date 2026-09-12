package net.exylia.lib.api.aimtrainer.event;

import net.exylia.lib.api.aimtrainer.AimMatch;
import net.exylia.lib.api.aimtrainer.AimRound;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A round was scored. A draw has no winner and is replayed.
 *
 * @since 1.5.0
 */
public class AimRoundEndEvent extends AimTrainerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final AimMatch match;
    private final AimRound round;

    public AimRoundEndEvent(@NotNull AimMatch match, @NotNull AimRound round) {
        this.match = match;
        this.round = round;
    }

    public @NotNull AimMatch match() {
        return match;
    }

    public @NotNull AimRound round() {
        return round;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
