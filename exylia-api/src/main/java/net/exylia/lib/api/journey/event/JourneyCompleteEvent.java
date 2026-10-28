package net.exylia.lib.api.journey.event;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;


/**
 * A player finished a whole journey.
 *
 * <p>Fired once, after the completion rewards were handed over and the
 * completion was recorded. Not cancellable. Called on the player's own
 * thread.
 *
 * @since 3.4.0
 */
public class JourneyCompleteEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String journeyId;
    private final int completions;
    private final long durationMillis;

    /**
     * Creates the event.
     *
     * @param player         who finished it
     * @param journeyId      the journey
     * @param completions    how many times this player has finished it, this one included
     * @param durationMillis how long this run took, from its start
     */
    public JourneyCompleteEvent(@NotNull Player player, @NotNull String journeyId, int completions,
                                long durationMillis) {
        super(player, !Bukkit.isPrimaryThread());
        this.journeyId = journeyId;
        this.completions = completions;
        this.durationMillis = durationMillis;
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
     * How many times this player has finished it.
     *
     * @return at least {@code 1}
     */
    public int getCompletions() {
        return completions;
    }

    /**
     * How long this run took.
     *
     * @return milliseconds from its start to now
     */
    public long getDurationMillis() {
        return durationMillis;
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
