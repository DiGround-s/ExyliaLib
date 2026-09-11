package net.exylia.lib.api.classes.event;

import net.exylia.lib.api.classes.PlayerClass;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A class is about to take effect on a player.
 *
 * <p>Fired at the end of the warm-up, the moment the class would start: the
 * player is wearing the armor, holds the permission and has waited. Nothing of
 * the class is applied yet — no passive effects, no energy bar — so an arena
 * that allows no classes can refuse one here however the player got dressed.
 *
 * <p>Cancelling says nothing and leaves the player dressed for the class but
 * not in it, until their armor changes again. The handler that refused the
 * class is expected to tell them why.
 *
 * <p>Called on the thread that owns the player, which on Folia is their region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.3.0
 */
public class ClassEquipEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final PlayerClass playerClass;

    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player      who is entering the class
     * @param playerClass the class they are entering
     */
    public ClassEquipEvent(@NotNull Player player, @NotNull PlayerClass playerClass) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.playerClass = playerClass;
    }

    /**
     * The player entering the class.
     *
     * @return the player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * The class about to take effect.
     *
     * @return the class
     */
    @NotNull
    public PlayerClass getPlayerClass() {
        return playerClass;
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
