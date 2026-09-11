package net.exylia.lib.api.totems.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A dying player carries a totem, and ExyliaTotems is about to hold their death
 * open to see whether they reached for it in time.
 *
 * <p>Holding a death is intrusive: the {@code PlayerDeathEvent} is cancelled,
 * its drops, experience and death message are cleared, and the player stays
 * at zero health for a few ticks until the plugin either saves them —
 * {@link PlayerTotemSaveEvent} — or kills them again for real. A plugin that
 * runs its own deaths, a duel or an event with a no-totem rule, wants none of
 * that.
 *
 * <p>Cancel to leave this death alone. Nothing is touched and the death goes
 * ahead as the server decided; only the late-totem rescue is given up.
 *
 * <p>Not fired for a death no totem could undo — the void, {@code /kill},
 * outside survival and adventure — nor for a player carrying no totem, nor for
 * the second death that settles a hold that did not save them.
 *
 * <p>Fired on the thread that owns the player, from inside the plugin's
 * lowest-priority {@code PlayerDeathEvent} listener.
 *
 * @since 1.3.0
 */
public class PlayerTotemHoldEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player the player whose death is about to be held
     */
    public PlayerTotemHoldEvent(@NotNull Player player) {
        this.player = player;
    }

    /**
     * The player whose death is about to be held.
     *
     * @return the player, dead as far as the server knows
     */
    @NotNull
    public Player player() {
        return player;
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
