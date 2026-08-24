package net.exylia.lib.panel.internal;

import net.exylia.lib.panel.PanelSession;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The panels of one plugin, and the ones its players have open.
 *
 * <p>One runtime per consumer, so releasing a plugin releases its panels and
 * nothing else.
 *
 * <h2>Why there is no map from player to session</h2>
 * A session is found through the window it is bound to, exactly as
 * {@code MenuRuntime} finds a menu through its inventory holder. A map keyed by
 * player answers the wrong question the moment somebody opens a chest on top of
 * a panel: the map still says "this player has a panel", and a click in the
 * chest would be handed to it. The window knows what it is; the player does not.
 *
 * <p>The only per-plugin collection here is the list of live sessions, which
 * exists so a disable can close them — it is keyed by nothing, and a session
 * leaves it the moment it is released.
 */
@ApiStatus.Internal
public final class PanelRuntime {

    /** One runtime per consumer, shared by every {@code Panels.of} call. */
    private static final Map<String, PanelRuntime> RUNTIMES = new ConcurrentHashMap<>();

    /**
     * Where the module reads the time.
     *
     * <p>A seam rather than a call to {@link System}, so a test that cares about
     * ordering moves time instead of sleeping. Precedent: {@code Cooldowns}.
     */
    private static volatile LongSupplier clock = System::currentTimeMillis;

    private final Plugin plugin;

    /**
     * The panels of this plugin that are open right now.
     *
     * <p>Not keyed by anything: it is a set of live sessions so that disable can
     * close them, and each one removes itself when it is released. Deliberately
     * <em>not</em> a {@code Map<UUID, Session>} — see the class documentation.
     */
    private final List<Session> live = new java.util.concurrent.CopyOnWriteArrayList<>();

    private PanelRuntime(Plugin plugin) {
        this.plugin = plugin;
    }

    /** The runtime belonging to a plugin, created on first ask. */
    public static @NotNull PanelRuntime of(@NotNull Plugin plugin) {
        return RUNTIMES.computeIfAbsent(plugin.getName(), ignored -> new PanelRuntime(plugin));
    }

    public @NotNull Plugin plugin() {
        return plugin;
    }

    /** Now, as the module sees it. */
    static long now() {
        return clock.getAsLong();
    }

    // ------------------------------------------------------------ sessions

    /** Starts tracking a session, so a disable can close it. */
    void track(Session session) {
        live.add(session);
    }

    /** Stops tracking one, when it is released. */
    void untrack(Session session) {
        live.remove(session);
    }

    /** How many panels this plugin has on screen. */
    public int open() {
        return live.size();
    }

    /**
     * The panel a player has open, if it is one of ours.
     *
     * <p>Read off the window rather than searched for, so it costs the same
     * whether one plugin has panels or twenty, and so a chest opened over a
     * panel resolves to nothing.
     */
    public static @Nullable Session sessionOf(@NotNull Player viewer) {
        var view = viewer.getOpenInventory();
        if (view == null) {
            return null;
        }
        Inventory top = view.getTopInventory();
        return top != null && top.getHolder() instanceof PanelHolder holder ? holder.session() : null;
    }

    /** The same, as the public type. */
    public static @Nullable PanelSession publicSessionOf(@NotNull Player viewer) {
        return sessionOf(viewer);
    }

    // ----------------------------------------------------------- lifecycle

    /**
     * Releases a player's panel, on quit.
     *
     * <p>Every runtime is asked because a player's panel belongs to whichever
     * plugin opened it, and quitting does not say which.
     *
     * @param player who left
     */
    public static void forget(@NotNull UUID player) {
        for (PanelRuntime runtime : RUNTIMES.values()) {
            for (Session session : List.copyOf(runtime.live)) {
                if (session.viewer().getUniqueId().equals(player)) {
                    session.release();
                }
            }
        }
    }

    /**
     * Releases a plugin's panels.
     *
     * <p>Windows already on screen are closed: their buttons write into a
     * working copy owned by a classloader that is going away, and a panel that
     * answers a click with a {@code NoClassDefFoundError} is worse than one that
     * shut. Released <em>before</em> the task module drops the plugin's tasks,
     * because releasing a session is what cancels the delayed steps it started.
     */
    public static void release(@NotNull String pluginName) {
        PanelRuntime runtime = RUNTIMES.remove(pluginName);
        if (runtime != null) {
            runtime.closeEverything();
        }
    }

    /** Releases every plugin's panels. */
    public static void releaseAll() {
        for (String name : List.copyOf(RUNTIMES.keySet())) {
            release(name);
        }
    }

    /** How many plugins have panels, for diagnostics. */
    public static int registered() {
        return RUNTIMES.size();
    }

    private void closeEverything() {
        for (Session session : new ArrayList<>(live)) {
            session.release();
        }
        live.clear();
    }

    // --------------------------------------------------------- test seams

    /** For tests: replaces the clock so time can be moved without sleeping. */
    static void setClock(@NotNull LongSupplier replacement) {
        clock = replacement;
    }

    /** For tests: restores the real clock. */
    static void resetClock() {
        clock = System::currentTimeMillis;
    }
}
