package net.exylia.lib.api.journey.event;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;


/**
 * A journey is about to start for a player.
 *
 * <p>Fired after every requirement, group and repeat check has passed and
 * before anything is written or shown, so a cancelled start leaves nothing
 * behind. Called on the player's own thread, which on Folia is their region
 * thread.
 *
 * @since 3.4.0
 */
public class JourneyStartEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String journeyId;
    private final String cause;
    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player    who starts it
     * @param journeyId the journey
     * @param cause     what started it: {@code FIRST_JOIN}, {@code JOIN}, {@code COMMAND},
     *                  {@code REGION_ENTER}, {@code NPC_CLICK}, {@code JOURNEY_COMPLETE},
     *                  {@code PERMISSION}, {@code CUSTOM} or {@code API}
     */
    public JourneyStartEvent(@NotNull Player player, @NotNull String journeyId, @NotNull String cause) {
        super(player, !Bukkit.isPrimaryThread());
        this.journeyId = journeyId;
        this.cause = cause;
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
     * What started it.
     *
     * @return the start trigger's name, or {@code API} for {@code JourneyService.start}
     */
    @NotNull
    public String getCause() {
        return cause;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
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
