package net.exylia.lib.hologram.internal;

import net.exylia.lib.hologram.Hologram;
import net.exylia.lib.hologram.HologramConfig;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Owns every hologram on the server and decides who sees what.
 *
 * <p>Two things happen on a timer, and they are deliberately not the same
 * thing. <b>Visibility</b> is checked often and costs a squared distance per
 * player per hologram, sending packets only when somebody crosses the edge.
 * <b>Refreshing</b> happens per hologram at its own interval, and only for
 * holograms whose lines actually contain placeholders.
 *
 * <p>Both run on one async timer owned by ExyliaLib. Holograms are packets, so
 * none of this needs the main thread; what it must never do is outlive the
 * plugin that created them.
 */
public final class HologramRuntime {

    /** How often visibility is re-checked. Four times a second is past what a walking player notices. */
    private static final long VISIBILITY_PERIOD_TICKS = 5L;

    private static final Object LOCK = new Object();

    /** Keyed by owning plugin, then by hologram id, so ids never clash between plugins. */
    private static final Map<String, Map<String, HologramImpl>> BY_OWNER = new ConcurrentHashMap<>();

    private static TaskScheduler scheduler;
    private static Logger logger = Logger.getLogger("ExyliaLib");
    private static TaskHandle driver;
    private static LongSupplier clock = System::currentTimeMillis;
    private static EntityIds ids;
    private static DisplaySink sink;
    private static boolean available;

    /** Where entity ids come from, so tests do not need a server. */
    @FunctionalInterface
    public interface EntityIds {
        int next();
    }

    private HologramRuntime() {
    }

    /**
     * Wires the module to the runtime. Called by ExyliaLib at startup.
     *
     * @param plugin the plugin whose scheduler drives holograms
     */
    public static void init(Plugin plugin) {
        synchronized (LOCK) {
            scheduler = Tasks.of(plugin);
            logger = plugin.getLogger();
            // The default sink loads PacketEvents — do it only when
            // HologramRuntime is wired for real, not in tests.
            ids = DisplayPackets::newEntityId;
            sink = DisplayPackets.INSTANCE;
            available = DisplayPackets.ready();
        }
    }

    /** Swaps the clock and the id source. For tests. */
    static void testHooks(TaskScheduler testScheduler, Logger testLogger,
                          LongSupplier testClock, EntityIds testIds, DisplaySink testSink) {
        synchronized (LOCK) {
            scheduler = testScheduler;
            logger = testLogger;
            clock = testClock == null ? System::currentTimeMillis : testClock;
            ids = testIds == null ? DisplayPackets::newEntityId : testIds;
            sink = testSink == null ? DisplayPackets.INSTANCE : testSink;
            available = true;
        }
    }

    /** Where packets go. Swapped by tests. */
    static DisplaySink sink() {
        DisplaySink current = sink;
        if (current == null) {
            throw new IllegalStateException("HologramRuntime has not been initialised");
        }
        return current;
    }

    static long now() {
        return clock.getAsLong();
    }

    static int newEntityId() {
        return ids.next();
    }

    static Logger logger() {
        return logger;
    }

    /**
     * Returns whether holograms can be shown at all.
     *
     * <p>They are packets and nothing else, so without PacketEvents there is
     * no fallback worth pretending about: {@link #show} reports it once and
     * returns a hologram that does nothing.
     */
    public static boolean isSupported() {
        return available;
    }

