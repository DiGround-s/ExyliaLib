package net.exylia.lib.nametag;

import net.exylia.lib.nametag.internal.NametagRuntime;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Entry point of the nametag module.
 *
 * <p>Changes how one player looks <em>to another player</em>: the colour of the
 * name above their head, whether they glow, whether they can be pushed, and
 * whether they are still drawn while invisible.
 *
 * <pre>{@code
 * PluginNametags tags = Nametags.of(this);
 *
 * // clanmates are green to each other, and visible through walls
 * tags.paint(viewer, clanmate, NametagStyle.of(NamedTextColor.GREEN).glowing());
 *
 * // and back to normal when the clan breaks up
 * tags.reset(viewer, clanmate);
 * }</pre>
 *
 * <h2>Everyone sees this, not only modified clients</h2>
 * This is the difference from {@link net.exylia.lib.client.Clients}. That
 * module talks to Lunar and Feather and does nothing for anyone else; this one
 * is vanilla scoreboard teams and entity flags sent as packets, so a player on
 * an unmodified client sees exactly the same thing.
 *
 * <h2>Per viewer, not per player</h2>
 * Two players can be told different things about the same third player at the
 * same time, because none of it exists on the server: the same person is red to
 * their enemy and green to their clan, with no scoreboard, no team, and no
 * server-side state to keep in step.
 *
 * <h2>What is needed</h2>
 * PacketEvents. Without it {@link #isSupported()} is {@code false} and every
 * call does nothing rather than failing — a server without it keeps working,
 * with everybody in white.
 *
 * <h2>Threading</h2>
 * Every method is safe from any thread.
 *
 * @since 1.36.0
 */
public final class Nametags {

    private Nametags() {
        throw new AssertionError("No instances.");
    }

    /**
     * Returns the nametags of a plugin.
     *
     * <p>What a plugin paints is undone when it is disabled, so a game that
     * ends badly cannot leave a player permanently red.
     *
     * @param plugin the owning plugin
     * @return its nametags
     */
    public static @NotNull PluginNametags of(@NotNull Plugin plugin) {
        return NametagRuntime.of(plugin);
    }

    /**
     * Returns whether nametags can be sent at all.
     *
     * <p>{@code false} when PacketEvents is not installed. Every call still
     * works and sends nothing.
     *
     * @return {@code true} when PacketEvents is loaded
     */
    public static boolean isSupported() {
        return NametagRuntime.isSupported();
    }
}
