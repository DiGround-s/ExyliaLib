package net.exylia.lib.replay.internal;

import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Somebody watching a recording in the very place it happened, among the
 * people still playing there.
 *
 * <p>On a stage the viewer is alone on a world nobody else is on. Here they
 * float over a real fight, so three things are made true for as long as it
 * plays:
 * <ul>
 *   <li><b>Nobody sees them.</b> Every other player is told to hide them, and
 *       so is anybody who joins meanwhile. They push nothing, are targeted by
 *       nothing and take no damage.</li>
 *   <li><b>They touch nothing.</b> No block is used, broken or placed, no entity
 *       is clicked or hit, nothing is dropped or picked up.</li>
 *   <li><b>They see only the recording.</b> What is really there now &mdash; the
 *       killer walking off, the mob that came later &mdash; is hidden from them,
 *       so the only bodies on their screen are the recorded ones. Whatever the
 *       recording would draw is hidden ({@link Tracking#worthRecording}), and
 *       holograms, frames and the rest of the scenery stay.</li>
 * </ul>
 * Everything is put back by {@link #end()}.
 */
@ApiStatus.Internal
public final class LiveWatch {

    /**
     * How far around the viewer what is really there is hidden from them, in
     * blocks: as far as the server shows a player to anybody by default.
     */
    private static final double REACH = 48;

    /**
     * Sweeps actually done, one call in this many: asking the world what is
     * near was most of what watching live cost the server thread on a hundred
     * players, and something walking in shows for half a second at most.
     */
    private static final int SWEEP_EVERY = 3;

    private static final Set<UUID> WATCHING = ConcurrentHashMap.newKeySet();

    private final Player viewer;
    private final Plugin library;
    private final boolean collidable;
    /** Who is hidden from the viewer. Touched on the viewer's thread only. */
    private final Set<Entity> hidden = new HashSet<>();
    private volatile boolean ended;
    private int calls;

    private LiveWatch(Player viewer, Plugin library) {
        this.viewer = viewer;
        this.library = library;
        this.collidable = viewer.isCollidable();
    }

    /** Whether somebody is watching where it happened: the black box leaves them out. */
    static boolean watching(UUID player) {
        return !WATCHING.isEmpty() && WATCHING.contains(player);
    }

    /**
     * Makes the viewer a ghost over the real place. On the viewer's thread,
     * once they have arrived.
     */
    public static @NotNull LiveWatch begin(@NotNull Player viewer) {
        LiveWatch watch = new LiveWatch(viewer, ReplayRuntime.plugin());
        WATCHING.add(viewer.getUniqueId());
        viewer.setCollidable(false);
        for (Player other : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            if (other != viewer) watch.onThreadOf(other, () -> other.hideEntity(watch.library, viewer));
        }
        watch.sweep();
        return watch;
    }

    /**
     * Hides whatever came near since the last time. On the viewer's thread,
     * every few ticks: what is in their region is what is near them.
     */
    public void sweep() {
        if (ended || !viewer.isOnline() || calls++ % SWEEP_EVERY != 0) return;
        List<Entity> near = viewer.getNearbyEntities(REACH, REACH, REACH);
        for (Entity entity : near) {
            if (hidden.contains(entity) || WATCHING.contains(entity.getUniqueId())) continue;
            if (!Tracking.worthRecording(entity)) continue;
            hidden.add(entity);
            viewer.hideEntity(library, entity);
        }
        // What died or left is a stale reference: let it go, its showEntity is moot.
        hidden.removeIf(entity -> !entity.isValid() && !(entity instanceof Player));
    }

    /** A sweep right now, such as on arriving at the place. */
    public void sweepNow() {
        calls = 0;
        sweep();
    }

    /**
     * Puts everything back: the viewer seen, solid, and seeing everybody. On
     * the viewer's thread, once they are back where they came from, so nobody
     * sees them appear over the fight first. Safe to call twice.
     */
    public void end() {
        if (ended) return;
        ended = true;
        WATCHING.remove(viewer.getUniqueId());
        if (viewer.isOnline()) {
            for (Entity entity : hidden) viewer.showEntity(library, entity);
            viewer.setCollidable(collidable);
        }
        hidden.clear();
        for (Player other : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            if (other != viewer) onThreadOf(other, () -> other.showEntity(library, viewer));
        }
    }

    private void onThreadOf(Player player, Runnable action) {
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            action.run();
        } else {
            ReplayRuntime.scheduler().runAtEntity(player, action);
        }
    }

    // ------------------------------------------------------------------ guard

    /**
     * Keeps the hands of somebody watching live off the real world. At
     * {@code HIGHEST}: a plugin's own hotbar listener at a normal priority still
     * hears its click; the world just does not act on it.
     */
    static final class Guard implements Listener {

        private static boolean live(Entity entity) {
            return entity instanceof Player && watching(entity.getUniqueId());
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onJoin(PlayerJoinEvent event) {
            if (WATCHING.isEmpty()) return;
            Player joined = event.getPlayer();
            for (UUID id : WATCHING) {
                Player viewer = Bukkit.getPlayer(id);
                if (viewer != null && viewer != joined) joined.hideEntity(ReplayRuntime.plugin(), viewer);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onTarget(EntityTargetEvent event) {
            if (event.getTarget() != null && live(event.getTarget())) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDamage(EntityDamageEvent event) {
            if (live(event.getEntity())
                    || event instanceof EntityDamageByEntityEvent byEntity && live(byEntity.getDamager())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInteract(PlayerInteractEvent event) {
            if (!live(event.getPlayer())) return;
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInteractEntity(PlayerInteractEntityEvent event) {
            if (live(event.getPlayer())) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDrop(PlayerDropItemEvent event) {
            if (live(event.getPlayer())) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onPickup(PlayerAttemptPickupItemEvent event) {
            if (live(event.getPlayer())) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onBreak(BlockBreakEvent event) {
            if (live(event.getPlayer())) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onPlace(BlockPlaceEvent event) {
            if (live(event.getPlayer())) event.setCancelled(true);
        }
    }
}