    /**
     * Creates and shows a hologram.
     *
     * @param plugin   the plugin it belongs to
     * @param id       a name unique within that plugin; reusing one replaces it
     * @param location where it stands, before the configured offset
     * @param config   what it looks like
     * @param data     extra values placeholders can read, may be {@code null}
     * @return the hologram, or a no-op one when disabled or unsupported
     */
    public static Hologram show(Plugin plugin, String id, Location location,
                                HologramConfig config, Map<String, Object> data) {
        if (plugin == null || id == null || location == null || config == null) {
            throw new IllegalArgumentException("plugin, id, location and config must not be null");
        }
        if (!config.enabled()) {
            return new NoopHologram(id, location);
        }
        if (!available) {
            logger.warning(plugin.getName() + " asked for a hologram, but PacketEvents is not"
                    + " installed; holograms are packet-only and will not be shown.");
            return new NoopHologram(id, location);
        }

        HologramImpl hologram = new HologramImpl(id, plugin.getName(), config, location);
        if (data != null && !data.isEmpty()) {
            hologram.updateData(data);
        }

        synchronized (LOCK) {
            Map<String, HologramImpl> owned =
                    BY_OWNER.computeIfAbsent(plugin.getName(), key -> new ConcurrentHashMap<>());
            HologramImpl previous = owned.put(id, hologram);
            if (previous != null) {
                despawnEverywhere(previous);
                previous.markRemoved();
            }
            ensureDriver();
        }
        return hologram;
    }

    /**
     * Returns a hologram a plugin created.
     *
     * @param plugin the plugin
     * @param id     the name it was created under
     * @return the hologram, empty when there is none
     */
    public static Optional<Hologram> get(Plugin plugin, String id) {
        Map<String, HologramImpl> owned = BY_OWNER.get(plugin.getName());
        return Optional.ofNullable(owned == null ? null : owned.get(id));
    }

    /** Returns every hologram a plugin created. */
    public static List<Hologram> all(Plugin plugin) {
        Map<String, HologramImpl> owned = BY_OWNER.get(plugin.getName());
        return owned == null ? List.of() : List.copyOf(owned.values());
    }

    /** Removes one hologram. */
    static void remove(HologramImpl hologram) {
        synchronized (LOCK) {
            if (hologram.removed()) {
                return;
            }
            hologram.markRemoved();
            despawnEverywhere(hologram);
            for (Map<String, HologramImpl> owned : BY_OWNER.values()) {
                owned.values().remove(hologram);
            }
            stopDriverIfIdle();
        }
    }

    /**
     * Removes every hologram a plugin created.
     *
     * @param pluginName the plugin's name
     * @return how many were removed
     */
    public static int removeAll(String pluginName) {
        synchronized (LOCK) {
            Map<String, HologramImpl> owned = BY_OWNER.remove(pluginName);
            if (owned == null) {
                return 0;
            }
            for (HologramImpl hologram : owned.values()) {
                hologram.markRemoved();
                despawnEverywhere(hologram);
            }
            stopDriverIfIdle();
            return owned.size();
        }
    }

    /** Takes every hologram off a leaving player's screen. */
    public static void forget(Player player) {
        UUID id = player.getUniqueId();
        for (Map<String, HologramImpl> owned : BY_OWNER.values()) {
            for (HologramImpl hologram : owned.values()) {
                // No packets: the client is gone. This only stops the runtime
                // from believing a player who left still has it on screen.
                hologram.viewerIds().remove(id);
            }
        }
    }

    /** Removes everything. Used on shutdown and by tests. */
    public static void removeEverything() {
        synchronized (LOCK) {
            for (Map<String, HologramImpl> owned : BY_OWNER.values()) {
                for (HologramImpl hologram : owned.values()) {
                    hologram.markRemoved();
                    despawnEverywhere(hologram);
                }
            }
            BY_OWNER.clear();
            stopDriverLocked();
        }
    }

    /**
     * Re-sends every hologram.
     *
     * <p>Used when the palette is reloaded: the text is unchanged but what it
     * parses into is not, so the diff would otherwise decide there is nothing
     * to do.
     */
    public static void invalidateAll() {
        forEach(hologram -> {
            hologram.invalidate();
            for (Player viewer : viewersOf(hologram)) {
                hologram.despawnFor(viewer);
            }
        });
    }

    /** Returns how many holograms exist. */
    public static int count() {
        int total = 0;
        for (Map<String, HologramImpl> owned : BY_OWNER.values()) {
            total += owned.size();
        }
        return total;
    }

