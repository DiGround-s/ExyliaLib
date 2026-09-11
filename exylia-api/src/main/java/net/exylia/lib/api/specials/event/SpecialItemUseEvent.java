package net.exylia.lib.api.specials.event;

import net.exylia.lib.api.specials.TriggerType;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A special item is about to run its actions.
 *
 * <p>Fired once the plugin has decided the use goes ahead — the region allows
 * it, the player is off cooldown, the stack has uses left, a combo has its hits
 * — and before any action runs, any use is taken off the stack or any cooldown
 * starts. Cancelling therefore costs the player nothing: the item stays as it
 * was and can be used again at once. The plugin says nothing to a player whose
 * use was cancelled; telling them why is the canceller's job.
 *
 * <p>Also fired for {@link net.exylia.lib.api.specials.SpecialsService#activate},
 * where there is no stack, so a region or event plugin that forbids specials
 * catches both with one listener.
 *
 * <p>Synchronous, on the thread that owns the player.
 *
 * @since 1.3.0
 */
public class SpecialItemUseEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String itemId;
    private final TriggerType trigger;
    private final Player target;
    private final ItemStack item;
    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player  who is using the item
     * @param itemId  which special item
     * @param trigger what fired it
     * @param target  the player it acts on, or {@code null}
     * @param item    the stack being used, or {@code null} when there is none
     */
    public SpecialItemUseEvent(@NotNull Player player, @NotNull String itemId,
                               @NotNull TriggerType trigger, @Nullable Player target,
                               @Nullable ItemStack item) {
        this.player = player;
        this.itemId = itemId;
        this.trigger = trigger;
        this.target = target;
        this.item = item;
    }

    /**
     * Who is using the item.
     *
     * @return the player
     */
    @NotNull
    public Player player() {
        return player;
    }

    /**
     * Which special item is being used.
     *
     * @return the item id, lowercase
     */
    @NotNull
    public String itemId() {
        return itemId;
    }

    /**
     * What fired the item, as its definition sets it.
     *
     * @return the trigger
     */
    @NotNull
    public TriggerType trigger() {
        return trigger;
    }

    /**
     * The player the item acts on.
     *
     * <p>Present for a hit, a projectile landing on a player, and the last
     * attacker; absent for an item that acts on its user or on an area.
     *
     * @return the target, or {@code null} when there is none
     */
    @Nullable
    public Player target() {
        return target;
    }

    /**
     * The stack being used.
     *
     * <p>The live stack, not a copy: reading it is fine, changing it changes
     * the player's item. Absent when the item was fired through the service,
     * and for a thrown item whose last copy has already left the inventory.
     *
     * @return the stack, or {@code null} when there is none
     */
    @Nullable
    public ItemStack item() {
        return item;
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
