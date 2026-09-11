package net.exylia.lib.api.capture.event;

import net.exylia.lib.api.capture.CaptureConfig;
import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A capture run is about to start.
 *
 * <p>Fired once the config has been found, is enabled and complete, is not
 * already running and names a mode the plugin knows — and before the run
 * registers a zone, puts up a display or runs a start command. Cancelling
 * therefore leaves nothing behind, and
 * {@link net.exylia.lib.api.capture.CaptureService#start(String)} answers empty.
 *
 * <p>Every start comes through here: a command, the schedule, the admin menu
 * and the service alike. A cancelled start is logged like any other refusal,
 * and an admin who started it by command is told it failed without a reason,
 * so a handler that cancels should say why itself.
 *
 * <p>Called on the thread that asked for the start: the sender's own for a
 * command, the global thread for the schedule. On Folia neither is a single
 * main thread, so anything touching the wider world has to be scheduled.
 *
 * @since 1.3.0
 */
public class CaptureStartEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final CaptureConfig config;

    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param config what is being started
     */
    public CaptureStartEvent(@NotNull CaptureConfig config) {
        super(!Bukkit.isPrimaryThread());
        this.config = config;
    }

    /**
     * What is being started.
     *
     * <p>The run will carry this config's id, so it is also the id the run can
     * be looked up and stopped by once it exists.
     *
     * @return the config
     */
    @NotNull
    public CaptureConfig getConfig() {
        return config;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
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
