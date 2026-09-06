package net.exylia.lib.api.totems.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A totem was honoured for a player the server had already killed.
 *
 * <p>ExyliaTotems cancels the death of a player who reached for a totem a few
 * milliseconds too late, then applies the totem itself. Because the death was
 * cancelled and the totem never really fired, none of the vanilla signals a
 * plugin would normally watch — {@code PlayerDeathEvent},
 * {@code EntityResurrectEvent} — arrive. This event is the only notification
 * that a save happened.
 *
 * <p>Fired after the save is complete: the health is back, the effects are on,
 * the totem has been taken out of the inventory and the statistic has been
 * counted. Nothing here can undo any of that, which is why the event is not
 * cancellable — by the time the plugin knows which totem the player meant, it
 * has already used it.
 *
 * <p>Called on the thread that owns the player, which on Folia is their region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.0.0
 */
public class PlayerTotemSaveEvent extends Event {

    /** The inventory slot that stands for the off hand. */
    public static final int OFF_HAND_SLOT = 40;

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final int slot;

    /**
     * Creates the event.
     *
     * @param player the player who was saved
     * @param slot   the inventory slot the totem was taken from,
     *               {@link #OFF_HAND_SLOT} for the off hand
     */
    public PlayerTotemSaveEvent(@NotNull Player player, int slot) {
        this.player = player;
        this.slot = slot;
    }

    /**
     * The player who was saved.
     *
     * @return the player, alive again with the totem effects applied
     */
    @NotNull
    public Player player() {
        return player;
    }

    /**
     * The inventory slot the totem was taken from.
     *
     * <p>A hotbar index for a held totem, or {@link #OFF_HAND_SLOT} when the
     * player swapped to their off hand. The slot is where the totem was, not
     * where it is: it has already been consumed.
     *
     * @return the slot
     */
    public int slot() {
        return slot;
    }

    /**
     * Whether the totem came from the off hand.
     *
     * @return {@code true} when the player pressed F rather than changing
     *         hotbar slot
     */
    public boolean fromOffHand() {
        return slot == OFF_HAND_SLOT;
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
