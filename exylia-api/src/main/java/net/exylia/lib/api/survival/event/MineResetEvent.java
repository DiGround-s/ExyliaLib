package net.exylia.lib.api.survival.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A mine has been refilled.
 *
 * <p>Fired once the last block is back in place, whether the mine's timer, its
 * emptiness threshold or an administrator asked for the reset — the moment an
 * announcement, a hologram or a "mine is ready" alert wants. Players standing
 * inside have already been lifted clear of the new blocks.
 *
 * <p>Only mines that refill as a whole fire this. A realistic mine grows each
 * block back on its own, so there is no single moment it is full again.
 *
 * <p>Not cancellable: the blocks are already placed. A refill is spread over
 * several ticks so it never stalls the server, which is why this arrives a
 * little after the reset began rather than with it.
 *
 * <p>Called on the thread that owns the mine's last refilled block, which on
 * Folia is that region's thread rather than a single main thread.
 *
 * @since 1.3.0
 */
public class MineResetEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String mineId;

    /**
     * Creates the event.
     *
     * @param mineId the mine that was refilled
     */
    public MineResetEvent(@NotNull String mineId) {
        super(!Bukkit.isPrimaryThread());
        this.mineId = mineId;
    }

    /**
     * The mine that was refilled.
     *
     * @return the id the mine was created with
     */
    @NotNull
    public String getMineId() {
        return mineId;
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
