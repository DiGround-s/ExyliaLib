package net.exylia.lib.api.survival.event;

import net.exylia.lib.api.survival.SurvivalKit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is claiming a kit.
 *
 * <p>Fired once every check has passed — the kit being enabled, the player's
 * permission, their cooldown, their remaining uses and the room in their
 * inventory — and before a single item is handed over. Unlike the teleport
 * events, an uncancelled event does mean the claim happens: nothing after this
 * point can refuse it.
 *
 * <p>Cancelling stops all of it. No items, no reward commands, no cooldown
 * written and no use counted, so a refused claim leaves the player able to try
 * again. They are told nothing, because the handler that refused the claim is
 * the only one that knows why.
 *
 * <p>The kit's contents are deliberately absent from {@link SurvivalKit}: they
 * belong to the plugin and are rewritten whenever an administrator edits the
 * kit. Change what a player receives by cancelling this and giving them
 * something yourself.
 *
 * <p>Called on the thread that owns the player, which on Folia is their region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.1.0
 */
public class KitClaimEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final SurvivalKit kit;

    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player the player claiming
     * @param kit    what they are claiming
     */
    public KitClaimEvent(@NotNull Player player, @NotNull SurvivalKit kit) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.kit = kit;
    }

    /**
     * The player claiming.
     *
     * @return the player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * What they are claiming.
     *
     * @return the kit
     */
    @NotNull
    public SurvivalKit getKit() {
        return kit;
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
