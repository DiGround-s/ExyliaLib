package net.exylia.lib.camera;

import net.exylia.lib.camera.internal.CameraRuntime;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Showing a player the world from somewhere they are not.
 *
 * <pre>{@code
 * PluginCameras cameras = Cameras.of(this);
 *
 * CameraShot orbit = CameraShot.parse(
 *         "0 close"
 *       + " | 3.2 yaw=~360 ease=in_out"
 *       + " | 0.6 distance=2.1 pitch=4 ease=out", problem -> getLogger().warning(problem));
 *
 * cameras.play(player, orbit);
 * }</pre>
 *
 * <h2>What this is for</h2>
 * The half of an animation the player who triggered it cannot see. A body that
 * waves, dances or celebrates is drawn for everybody around them and for nobody
 * inside it: the one player it was made for is looking out of its eyes at a wall.
 * A camera turns that round.
 *
 * <p>What it is for after that is everything a shot is for. A tutorial that
 * shows the thing it is talking about. An intro that pulls off a spawn platform
 * as somebody lands on it. A cutscene at the end of an event. All of them are
 * the same few seconds of somebody else's eyes.
 *
 * <h2>The client does the moving</h2>
 * The camera is a display entity told where to be and how long it has to get
 * there, so the client draws every frame in between at its own frame rate — the
 * same field the display and ragdoll modules animate with, for the same reason.
 * A shot is about ten packets a second and is smooth on a server that is not.
 *
 * <h2>What it does to the player</h2>
 * A player looking through a camera is frozen and their client is drawn as a
 * spectator, because somebody who cannot see their own body should not be
 * walking and should not be shown a hand and a hotbar that belong to it. Both
 * are put back at the end, and only if this module was what did them.
 *
 * <p>Everything else about them is untouched and deliberately so. They are still
 * standing where they were, still have a hitbox, and can still be hit: a camera
 * is not protection, and a plugin that films somebody in the open has left them
 * standing still with their eyes elsewhere.
 *
 * <h2>Nothing the server has to carry</h2>
 * The camera is a packet, so it is not ticked, not saved and not in any chunk.
 * What that costs is that nothing else will give a player their view back, so
 * the module owns that itself: a shot ends when it runs out, when somebody stops
 * it, when the player leaves, dies or changes world, when its plugin is
 * disabled, or when the server stops, and there is no seventh case.
 *
 * @since 1.172.0
 */
public final class Cameras {

    private static final Map<String, PluginCameras> BY_PLUGIN = new ConcurrentHashMap<>();

    private Cameras() {
    }

    /**
     * This plugin's view of the module.
     *
     * @param plugin the plugin
     * @return its view, the same instance every time
     */
    public static @NotNull PluginCameras of(@NotNull Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), name -> new PluginCameras(plugin));
    }

    /**
     * Ends one plugin's shots and forgets it.
     *
     * <p>Called by the library when a plugin is disabled, and before the packet
     * module it needs: giving a view back is a packet to a player who is still
     * on the server.
     *
     * @param pluginName the plugin's name
     */
    public static void release(@NotNull String pluginName) {
        BY_PLUGIN.remove(pluginName);
        CameraRuntime.release(pluginName);
    }

    /** Ends every plugin's shots, on shutdown. */
    public static void releaseAll() {
        BY_PLUGIN.clear();
        CameraRuntime.releaseAll();
    }

    /** How many shots are playing across every plugin, for diagnostics. */
    public static int active() {
        return CameraRuntime.active();
    }

    /** Whether this server can show a camera at all. */
    public static boolean isSupported() {
        return CameraRuntime.isSupported();
    }
}
