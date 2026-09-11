package net.exylia.lib.api.sandbox.event;

import net.exylia.lib.api.sandbox.SandboxWorld;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is asking to be taken into a sandbox world.
 *
 * <p>Fired when a player asks for a world — by random teleport, or by joining a
 * world's queue — and before anything is taken from them: no session claim, no
 * inventory put aside, no teleport, no place in a queue. Cancelling therefore
 * leaves the player exactly as they were. The plugin says nothing to a player
 * whose join was cancelled; telling them why is the canceller's job.
 *
 * <p>A queue asks once, when the player joins it, and not again when a partner
 * is found: refusing at that point would send the partner in alone. Moving from
 * one sandbox world to another does not ask at all, because the player never
 * leaves the sandbox's hands.
 *
 * <p>An uncancelled event is not an arrival: the way in can still fail after
 * it. {@link SandboxArriveEvent} is the confirmation.
 *
 * <p>Called on the thread that owns the player, which on Folia is their region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.3.0
 */
public class SandboxJoinEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final SandboxWorld world;
    private final int kitSlot;
    private final boolean queued;

    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player  the player asking
     * @param world   the world they asked for
     * @param kitSlot the kit slot to dress them in, or {@code -1}
     * @param queued  whether they are joining the world's queue
     */
    public SandboxJoinEvent(@NotNull Player player, @NotNull SandboxWorld world,
                            int kitSlot, boolean queued) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.world = world;
        this.kitSlot = kitSlot;
        this.queued = queued;
    }

    /**
     * The player asking.
     *
     * @return the player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * The world they asked for.
     *
     * @return a snapshot of the world
     */
    @NotNull
    public SandboxWorld getWorld() {
        return world;
    }

    /**
     * The kit they are to be dressed in on arrival.
     *
     * @return the kit slot, {@code -1} when they chose none
     */
    public int getKitSlot() {
        return kitSlot;
    }

    /**
     * Whether they are joining the world's queue to be paired with somebody,
     * rather than being teleported in on their own straight away.
     *
     * @return {@code true} for a queue
     */
    public boolean isQueued() {
        return queued;
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
