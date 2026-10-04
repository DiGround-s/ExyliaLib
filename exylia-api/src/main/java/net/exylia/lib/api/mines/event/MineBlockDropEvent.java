package net.exylia.lib.api.mines.event;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A mine is about to hand a player the items a broken block gave.
 *
 * <p>Inside a mine the server's {@link org.bukkit.event.block.BlockBreakEvent}
 * is cancelled and the mine works out the drops itself, so neither
 * {@link org.bukkit.event.block.BlockDropItemEvent} nor the vanilla drop path
 * runs. This is where a plugin that changes what a tool drops — smelting it,
 * collecting it — takes part instead.
 *
 * <p>Fired for the block's own drops and for every item a mine's loot table
 * rolls, after Fortune and every booster were applied and before the items are
 * sold, put in the inventory or dropped. The list is the one the mine delivers:
 * replacing, adding or removing stacks changes what the player receives.
 *
 * <p>Called on the thread that owns the block, which on Folia is its region
 * thread rather than a single main thread.
 *
 * @since 1.240.0
 */
public class MineBlockDropEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Location location;
    private final List<ItemStack> drops;

    private boolean autoPickup;

    /**
     * Creates the event.
     *
     * @param player     who broke the block
     * @param location   where the block was
     * @param drops      the items about to be delivered, mutable
     * @param autoPickup whether they go straight into the inventory
     */
    public MineBlockDropEvent(@NotNull Player player, @NotNull Location location, @NotNull List<ItemStack> drops,
                              boolean autoPickup) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.location = location;
        this.drops = drops;
        this.autoPickup = autoPickup;
    }

    /**
     * The player who broke the block.
     *
     * @return the player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * Where the block was.
     *
     * @return the block's location
     */
    @NotNull
    public Location getLocation() {
        return location;
    }

    /**
     * The items the mine is about to deliver.
     *
     * @return the mutable list of drops
     */
    @NotNull
    public List<ItemStack> getDrops() {
        return drops;
    }

    /**
     * Whether the drops go straight into the player's inventory, overflow
     * falling on the ground. Auto-sell still comes first either way.
     *
     * @return {@code true} if they are picked up
     */
    public boolean isAutoPickup() {
        return autoPickup;
    }

    /**
     * Sends the drops straight into the player's inventory, or onto the ground.
     *
     * @param autoPickup {@code true} to pick them up
     */
    public void setAutoPickup(boolean autoPickup) {
        this.autoPickup = autoPickup;
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
