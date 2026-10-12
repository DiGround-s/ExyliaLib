package net.exylia.lib.api.market.event;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is about to sell a container's contents with a sell wand.
 *
 * <p>Fired before anything is sold or the click is taken. Cancel it for a
 * container that decides itself when it opens, such as a loot chest or a
 * supply drop crate: the wand then does nothing and the click goes on as a
 * plain open, so that container's own rules answer it.
 *
 * <p>Called on the player's own thread, which on Folia is their region thread.
 *
 * @since 1.11.0
 */
public class SellWandUseEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Block block;
    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player who holds the wand
     * @param block  the container clicked
     */
    public SellWandUseEvent(@NotNull Player player, @NotNull Block block) {
        this.player = player;
        this.block = block;
    }

    /** Who holds the wand. */
    public @NotNull Player getPlayer() {
        return player;
    }

    /** The container clicked. */
    public @NotNull Block getBlock() {
        return block;
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
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * The handler list Bukkit requires.
     *
     * @return the handler list
     */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
