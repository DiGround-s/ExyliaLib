package net.exylia.lib.util.world.internal;

import net.exylia.lib.util.world.internal.WorldsReflection.BackendUnavailableException;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Finds the {@link WorldsBackend} that matches the installed Worlds plugin, once
 * per server run.
 *
 * <p>Detection is by capability, not by version string: each backend is
 * constructed in turn and the first one that binds every member it needs wins. A
 * Worlds release that renamed or dropped a method therefore fails while it is
 * being constructed and is skipped, instead of throwing at the moment a world is
 * being created.
 *
 * <p>The answer is remembered — including "no backend" — so a server without
 * Worlds pays the classloading once rather than on every call.
 */
public final class WorldsBackendDetector {

    /** Newest generation first: only one Worlds jar can be installed at a time. */
    private static final List<Function<Plugin, WorldsBackend>> FACTORIES =
            List.of(Worlds4Backend::new, Worlds3Backend::new);

    private static final Logger LOGGER = Logger.getLogger("ExyliaLib");

    private static volatile boolean resolved;
    private static volatile WorldsBackend backend;

    /** How many times the probe actually ran, so a test can see it memoized. */
    private static volatile int detections;

    private WorldsBackendDetector() {
        throw new AssertionError("No instances.");
    }

    /**
     * Returns the resolved backend, or {@code null} when no compatible Worlds
     * version is present.
     *
     * <p>Safe from any thread and at any point in the lifecycle. The probe is
     * deferred to the first call so that it happens after the Worlds plugin has
     * enabled.
     *
     * @return the backend, or {@code null}
     */
    public static WorldsBackend backend() {
        if (resolved) {
            return backend;
        }
        synchronized (WorldsBackendDetector.class) {
            if (resolved) {
                return backend;
            }
            backend = detect();
            resolved = true;
            return backend;
        }
    }

    private static WorldsBackend detect() {
        detections++;
        Plugin plugin;
        try {
            plugin = WorldsReflection.worldsPlugin();
        } catch (Throwable t) {
            LOGGER.warning("[Worlds] Plugin lookup failed: " + t);
            return null;
        }
        if (plugin == null) {
            return null;
        }
        for (Function<Plugin, WorldsBackend> factory : FACTORIES) {
            try {
                WorldsBackend candidate = factory.apply(plugin);
                LOGGER.info("[Worlds] Bound to " + candidate.name()
                        + " (plugin version " + version(plugin) + ")");
                return candidate;
            } catch (BackendUnavailableException ignored) {
                // The wrong generation, or an incompatible release of the right
                // one: try the next candidate.
            } catch (Throwable t) {
                // Deliberately broad. Probing another plugin's classes can
                // surface whatever a classloader decides to throw, including a
                // NoClassDefFoundError from an optional transitive dependency.
                // Detection is best-effort and must never abort the enable of a
                // plugin that only asked whether Worlds was usable.
                LOGGER.warning("[Worlds] Backend probe failed: " + t);
            }
        }
        LOGGER.warning("[Worlds] Plugin version " + version(plugin)
                + " is installed but exposes no supported API"
                + " (supported: 3.12.x for MC 1.21.x, 4.x for MC 26.x)."
                + " World operations will fall back to vanilla Bukkit.");
        return null;
    }

    /** Reads the version defensively: diagnostics must not fail the detection. */
    @SuppressWarnings("deprecation") // getDescription() is the portable one; see below.
    private static String version(Plugin plugin) {
        try {
            // Paper prefers getPluginMeta(), which Spigot does not have. The
            // deprecated call is the one that works on every platform.
            return plugin.getDescription().getVersion();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /**
     * Throws the remembered answer away, so the next call probes again.
     *
     * <p>For reload flows and tests. The Worlds plugin is not built to be
     * swapped while the server runs.
     */
    public static void reset() {
        synchronized (WorldsBackendDetector.class) {
            backend = null;
            resolved = false;
        }
    }

    /**
     * Returns how many times the probe has run since the JVM started.
     *
     * <p>Observation seam: a memoized probe is invisible from the outside, since
     * a second call and a cached answer look the same to the caller.
     *
     * @return the probe count
     */
    public static int detections() {
        return detections;
    }
}
