package net.exylia.lib.overlay;

import net.exylia.lib.overlay.internal.OverlayRuntime;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Entry point of the overlay module: items in a player's own inventory that
 * the server does not have.
 *
 * <pre>{@code
 * PluginOverlays overlays = Overlays.of(this);
 * overlays.load("staff", getConfig().getConfigurationSection("staff-hotbar"));
 *
 * // entering staff mode
 * overlays.show(player, "staff");
 *
 * // leaving it
 * overlays.hide(player);
 * }</pre>
 *
 * <h2>Why packets</h2>
 * The obvious way to give somebody a staff hotbar is to save their inventory,
 * write the tools into it, and put the old one back afterwards. Every step of
 * that is a way to lose a player's items:
 *
 * <ul>
 *   <li>A crash, a disconnect or an autosave between the write and the restore
 *       saves the tools to the world as real items. They have actions bound to
 *       them, so what is left behind is a working staff tool in a player's
 *       chest.</li>
 *   <li>Putting an item in a real inventory fires the {@code inventory_changed}
 *       advancement trigger, so a decorative diamond hands out an
 *       advancement.</li>
 *   <li>An item picked up while the tools are in the way either replaces one or
 *       is lost.</li>
 * </ul>
 *
 * <p>None of these can happen here, because nothing is ever written. The real
 * inventory stays exactly as it was and the client is told a different story;
 * a crash loses the story and keeps the inventory.
 *
 * <h2>What this does not do</h2>
 * An overlay covers the inventory and nothing else. Damage, block breaking,
 * flight, mob targeting and chat are a staff mode's business, not an overlay's
 * — {@link net.exylia.lib.packet.Packets} has the client-side half of several
 * of them.
 *
 * <h2>What is needed</h2>
 * PacketEvents. Without it {@link #isAvailable()} is {@code false}, showing an
 * overlay does nothing, and one warning is logged per plugin.
 *
 * <h2>Threading</h2>
 * Every method is safe from any thread; anything that touches a player hops to
 * that player's thread first, so the module behaves the same on Folia.
 *
 * @since 1.79.0
 */
public final class Overlays {

    private Overlays() {
        throw new AssertionError("No instances.");
    }

    /**
     * Returns whether PacketEvents is installed and overlays can be drawn.
     *
     * @return whether the module does anything
     */
    public static boolean isAvailable() {
        return OverlayRuntime.isAvailable();
    }

    /**
     * Returns the overlays of a plugin, created on first use.
     *
     * @param plugin the owning plugin
     * @return its overlays
     */
    public static @NotNull PluginOverlays of(@NotNull Plugin plugin) {
        return OverlayRuntime.of(plugin);
    }

    /** Takes off everything one plugin put on. Called when the plugin disables. */
    public static void release(@NotNull String pluginName) {
        OverlayRuntime.release(pluginName);
    }

    /** Takes off everything and drops the listener. Called when the library disables. */
    public static void releaseAll() {
        OverlayRuntime.shutdown();
    }

    /**
     * Takes a player's overlay off, whoever put it there.
     *
     * <p>For the case a plugin has to end somebody else's: a punishment that
     * must land whatever the player was doing.
     *
     * @param viewer the player
     */
    public static void hide(@NotNull org.bukkit.entity.Player viewer) {
        OverlayRuntime.hide(viewer);
    }

    /** How many players are wearing one. */
    public static int worn() {
        return OverlayRuntime.worn();
    }
}
