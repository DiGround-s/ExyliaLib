package net.exylia.lib.packet;

import net.exylia.lib.packet.internal.PacketRuntime;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Entry point of the packet module.
 *
 * <p>Client-side tricks that a staff plugin needs and the server has no API
 * for: hiding a player from some viewers but not others, showing blocks that
 * are not there, outlining blocks through the world, pinning a player in
 * place, drawing one client as a spectator, and watching a chest without
 * opening it.
 *
 * <pre>{@code
 * PluginPackets packets = Packets.of(this);
 *
 * // vanished staff are invisible to everyone without the permission
 * packets.visibility().rule((viewer, target) ->
 *         !vanished.contains(target.getUniqueId()) || viewer.hasPermission("staff.see"));
 * packets.visibility().refresh(staff);
 *
 * // and frozen players stay where they are
 * packets.movement().freeze(suspect);
 * }</pre>
 *
 * <h2>Packets, not state</h2>
 * Nothing here changes what the server believes. A hidden player still
 * collides, a fake block is still air to the server, a client drawn as a
 * spectator still walks into walls and takes damage. Every helper documents what it does not do; pair it with the
 * server-side call that covers the rest.
 *
 * <h2>What is needed</h2>
 * PacketEvents. Without it {@link #isAvailable()} is {@code false} and every
 * helper is a silent no-op — one warning per plugin, then nothing. The only
 * exception is {@link SilentContainer}, which is plain Bukkit and works
 * anywhere.
 *
 * <h2>Threading</h2>
 * Every method is safe from any thread; anything that touches an entity hops
 * to that entity's thread first, so the module behaves the same on Folia.
 *
 * @since 1.75.0
 */
public final class Packets {

    private Packets() {
        throw new AssertionError("No instances.");
    }

    /**
     * Returns whether PacketEvents is installed and ready.
     *
     * @return {@code true} when packets can be sent and intercepted
     */
    public static boolean isAvailable() {
        return PacketRuntime.isAvailable();
    }

    /**
     * Returns the packet helpers of a plugin, created on first use.
     *
     * <p>What a plugin froze, hid or faked is undone when it is disabled.
     *
     * @param plugin the owning plugin
     * @return its helpers
     */
    public static @NotNull PluginPackets of(@NotNull Plugin plugin) {
        return PacketRuntime.of(plugin);
    }

    /** Undoes everything one plugin did. Called when the plugin disables. */
    public static void release(@NotNull String pluginName) {
        PacketRuntime.release(pluginName);
    }

    /** Undoes everything and drops the listeners. Called when the library disables. */
    public static void releaseAll() {
        PacketRuntime.shutdown();
    }
}
