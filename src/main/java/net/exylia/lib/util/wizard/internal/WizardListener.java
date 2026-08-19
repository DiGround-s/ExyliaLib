package net.exylia.lib.util.wizard.internal;

import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * The module's single Bukkit listener.
 *
 * <p>One listener for every wizard on the server, not one per run. Registering
 * a handler per session is how a plugin ends up with a thousand listeners after
 * an evening of players opening menus, and how a listener belonging to a
 * finished flow ends up answering somebody else's click.
 *
 * <p>It contains no policy: it finds the player's active session, if any, and
 * hands it the event. Whether the click means anything is the session's
 * business, because only the session knows which step it is on.
 */
final class WizardListener implements Listener {

    /**
     * Feeds a block click to a run waiting for one.
     *
     * <p>Both buttons count. A player told to "click the spawn block" reaches
     * for whichever one they habitually use, and a prompt that only accepts one
     * of them looks broken to half the server. Cancelling the event is what
     * makes accepting both safe: the left click does not start breaking the
     * block and the right click does not open the chest.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        WizardSession session = WizardRuntime.sessionOf(event.getPlayer().getUniqueId());
        if (session == null) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        if (session.blockClicked(block)) {
            event.setCancelled(true);
        }
    }

    /**
     * Ends a leaving player's run.
     *
     * <p>The library's own quit handler calls {@link WizardRuntime#forget} too;
     * this is here so the module keeps working when it is driven by its own
     * {@code init}, and both paths are idempotent because a run's terminal slot
     * is claimed exactly once.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        WizardRuntime.forget(event.getPlayer().getUniqueId());
    }
}
