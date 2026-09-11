package net.exylia.lib.api.ffa.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is leaving an arena.
 *
 * <p>Fired once the leave has been accepted — a player tagged in combat who
 * asks to leave is refused before this point and fires nothing — and before
 * their inventory and location are handed back, which happen over the next
 * ticks. Not cancellable: a disconnect, a kick and another mode taking the
 * player all arrive here too, and none of those can be undone.
 *
 * <p>Synchronous, on the thread that owns the player.
 *
 * @since 1.3.0
 */
public class FfaLeaveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String arenaId;
    private final boolean forced;

    /**
     * Creates the event.
     *
     * @param player  the player leaving
     * @param arenaId the arena they are leaving
     * @param forced  whether the leave skipped the combat-tag check
     */
    public FfaLeaveEvent(@NotNull Player player, @NotNull String arenaId, boolean forced) {
        this.player = player;
        this.arenaId = arenaId;
        this.forced = forced;
    }

    /**
     * The player leaving.
     *
     * <p>May already be offline when the leave is a disconnect.
     *
     * @return the player
     */
    @NotNull
    public Player player() {
        return player;
    }

    /**
     * The arena they are leaving.
     *
     * @return the arena id
     */
    @NotNull
    public String arenaId() {
        return arenaId;
    }

    /**
     * Whether the leave skipped the combat-tag check.
     *
     * <p>{@code true} for a disconnect, a kick, and a death on a server that
     * sends the dead home. {@code false} for everything that could have been
     * refused — the player's own leave, another mode asking for the player, an
     * administrator emptying the arena — and only those store an arena's
     * inventory for next time.
     *
     * @return {@code true} when the leave was forced
     */
    public boolean forced() {
        return forced;
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
