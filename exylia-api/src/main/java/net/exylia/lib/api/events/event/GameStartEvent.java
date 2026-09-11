package net.exylia.lib.api.events.event;

import net.exylia.lib.api.events.EventDefinition;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A run of a configured event is about to open.
 *
 * <p>Fired once every check the plugin makes has passed — the definition is
 * enabled, fully set up and this server's to play, it is not already running,
 * and the server's limits on running and waiting events leave room — and
 * before the run exists. Cancelling therefore leaves nothing behind: no run, no
 * broadcast, no slot taken in those limits and no cooldown on whoever started
 * it.
 *
 * <p>Every start comes through here — a command, the admin menu, the
 * timetable, the random scheduler, a request from another server and
 * {@link net.exylia.lib.api.events.EventsService#start(String)} alike — which
 * makes it the one place to hold events back during maintenance or to meter
 * them. The plugin cannot know why a handler refused, so a handler that
 * cancels should tell the starter itself.
 *
 * <p>Called on the thread that asked for the start: the starter's own for a
 * command or a menu, the global thread for a schedule. On Folia neither is a
 * single main thread, so anything touching the wider world has to be
 * scheduled.
 *
 * @since 1.3.0
 */
public class GameStartEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final EventDefinition definition;
    private final Player starter;

    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param definition what is being started
     * @param starter    the player credited with starting it, or {@code null}
     */
    public GameStartEvent(@NotNull EventDefinition definition, @Nullable Player starter) {
        super(!Bukkit.isPrimaryThread());
        this.definition = definition;
        this.starter = starter;
    }

    /**
     * What is being started.
     *
     * @return the definition the run will be built from
     */
    @NotNull
    public EventDefinition getDefinition() {
        return definition;
    }

    /**
     * The player credited with starting it.
     *
     * <p>The one the per-player limits and the start cooldown apply to, and the
     * one who is put into the run as soon as it opens.
     *
     * @return the starter, empty when no player is credited, as for a schedule
     *         or an API call
     */
    @NotNull
    public Optional<Player> getStarter() {
        return Optional.ofNullable(starter);
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
