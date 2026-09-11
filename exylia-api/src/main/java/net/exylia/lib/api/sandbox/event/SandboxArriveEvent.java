package net.exylia.lib.api.sandbox.event;

import net.exylia.lib.api.sandbox.SandboxWorld;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player has landed in a sandbox world and is ready to play.
 *
 * <p>Fired once the teleport has landed and the sandbox has finished with them:
 * survival mode, full health, and their kit on when they chose one. This is the
 * confirmation that neither {@link SandboxJoinEvent} nor an accepted
 * {@link net.exylia.lib.api.sandbox.SandBoxService#enter} is — a way in can
 * still fail after both, when no safe place is found or the teleport does not
 * land.
 *
 * <p>Only arriving from outside the sandbox fires this. Moving between its
 * worlds does not, and neither does logging back in inside one.
 *
 * <p>Called on the thread that owns the player, a couple of ticks after they
 * land. On Folia that is their region thread rather than a single main thread,
 * so anything touching the wider world has to be scheduled.
 *
 * @since 1.3.0
 */
public class SandboxArriveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final SandboxWorld world;

    /**
     * Creates the event.
     *
     * @param player the player who arrived
     * @param world  the world they arrived in
     */
    public SandboxArriveEvent(@NotNull Player player, @NotNull SandboxWorld world) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.world = world;
    }

    /**
     * The player who arrived.
     *
     * @return the player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * The world they arrived in.
     *
     * @return a snapshot of the world
     */
    @NotNull
    public SandboxWorld getWorld() {
        return world;
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
