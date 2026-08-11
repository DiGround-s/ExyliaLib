package net.exylia.lib.platform;

/**
 * Identifies the server software ExyliaLib is currently running on.
 *
 * <p>Detection happens exactly once, when this class is first loaded, and the
 * result is cached in a {@code static final} field. That lets the JIT compiler
 * treat {@link #isFolia()} as a constant and eliminate the dead branch entirely,
 * so platform checks on hot paths cost nothing at runtime.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * if (Platform.isFolia()) {
 *     // region-threaded specific behaviour
 * }
 * }</pre>
 *
 * <p>Most code should <strong>not</strong> need this class: the
 * {@link net.exylia.lib.task task API} already abstracts every scheduling
 * difference between platforms.
 *
 * @since 1.0.0
 */
public enum Platform {

    /**
     * Folia, or any fork of it. The server is region-threaded: there is no
     * single "main thread", and every task must be scheduled against the thread
     * that owns a specific entity or region.
     */
    FOLIA,

    /**
     * Paper, or any fork of it that is not region-threaded (Purpur, Pufferfish,
     * ...). Single main thread, plus Paper's extended API surface.
     */
    PAPER,

    /**
     * Spigot, CraftBukkit, or any other plain Bukkit implementation. Single main
     * thread, Bukkit API only.
     */
    BUKKIT;

    private static final Platform CURRENT = detect();

    /**
     * Returns the platform this server is running on.
     *
     * @return the detected platform, never {@code null}
     */
    public static Platform current() {
        return CURRENT;
    }

    /**
     * Returns whether this server is region-threaded (Folia).
     *
     * <p>When this is {@code true} there is no main thread, and code must never
     * assume it can touch an entity or a block from an arbitrary thread.
     *
     * @return {@code true} if running on Folia or a Folia fork
     */
    public static boolean isFolia() {
        return CURRENT == FOLIA;
    }

    /**
     * Returns whether Paper's extended API is available.
     *
     * <p>This is also {@code true} on Folia, since Folia is a Paper fork.
     *
     * @return {@code true} if running on Paper, a Paper fork, or Folia
     */
    public static boolean isPaper() {
        return CURRENT == PAPER || CURRENT == FOLIA;
    }

    private static Platform detect() {
        if (hasClass("io.papermc.paper.threadedregions.RegionizedServer")) {
            return FOLIA;
        }
        if (hasClass("io.papermc.paper.configuration.Configuration")
                || hasClass("com.destroystokyo.paper.PaperConfig")) {
            return PAPER;
        }
        return BUKKIT;
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
