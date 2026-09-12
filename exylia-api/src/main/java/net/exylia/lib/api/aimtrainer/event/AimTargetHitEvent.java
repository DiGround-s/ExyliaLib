package net.exylia.lib.api.aimtrainer.event;

import net.exylia.lib.api.aimtrainer.AimGrade;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A target was hit.
 *
 * @since 1.5.0
 */
public class AimTargetHitEvent extends AimTrainerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final long millis;
    private final AimGrade grade;
    private final boolean reaction;
    private final boolean inMatch;

    /**
     * @param millis   the flick time since the previous hit, or the reaction time; negative for a first hit
     * @param grade    how that time was graded, or {@code null} when it was not
     * @param reaction whether the time is a reaction rather than a flick
     * @param inMatch  whether the hit happened in a duel
     */
    public AimTargetHitEvent(@NotNull Player player, long millis, @Nullable AimGrade grade, boolean reaction,
                             boolean inMatch) {
        this.player = player;
        this.millis = millis;
        this.grade = grade;
        this.reaction = reaction;
        this.inMatch = inMatch;
    }

    public @NotNull Player player() {
        return player;
    }

    public long millis() {
        return millis;
    }

    public @Nullable AimGrade grade() {
        return grade;
    }

    public boolean reaction() {
        return reaction;
    }

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
