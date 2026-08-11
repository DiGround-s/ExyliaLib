package net.exylia.lib.hologram;

import net.exylia.lib.hologram.internal.HologramRuntime;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Entry point of the hologram module.
 *
 * <p>A hologram is declared in config and placed by the plugin:
 *
 * <pre>{@code
 * // the event started
 * Holograms.show(this, "koth-" + arena.id(), arena.centre(), config.get().koth());
 *
 * // the score changed
 * Holograms.get(this, "koth-" + arena.id()).ifPresent(Hologram::refresh);
 *
 * // the event ended
 * Holograms.remove(this, "koth-" + arena.id());
 * }</pre>
 *
 * <h2>Packets, not entities</h2>
 * Holograms here are packets and nothing else. The server does not know they
 * exist: nothing is ticked, nothing is saved to a chunk, nothing survives into
 * a world file, and two players can be shown different text at the same
 * coordinates. That also means PacketEvents is required; without it
 * {@link #isSupported()} is {@code false}, every call keeps working, and
 * nothing is drawn.
 *
 * <h2>Cost</h2>
 * A player is only sent packets when they cross the hologram's view distance,
 * and a line is only re-sent when its text actually changes. A hologram whose
 * lines contain no placeholders never schedules a refresh at all, so a sign
 * that says "Spawn" costs one packet per viewer, once.
 *
 * <p>Holograms are shared by default: one render goes to every viewer. Turn on
 * {@code per-player} only when the lines must differ per viewer, since it costs
 * one render and one set of packets per player.
 *
 * <h2>Threading</h2>
 * Every method here is safe from any thread, and the refresh driver runs off
 * the main thread, so resolvers used in hologram lines must be safe there too:
 * that is what {@link net.exylia.lib.placeholder.Placeholders.Group#async()}
 * declares.
 *
 * @since 1.6.0
 */
public final class Holograms {

    private Holograms() {
        throw new AssertionError("No instances.");
    }

    /**
     * Shows a hologram.
     *
     * <p>Reusing an id replaces the hologram that had it, which is what makes
     * this safe to call again after a reload.
     *
     * @param plugin   the plugin it belongs to; its holograms disappear when it
     *                 is disabled
     * @param id       a name unique within that plugin
     * @param location where it stands, before the configured offset
     * @param config   what it looks like, usually from a config record
     * @return the hologram, never {@code null}
     */
    public static @NotNull Hologram show(@NotNull Plugin plugin, @NotNull String id,
                                         @NotNull Location location,
                                         @NotNull HologramConfig config) {
        return HologramRuntime.show(plugin, id, location, config, null);
    }

    /**
     * Shows a hologram, with extra values its placeholders can read.
     *
     * @param plugin   the plugin it belongs to
     * @param id       a name unique within that plugin
     * @param location where it stands, before the configured offset
     * @param config   what it looks like
     * @param data     values resolvers can read with
     *                 {@link net.exylia.lib.placeholder.Request#get}
     * @return the hologram, never {@code null}
     */
    public static @NotNull Hologram show(@NotNull Plugin plugin, @NotNull String id,
                                         @NotNull Location location,
                                         @NotNull HologramConfig config,
                                         @NotNull Map<String, Object> data) {
        return HologramRuntime.show(plugin, id, location, config, data);
    }

    /**
     * Returns a hologram by name.
     *
     * @param plugin the plugin that created it
     * @param id     the name it was created under
     * @return the hologram, empty when there is none
     */
    public static @NotNull Optional<Hologram> get(@NotNull Plugin plugin, @NotNull String id) {
        return HologramRuntime.get(plugin, id);
    }

    /**
     * Returns every hologram a plugin created.
     *
     * @param plugin the plugin
     * @return an immutable list
     */
    public static @NotNull List<Hologram> all(@NotNull Plugin plugin) {
        return HologramRuntime.all(plugin);
    }

    /**
     * Removes a hologram by name.
     *
     * @param plugin the plugin that created it
     * @param id     the name it was created under
     * @return {@code true} when there was one to remove
     */
    public static boolean remove(@NotNull Plugin plugin, @NotNull String id) {
        Optional<Hologram> found = HologramRuntime.get(plugin, id);
        found.ifPresent(Hologram::remove);
        return found.isPresent();
    }

    /**
     * Removes every hologram a plugin created.
     *
     * @param plugin the plugin
     * @return how many were removed
     */
    public static int removeAll(@NotNull Plugin plugin) {
        return HologramRuntime.removeAll(plugin.getName());
    }

    /**
     * Returns whether holograms can be shown.
     *
     * <p>They are packet-only, so this is {@code false} without PacketEvents.
     * Calls still work and simply draw nothing.
     *
     * @return {@code true} when holograms reach the client
     */
    public static boolean isSupported() {
        return HologramRuntime.isSupported();
    }

    /**
     * Returns whether a player currently sees a hologram.
     *
     * @param player    the player
     * @param hologram  the hologram
     * @return {@code true} when it is on their screen
     */
    public static boolean isViewing(@NotNull Player player, @NotNull Hologram hologram) {
        return hologram.isViewing(player);
    }
}
