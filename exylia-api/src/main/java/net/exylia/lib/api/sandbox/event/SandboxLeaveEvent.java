package net.exylia.lib.api.sandbox.event;

import net.exylia.lib.api.sandbox.LeaveReason;
import net.exylia.lib.api.sandbox.SandboxWorld;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is out of the sandbox.
 *
 * <p>Fired once the sandbox has let go of them — their inventory given back,
 * unless the practice lobby dresses them instead, and their claim released for
 * the rest of the server — so
 * {@link net.exylia.lib.api.sandbox.SandBoxService#isInSandbox(java.util.UUID)}
 * already answers {@code false}.
 *
 * <p>Only a player who was in a world fires this. Leaving a queue does not, and
 * neither does a way in that failed before the player arrived.
 *
 * <p>Called on the thread that owns the player, which on Folia is their region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.3.0
 */
public class SandboxLeaveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final SandboxWorld world;
    private final LeaveReason reason;

    /**
     * Creates the event.
     *
     * @param player the player who left
     * @param world  the world they left
     * @param reason how they came to leave
     */
    public SandboxLeaveEvent(@NotNull Player player, @NotNull SandboxWorld world,
                             @NotNull LeaveReason reason) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.world = world;
        this.reason = reason;
    }

    /**
     * The player who left.
     *
     * <p>For {@link LeaveReason#DISCONNECTED} still the live player of the quit,
     * and gone right after it.
     *
     * @return the player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * The world they left.
     *
     * @return a snapshot of the world
     */
    @NotNull
    public SandboxWorld getWorld() {
        return world;
    }

    /**
     * How they came to leave.
     *
     * @return the reason
     */
    @NotNull
    public LeaveReason getReason() {
        return reason;
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
