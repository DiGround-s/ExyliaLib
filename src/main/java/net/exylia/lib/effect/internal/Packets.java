package net.exylia.lib.effect.internal;

import org.bukkit.Bukkit;

/**
 * Decides whether effects go out as packets or through the Bukkit API.
 *
 * <p>Packets are preferred because the server then holds no state for the
 * effect: a title, an action bar or a boss bar sent as a packet is something the
 * client draws and the server forgets. A boss bar created through the Bukkit API
 * is an object the server tracks, keeps in a registry, and has to be told to
 * clean up.
 *
 * <p>PacketEvents is optional, so every reference to its classes is confined to
 * {@link PacketSender}, which is only loaded once the plugin is known to be
 * present. Without it, effects still work through the Bukkit API; they just cost
 * the server more.
 */
public final class Packets {

    private static volatile Boolean available;

    private Packets() {
    }

    /**
     * Returns whether effects can be sent as packets.
     *
     * <p>Checked once and remembered. A plugin cannot appear part-way through a
     * server's life, and this sits on the path of every effect.
     */
    public static boolean available() {
        Boolean known = available;
        if (known != null) {
            return known;
        }
        boolean found = false;
        try {
            if (Bukkit.getPluginManager().getPlugin("packetevents") != null
                    || Bukkit.getPluginManager().getPlugin("PacketEvents") != null) {
                // Present as a plugin is not enough: the API must also be
                // initialised, or the first send would fail instead of falling
                // back cleanly.
                found = PacketSender.ready();
            }
        } catch (Throwable ignored) {
            // No server running, which is the case in tests.
            found = false;
        }
        available = found;
        return found;
    }

    /**
     * Forces the packet path off.
     *
     * <p>For tests, and for a server owner who would rather not route effects
     * through PacketEvents.
     *
     * @param enabled whether packets may be used
     */
    public static void override(boolean enabled) {
        available = enabled;
    }

    /** Forgets the cached answer, so it is worked out again. */
    public static void reset() {
        available = null;
    }
}
