package net.exylia.lib.api.journey.event;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;


/**
 * A player skipped a step, or a whole journey.
 *
 * <p>Fired after the skip took effect: a skipped step pays nothing and the
 * next one starts; a skipped journey is recorded as skipped and pays nothing.
 * Not cancellable &mdash; whether skipping is allowed is the journey's own
 * setting. Called on the player's own thread.
 *
 * @since 3.4.0
 */
public class JourneySkipEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String journeyId;
    private final String stepId;
    private final int stepIndex;

    /**
     * Creates the event.
     *
     * @param player    who skipped
     * @param journeyId the journey
     * @param stepId    the step skipped, or {@code null} when the whole journey was
     * @param stepIndex that step's index, or the step the journey was on when it was skipped
     */
    public JourneySkipEvent(@NotNull Player player, @NotNull String journeyId, @Nullable String stepId,
                            int stepIndex) {
        super(player, !Bukkit.isPrimaryThread());
        this.journeyId = journeyId;
        this.stepId = stepId;
        this.stepIndex = stepIndex;
    }

    /**
     * The journey.
     *
     * @return its id
     */
    @NotNull
    public String getJourneyId() {
        return journeyId;
    }

    /**
     * Whether the whole journey was skipped rather than one step.
     *
     * @return {@code true} for the whole journey
     */
    public boolean isWholeJourney() {
        return stepId == null;
    }

    /**
     * The step skipped.
     *
     * @return its id, or {@code null} when the whole journey was skipped
     */
    @Nullable
    public String getStepId() {
        return stepId;
    }

    /**
     * The step skipped, or the one the journey was on.
     *
     * @return its index, from {@code 0}
     */
    public int getStepIndex() {
        return stepIndex;
    }

    @Override
    @NotNull
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * The handler list Bukkit requires.
     *
     * @return the handler list
     */
    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
