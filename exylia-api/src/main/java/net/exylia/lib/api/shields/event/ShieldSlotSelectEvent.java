package net.exylia.lib.api.shields.event;

import net.exylia.lib.api.shields.ShieldDesign;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A player is about to wear another of their shield slots.
 *
 * <p>Fired for a click in the slots menu and for
 * {@link net.exylia.lib.api.shields.ShieldsService#selectSlot(Player, int)},
 * before the slot is switched and before the shield in their hands is redrawn.
 * Only for a player whose slots are loaded: for anyone else the switch was going
 * to do nothing anyway.
 *
 * <p>Cancelling leaves them on the slot they had and says nothing — not even the
 * line that normally confirms the switch. The handler that refused is the one
 * that knows why.
 *
 * <p>Called on the thread that owns the player, which on Folia is their region
 * thread rather than a single main thread.
 *
 * @since 1.3.0
 */
public final class ShieldSlotSelectEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int slot;
    private final ShieldDesign design;
    private boolean cancelled;

    /**
     * @param player who is switching
     * @param slot   the slot they are switching to, counted from zero
     * @param design what that slot holds, or {@code null} when it is empty
     */
    public ShieldSlotSelectEvent(@NotNull Player player, int slot, @Nullable ShieldDesign design) {
        super(player, !Bukkit.isPrimaryThread());
        this.slot = slot;
        this.design = design;
    }

    /**
     * The slot about to be worn.
     *
     * @return the slot number, counted from zero
     */
    public int slot() {
        return slot;
    }

    /**
     * What the slot holds.
     *
     * @return the design, or empty when the slot is empty: switching to one
     *         leaves the shield plain
     */
    public @NotNull Optional<ShieldDesign> design() {
        return Optional.ofNullable(design);
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
     * @return the handler list Bukkit registers against
     */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