    // ------------------------------------------------------------------
    // Changes that need viewers told
    // ------------------------------------------------------------------

    static void moved(HologramImpl hologram) {
        hologram.teleportFor(viewersOf(hologram));
    }

    static void remounted(HologramImpl hologram) {
        // Riding is set up at spawn time, so viewers are given it again.
        respawn(hologram);
    }

    static void visibilityChanged(HologramImpl hologram) {
        for (Player viewer : viewersOf(hologram)) {
            if (!hologram.canSee(viewer)) {
                hologram.despawnFor(viewer);
            }
        }
    }

    static void rebuild(HologramImpl hologram, int lineCount) {
        for (Player viewer : viewersOf(hologram)) {
            hologram.despawnFor(viewer);
        }
        hologram.rebuildDisplays(lineCount);
    }

    private static void respawn(HologramImpl hologram) {
        for (Player viewer : viewersOf(hologram)) {
            hologram.despawnFor(viewer);
        }
    }

    private static void despawnEverywhere(HologramImpl hologram) {
        for (Player viewer : viewersOf(hologram)) {
            hologram.despawnFor(viewer);
        }
    }

    /** The online players who currently have this hologram on screen. */
    private static List<Player> viewersOf(HologramImpl hologram) {
        List<Player> viewers = new ArrayList<>(hologram.viewerIds().size());
        for (UUID id : hologram.viewerIds()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                viewers.add(player);
            }
        }
        return viewers;
    }

    private static void forEach(java.util.function.Consumer<HologramImpl> action) {
        for (Map<String, HologramImpl> owned : BY_OWNER.values()) {
            for (HologramImpl hologram : owned.values()) {
                action.accept(hologram);
            }
        }
    }

    // ------------------------------------------------------------------
    // The driver
    // ------------------------------------------------------------------

    private static void ensureDriver() {
        if (driver == null && scheduler != null) {
            driver = scheduler.runAsyncTimer(VISIBILITY_PERIOD_TICKS, VISIBILITY_PERIOD_TICKS,
                    HologramRuntime::tick);
        }
    }

    private static void stopDriverIfIdle() {
        if (count() == 0) {
            stopDriverLocked();
        }
    }

    private static void stopDriverLocked() {
        if (driver != null) {
            driver.cancel();
            driver = null;
        }
    }

    /**
     * One pass over every hologram: who can see it, and does it need redrawing.
     *
     * <p>Visibility is checked first, so a player who just arrived is sent the
     * hologram already carrying this cycle's values instead of the previous
     * ones.
     */
    static void tick() {
        // Asked four times a second forever, so a server whose plugins registered
        // no hologram should not allocate a list to discover that.
        if (BY_OWNER.isEmpty()) {
            return;
        }
        List<HologramImpl> holograms = new ArrayList<>();
        forEach(holograms::add);
        if (holograms.isEmpty()) {
            return;
        }

        List<? extends Player> online = List.copyOf(Bukkit.getOnlinePlayers());
        long now = now();

        for (HologramImpl hologram : holograms) {
            try {
                List<Player> viewers = updateVisibility(hologram, online);
                if (hologram.refreshes() && hologram.due(now) && !viewers.isEmpty()) {
                    hologram.render(viewers);
                }
            } catch (Throwable t) {
                logger.warning("Could not update the hologram '" + hologram.id() + "': "
                        + t.getMessage());
            }
        }
    }

    /** Sends and removes the hologram as players cross its view distance. */
    private static List<Player> updateVisibility(HologramImpl hologram,
                                                 List<? extends Player> online) {
        List<Player> viewers = new ArrayList<>();
        for (Player player : online) {
            boolean sees = hologram.isViewing(player);
            boolean should = hologram.canSee(player);
            if (should && !sees) {
                hologram.spawnFor(player);
            } else if (!should && sees) {
                hologram.despawnFor(player);
            }
            if (should) {
                viewers.add(player);
            }
        }
        return viewers;
    }
}
