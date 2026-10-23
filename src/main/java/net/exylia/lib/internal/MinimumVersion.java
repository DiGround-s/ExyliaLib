package net.exylia.lib.internal;

import org.bukkit.Bukkit;

import java.util.logging.Logger;

/**
 * Warns at startup when the server runs a Minecraft version older than the
 * library's baseline.
 *
 * <p>The library compiles against and is verified on 1.21.4. An older server
 * still loads it, then fails mid-game with {@code NoSuchMethodError} the first
 * time a newer API is touched. Loading is not stopped: an owner who chose to
 * run it keeps the behaviour they had, but the console says why things break.
 */
public final class MinimumVersion {

    /** The oldest Minecraft version the library supports. */
    public static final String MINIMUM = "1.21.4";

    private MinimumVersion() {
    }

    /** Logs a prominent error when the running server is older than {@link #MINIMUM}. */
    public static void check(Logger logger) {
        String running = running();
        if (!olderThan(running, MINIMUM)) return;
        logger.severe("************************************************************");
        logger.severe(" UNSUPPORTED MINECRAFT VERSION: " + running);
        logger.severe(" ExyliaLib and every Exylia plugin require " + MINIMUM + " or newer.");
        logger.severe(" On this version Exylia plugins WILL malfunction (errors such as");
        logger.severe(" NoSuchMethodError mid-game). Update the server to " + MINIMUM + "+.");
        logger.severe("************************************************************");
    }

    private static String running() {
        try {
            return Bukkit.getMinecraftVersion();
        } catch (NoSuchMethodError spigot) {
            return Bukkit.getBukkitVersion().split("-")[0];
        }
    }

    /**
     * Whether {@code version} is older than {@code minimum}, comparing each
     * dotted number in turn (so {@code 26.1} is newer than {@code 1.21.11}).
     * A suffix such as {@code -rc1} is ignored; an unreadable version is never
     * reported as older.
     */
    static boolean olderThan(String version, String minimum) {
        int[] a = parse(version);
        int[] b = parse(minimum);
        if (a == null || b == null) return false;
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x < y;
        }
        return false;
    }

    private static int[] parse(String version) {
        if (version == null) return null;
        String[] parts = version.trim().split("[^0-9.]", 2)[0].split("\\.");
        int[] numbers = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) numbers[i] = Integer.parseInt(parts[i]);
        } catch (NumberFormatException unreadable) {
            return null;
        }
        return numbers;
    }
}
