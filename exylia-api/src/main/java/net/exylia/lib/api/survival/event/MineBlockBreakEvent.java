package net.exylia.lib.api.survival.event;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is breaking a block a mine owns.
 *
 * <p>Inside a mine the server's own {@link org.bukkit.event.block.BlockBreakEvent}
 * is always cancelled, because the mine removes the block itself. A listener
 * that ignores cancelled events therefore never sees a mine break, and this is
 * the event to listen to instead.
 *
 * <p>Fired once the mine has agreed to the break — the player holds its
 * permission and the block is one it manages — and before anything happens to
 * the block, so it still has the type the player was mining. Fired for a
 * player's own swing and for every break another plugin asks for through
 * {@link net.exylia.lib.api.survival.SurvivalService#breakMineBlock(Player, Block)}.
 * A handler that breaks further blocks that way receives this event again for
 * each of them, and has to tell its own breaks apart.
 *
 * <p>Cancelling leaves the block in place and says nothing to the player: the
 * handler that refused the break is the only one that knows why.
 *
 * <p>Called on the thread that owns the block, which on Folia is its region
 * thread rather than a single main thread.
 *
 * @since 1.2.0
 */
public class MineBlockBreakEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String mineId;
    private final Block block;

    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player who is breaking the block
     * @param mineId the mine that owns it
     * @param block  the block, not yet broken
     */
    public MineBlockBreakEvent(@NotNull Player player, @NotNull String mineId, @NotNull Block block) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.mineId = mineId;
        this.block = block;
    }

    /**
     * The player breaking the block.
     *
     * @return the player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * The mine the block belongs to.
     *
     * @return the id the mine was created with
     */
    @NotNull
    public String getMineId() {
        return mineId;
    }

    /**
     * The block being broken, still in the state the player found it.
     *
     * @return the block
     */
    @NotNull
    public Block getBlock() {
        return block;
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
