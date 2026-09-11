package net.exylia.lib.api.shields.event;

import net.exylia.lib.api.shields.ShieldDesign;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A player is about to copy a design out of the shared library.
 *
 * <p>Fired for a click in the library browser, for an id typed into the import
 * prompt, and for
 * {@link net.exylia.lib.api.shields.ShieldsService#importDesign(Player, long)}.
 * Only once the copy would go through — the player has a free slot and the
 * library row was read — and before anything is written, so a handler charging
 * for a design or keeping one exclusive decides with the design in hand.
 *
 * <p>Cancelling copies nothing, does not count a use against the design, and
 * says nothing to the player: the handler that refused is the only one that
 * knows why.
 *
 * <p>Called on the thread that owns the player, which on Folia is their region
 * thread rather than a single main thread.
 *
 * @since 1.3.0
 */
public final class ShieldDesignImportEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final long libraryId;
    private final int slot;
    private final ShieldDesign design;
    private boolean cancelled;

    /**
     * @param player    who is taking the copy
     * @param libraryId the library row it comes from
     * @param slot      the slot it will land in, counted from zero
     * @param design    the design being copied
     */
    public ShieldDesignImportEvent(@NotNull Player player, long libraryId, int slot,
                                   @NotNull ShieldDesign design) {
        super(player, !Bukkit.isPrimaryThread());
        this.libraryId = libraryId;
        this.slot = slot;
        this.design = design;
    }

    /**
     * The library row the design comes from.
     *
     * @return the library id
     */
    public long libraryId() {
        return libraryId;
    }

    /**
     * Where the copy will land: the player's first free slot.
     *
     * @return the slot number, counted from zero
     */
    public int slot() {
        return slot;
    }

    /**
     * The design being copied, as the library holds it.
     *
     * @return the design
     */
    public @NotNull ShieldDesign design() {
        return design;
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
