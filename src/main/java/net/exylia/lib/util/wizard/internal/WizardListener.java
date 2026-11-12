package net.exylia.lib.util.wizard.internal;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.entity.Player;
import net.exylia.lib.npc.PlacedNpcClickEvent;
import net.exylia.lib.util.wizard.WizardTarget;

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
     * Feeds a click to a run waiting for one.
     *
     * <p>Both buttons count. A player told to "click the spawn block" reaches
     * for whichever one they habitually use, and a prompt that only accepts one
     * of them looks broken to half the server. Cancelling the event is what
     * makes accepting both safe: the left click does not start breaking the
     * block and the right click does not open the chest.
     *
     * <p>Clicks at air are forwarded too, with a {@code null} block. Only the
     * standing gesture answers to one &mdash; a player standing in the open with
     * nothing in front of them has no block to click, and refusing them here
     * would make the step unanswerable exactly where it is most useful. The
     * session is what decides; this listener holds no policy.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK
                && action != Action.LEFT_CLICK_AIR && action != Action.RIGHT_CLICK_AIR) {
            return;
        }
        WizardSession session = WizardRuntime.sessionOf(event.getPlayer().getUniqueId());
        if (session == null) {
            return;
        }
        if (session.interacted(event.getClickedBlock(), event.getPlayer().isSneaking())) {
            event.setCancelled(true);
        }
    }

    /**
     * Feeds an entity right-click to a pick that takes entities
     * ({@link net.exylia.lib.util.wizard.PluginWizards#askTarget}); other picks ignore it.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (WizardRuntime.target(event.getPlayer().getUniqueId(), new WizardTarget(
                event.getRightClicked().getLocation(), event.getRightClicked().getUniqueId(), null))) {
            event.setCancelled(true);
        }
    }

    /** The same for a left click, which reaches the server as a hit. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (WizardRuntime.target(player.getUniqueId(), new WizardTarget(
                event.getEntity().getLocation(), event.getEntity().getUniqueId(), null))) {
            event.setCancelled(true);
        }
    }

    /** The same for a library placed NPC, which is packets only and fires no entity event. */
    @EventHandler
    public void onNpc(PlacedNpcClickEvent event) {
        WizardRuntime.target(event.getPlayer().getUniqueId(), new WizardTarget(
                event.npc().location(), null, event.owner().getName() + ":" + event.npcId()));
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
