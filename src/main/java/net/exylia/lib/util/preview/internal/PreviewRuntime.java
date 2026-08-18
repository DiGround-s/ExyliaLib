package net.exylia.lib.util.preview.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.util.preview.Preview;
import net.exylia.lib.util.preview.PreviewSettings;
import net.exylia.lib.util.sequence.Sequence;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every preview on the server, and everything that can interrupt one.
 *
 * <h2>One per player</h2>
 * A second preview for the same player ends the first rather than stacking.
 * Two overlapping previews would each remember an origin, and the second to
 * finish would put the player back where the first one found them &mdash; which
 * by then is a patch of empty sky.
 *
 * <h2>Everything that can interrupt</h2>
 * Quit, kick, death, world change, a teleport by another plugin, the owning
 * plugin disabling, and the server stopping. Each ends the preview in the way
 * that suits it: a player who left is not teleported, and a player who was
 * moved on purpose is left where they were moved to.
 */
@ApiStatus.Internal
public final class PreviewRuntime implements Listener {

    private static final Map<UUID, PreviewSession> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<UUID, String> OWNERS = new ConcurrentHashMap<>();
    private static volatile boolean listening;

    private PreviewRuntime() {
    }

    /** Registers the listeners, once, against the library itself. */
    public static synchronized void init(@NotNull Plugin library) {
        if (listening) {
            return;
        }
        org.bukkit.Bukkit.getPluginManager().registerEvents(new PreviewRuntime(), library);
        listening = true;
    }

    /**
     * Starts a preview, ending whatever that player already had.
     *
     * @return the preview
     */
    public static @NotNull Preview start(@NotNull Plugin plugin, @NotNull Player viewer,
                                         @NotNull Sequence sequence, @NotNull TaskScheduler tasks,
                                         @NotNull Debug debug, @NotNull PreviewSettings settings,
                                         @Nullable Runnable onComplete) {
        UUID id = viewer.getUniqueId();
        PreviewSession existing = ACTIVE.get(id);
        if (existing != null) {
            // Ends where they are: this player is about to be lifted again, and
            // returning them to the old origin first would be a visible bounce.
            existing.endWhereTheyAre();
        }

        PreviewSession session = new PreviewSession(plugin, viewer, tasks, debug, settings,
                onComplete, () -> {
            ACTIVE.remove(id);
            OWNERS.remove(id);
        });
        ACTIVE.put(id, session);
        OWNERS.put(id, plugin.getName());
        session.start(sequence);
        return session;
    }

    /** Whether this player is being shown a preview. */
    public static boolean isPreviewing(@NotNull UUID viewer) {
        return ACTIVE.containsKey(viewer);
    }

    /** How many previews are running. */
    public static int active() {
        return ACTIVE.size();
    }

    /**
     * Ends every preview one plugin started.
     *
     * <p>Called when that plugin is disabled. The players are put back before
     * the plugin's scheduler goes away, because the restore needs it.
     */
    public static int endAllOf(@NotNull String pluginName) {
        int ended = 0;
        for (Map.Entry<UUID, String> entry : Map.copyOf(OWNERS).entrySet()) {
            if (!entry.getValue().equals(pluginName)) {
                continue;
            }
            PreviewSession session = ACTIVE.get(entry.getKey());
            if (session != null) {
                session.end();
                ended++;
            }
        }
        return ended;
    }

    /**
     * Test seam: forgets the registered listeners so a fresh server can be
     * stood up. Package-private in spirit; public only because the tests live
     * in the package next door.
     */
    @ApiStatus.Internal
    public static synchronized void resetForTests() {
        ACTIVE.clear();
        OWNERS.clear();
        Stages.releaseAll();
        listening = false;
    }

    /** Ends every preview on the server, on shutdown. */
    public static void endEverything() {
        for (PreviewSession session : List.copyOf(ACTIVE.values())) {
            // Moving a player during shutdown is neither safe nor useful: the
            // server saves them where they are, and the join handler below puts
            // right anyone who was mid-preview.
            session.end();
        }
        ACTIVE.clear();
        OWNERS.clear();
        Stages.releaseAll();
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PreviewSession session = ACTIVE.get(event.getPlayer().getUniqueId());
        if (session != null) {
            // They are already gone: teleporting them throws, and the server has
            // saved whatever position it has. What matters is that the slot goes
            // back and the hiding is undone for everyone still online.
            session.endWithoutMoving();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        PreviewSession session = ACTIVE.get(event.getPlayer().getUniqueId());
        if (session != null) {
            session.endWithoutMoving();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        PreviewSession session = ACTIVE.get(event.getEntity().getUniqueId());
        if (session != null) {
            // Respawning decides where they go. Returning them to the origin
            // would fight it, and the origin is where they died anyway.
            session.endWhereTheyAre();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        PreviewSession session = ACTIVE.get(event.getPlayer().getUniqueId());
        if (session != null) {
            // The stage was in the world they left. Their new world is
            // somewhere they were sent on purpose.
            session.endWhereTheyAre();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) {
            return;
        }
        PreviewSession session = ACTIVE.get(event.getPlayer().getUniqueId());
        if (session == null) {
            return;
        }
        // The preview's own lift arrives here too, so only a teleport that
        // takes them off the stage counts as somebody else moving them.
        if (isOurOwnStage(session, event)) {
            return;
        }
        session.endWhereTheyAre();
    }

    private boolean isOurOwnStage(PreviewSession session, PlayerTeleportEvent event) {
        var to = event.getTo();
        var origin = session.origin();
        if (to == null || origin == null) {
            return true;
        }
        // The lift, or the return. Anything else is another plugin's doing.
        return to.getBlockY() >= 320 || to.distanceSquared(origin) < 1.0;
    }

    /**
     * Puts right a player who logs in still altered.
     *
     * <p>Only reachable if the server died mid-preview, which is exactly when
     * nothing else got a chance to tidy up. Flight is the part that matters:
     * a player left able to fly in a survival lobby is a bug report.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!ACTIVE.containsKey(player.getUniqueId())) {
            return;
        }
        PreviewSession stale = ACTIVE.remove(player.getUniqueId());
        OWNERS.remove(player.getUniqueId());
        if (stale != null) {
            stale.endWithoutMoving();
        }
    }
}
