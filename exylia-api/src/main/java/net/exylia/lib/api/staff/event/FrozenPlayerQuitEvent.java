package net.exylia.lib.api.staff.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A frozen player left the server.
 *
 * <p>Logging out is how a player refuses a screenshare, and this is the hook
 * for what a server does about it — a ban from a punishment plugin, an entry
 * in an anticheat's history, a message to a Discord channel. The plugin's own
 * configured quit commands have already run by the time a listener sees it.
 *
 * <p>Fired after the freeze has ended: a disconnect releases the player, so
 * {@link net.exylia.lib.api.staff.StaffService#isFrozen(UUID)} already answers
 * {@code false}. Not fired when the server is shutting down, which keeps every
 * freeze for the restart rather than treating everybody as having run.
 *
 * <p>Not cancellable: the player is already gone. Called on the thread that
 * handles the quit.
 *
 * @since 1.3.0
 */
public final class FrozenPlayerQuitEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final UUID staffId;
    private final String staffName;

    /**
     * @param player    the frozen player who left
     * @param staffId   the staff member who froze them, or {@code null} for the console
     * @param staffName the name of whoever froze them
     */
    public FrozenPlayerQuitEvent(@NotNull Player player, @Nullable UUID staffId, @NotNull String staffName) {
        this.player = player;
        this.staffId = staffId;
        this.staffName = staffName;
    }

    /**
     * The player who left while frozen.
     *
     * <p>Still readable for their name and id, but offline: do not send them
     * anything or teleport them.
     *
     * @return the player
     */
    public @NotNull Player getPlayer() {
        return player;
    }

    /**
     * The staff member who froze them.
     *
     * <p>They may be on another server of the network by now.
     *
     * @return their id, or {@code null} when the console did it
     */
    public @Nullable UUID getStaffId() {
        return staffId;
    }

    /**
     * The name of whoever froze them.
     *
     * @return the staff member's name, or the console's
     */
    public @NotNull String getStaffName() {
        return staffName;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * Bukkit's handler list for this event.
     *
     * @return the handler list
     */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
