package net.exylia.lib.tuner.internal;

import net.exylia.lib.tuner.Tuner;
import net.exylia.lib.tuner.TunerEnd;
import net.exylia.lib.tuner.TunerSession;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Optional;

/**
 * Turns a tuning player's wheel, F and clicks into {@link TunerSession} gestures,
 * and refuses everything else those keys would do.
 *
 * <p>The wheel is read from {@link PlayerItemHeldEvent}. The slot change is let
 * through, so each notch is measured from the slot the client really holds;
 * the session gives the original slot back when it ends.
 */
public final class TunerListener implements Listener {

    /** A teleport further than this ends the session: the player left what they were tuning. */
    static final double FAR = 32;

    /**
     * The notch a slot change means: +1 for wheel up, -1 for wheel down, 0 for
     * a jump only a number key makes. The hotbar wraps, so 0 to 8 is wheel up.
     */
    public static int notch(int from, int to) {
        int forward = Math.floorMod(to - from, 9);
        if (forward == 1) return -1;
        if (forward == 8) return 1;
        return 0;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onHeld(PlayerItemHeldEvent event) {
        session(event.getPlayer()).ifPresent(session -> {
            int notch = notch(event.getPreviousSlot(), event.getNewSlot());
            if (notch == 0) return;
            if (event.getPlayer().isSneaking()) {
                session.shiftStep(notch);
            } else {
                session.nudge(notch);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        session(event.getPlayer()).ifPresent(session -> {
            event.setCancelled(true);
            session.cycle(event.getPlayer().isSneaking() ? -1 : 1);
        });
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL) return;
        session(event.getPlayer()).ifPresent(session -> {
            event.setCancelled(true);
            if (event.getHand() == EquipmentSlot.OFF_HAND) return;
            boolean left = event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK;
            if (left && !event.getPlayer().isSneaking()) {
                session.save();
            } else {
                session.snap();
            }
        });
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            session(player).ifPresent(session -> {
                event.setCancelled(true);
                if (player.isSneaking()) session.snap(); else session.save();
            });
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        refuse(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlace(BlockPlaceEvent event) {
        refuse(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBreak(BlockBreakEvent event) {
        refuse(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrop(PlayerDropItemEvent event) {
        refuse(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        end(event.getPlayer(), TunerEnd.QUIT);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        end(event.getEntity(), TunerEnd.DEATH);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) {
        end(event.getPlayer(), TunerEnd.MOVED_AWAY);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (far(event.getFrom(), event.getTo())) {
            end(event.getPlayer(), TunerEnd.MOVED_AWAY);
        }
    }

    static boolean far(Location from, Location to) {
        if (to == null || from.getWorld() != to.getWorld()) return true;
        return from.distanceSquared(to) > FAR * FAR;
    }

    private static Optional<TunerSession> session(Player player) {
        return Tuner.active(player.getUniqueId());
    }

    private static void refuse(Player player, Cancellable event) {
        if (Tuner.active(player.getUniqueId()).isPresent()) event.setCancelled(true);
    }

    private static void end(Player player, TunerEnd reason) {
        session(player).ifPresent(session -> session.end(reason));
    }
}
