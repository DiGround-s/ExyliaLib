package net.exylia.lib.api.totemtrainer.event;

import net.exylia.lib.api.totemtrainer.PerformanceGrade;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A totem popped and the player got the next one into a hand. The reaction
 * is measured from the hit to the re-equip, not from the pop animation.
 *
 * @since 1.0.0
 */
public final class TotemPopEvent extends TotemTrainerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final long reactionMillis;
    private final PerformanceGrade grade;
    private final boolean inMatch;

    public TotemPopEvent(@NotNull Player player, long reactionMillis,
                         @NotNull PerformanceGrade grade, boolean inMatch) {
        this.player = player;
        this.reactionMillis = reactionMillis;
        this.grade = grade;
        this.inMatch = inMatch;
    }

    public @NotNull Player player() {
        return player;
    }

    public long reactionMillis() {
        return reactionMillis;
    }

    public @NotNull PerformanceGrade grade() {
        return grade;
    }

    /** {@code false} in solo training. */
    public boolean inMatch() {
        return inMatch;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
