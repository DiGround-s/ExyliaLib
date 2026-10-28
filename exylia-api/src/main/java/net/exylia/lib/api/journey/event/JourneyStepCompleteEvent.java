package net.exylia.lib.api.journey.event;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;


/**
 * A player finished one step of a journey.
 *
 * <p>Fired once per step, after its rewards were handed over and before the
 * next step starts. A step that was skipped fires {@link JourneySkipEvent}
 * instead. Not cancellable: what it reports already happened. Called on the
 * player's own thread.
 *
 * @since 3.4.0
 */
public class JourneyStepCompleteEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String journeyId;
    private final int stepIndex;
    private final String stepId;
    private final boolean last;

    /**
     * Creates the event.
     *
     * @param player    who finished it
     * @param journeyId the journey
     * @param stepIndex the step finished, from {@code 0}
     * @param stepId    its id
     * @param last      whether it was the journey's last step
     */
    public JourneyStepCompleteEvent(@NotNull Player player, @NotNull String journeyId, int stepIndex,
                                    @NotNull String stepId, boolean last) {
        super(player, !Bukkit.isPrimaryThread());
        this.journeyId = journeyId;
        this.stepIndex = stepIndex;
        this.stepId = stepId;
        this.last = last;
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
     * The step finished.
     *
     * @return its index, from {@code 0}
     */
    public int getStepIndex() {
        return stepIndex;
    }

    /**
     * The step finished.
     *
     * @return its id
     */
    @NotNull
    public String getStepId() {
        return stepId;
    }

    /**
     * Whether it was the last step; a {@link JourneyCompleteEvent} follows.
     *
     * @return {@code true} for the last step
     */
    public boolean isLastStep() {
        return last;
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
