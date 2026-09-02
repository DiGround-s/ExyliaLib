package net.exylia.lib.display;

import net.exylia.lib.display.internal.DisplayRuntime;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Display entities that move by themselves, sent as packets.
 *
 * <pre>{@code
 * PluginDisplays displays = Displays.of(this);
 *
 * DisplayModel blade = DisplayModel.item(new ItemStack(Material.NETHERITE_SWORD))
 *         .glow(0xFF6B9D)
 *         .light(15);
 *
 * DisplayMotion thrown = DisplayMotion.builder()
 *         .life(1200)
 *         .from(0, 7, 0).to(0, 0, 0)
 *         .spin(Rotation.Axis.Z, 3)
 *         .build();
 *
 * displays.show(blade, thrown, where, observers);
 * }</pre>
 *
 * <h2>What this is for</h2>
 * The effects particles cannot do: a solid object with a shape, a shadow and an
 * outline. A sword that falls out of the sky and lands point-first, a shockwave
 * of real blocks, a body that comes apart into the block it was standing on.
 *
 * <p>It is not a replacement for particles and should not be used as one.
 * Displays give an effect weight and silhouette; particles give it light,
 * smoke and atmosphere. Effects that look expensive are both.
 *
 * <h2>The client does the animating</h2>
 * A display is told a pose and how long it has to get there, and draws every
 * frame in between at the viewer's own frame rate. A two-second animation is
 * about six packets a viewer, and it is smooth on a server running at fifteen
 * ticks a second, because the smoothness never depended on the tick rate.
 *
 * <h2>Nothing the server has to carry</h2>
 * These are not entities. They are not ticked, not saved, not in any chunk, and
 * two players standing together can be shown different ones. What that costs is
 * that nothing else will clean them up, so the module owns their lives itself:
 * a display goes when its motion ends, when its plugin is disabled, or when the
 * server stops, and there is no fourth case.
 *
 * <h2>Written in configuration</h2>
 * Most callers never touch this API. The sequence module draws shapes out of
 * displays from the same file that draws them out of particles &mdash; see
 * {@code docs/displays.md}.
 *
 * @since 1.85.0
 */
public final class Displays {

    private static final Map<String, PluginDisplays> BY_PLUGIN = new ConcurrentHashMap<>();

    private Displays() {
    }

    /**
     * This plugin's view of the module.
     *
     * @param plugin the plugin
     * @return its view, the same instance every time
     */
    public static @NotNull PluginDisplays of(@NotNull Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), PluginDisplays::new);
    }

    /**
     * Removes one plugin's displays and forgets it.
     *
     * <p>Called by the library when a plugin is disabled. Anything it was
     * showing is taken off the clients showing it: a display left behind stays
     * on screen until that player relogs.
     *
     * @param pluginName the plugin's name
     */
    public static void release(@NotNull String pluginName) {
        BY_PLUGIN.remove(pluginName);
        DisplayRuntime.release(pluginName);
    }

    /** Removes every plugin's displays, on shutdown. */
    public static void releaseAll() {
        BY_PLUGIN.clear();
        DisplayRuntime.releaseAll();
    }

    /** How many displays are on screen across every plugin, for diagnostics. */
    public static int active() {
        return DisplayRuntime.active();
    }

    /** Whether this server can show displays at all. */
    public static boolean isSupported() {
        return DisplayRuntime.isSupported();
    }
}
