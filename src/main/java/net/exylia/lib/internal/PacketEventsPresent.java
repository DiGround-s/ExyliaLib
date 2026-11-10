package net.exylia.lib.internal;

import org.bukkit.Bukkit;

/**
 * Whether the PacketEvents plugin is installed, asked without touching any of
 * its classes.
 *
 * <p>Every {@code *Packets} class names PacketEvents types in its fields or
 * signatures, so even calling its {@code ready()} on a server without the
 * plugin is a {@link NoClassDefFoundError} that takes the whole library down.
 * Ask this first.
 */
public final class PacketEventsPresent {

    private PacketEventsPresent() {
    }

    /** Whether the plugin is there; its classes can be loaded only when this is true. */
    public static boolean installed() {
        return Bukkit.getPluginManager().getPlugin("packetevents") != null;
    }
}
