package net.exylia.lib.ragdoll;

import net.exylia.lib.display.Displays;
import net.exylia.lib.ragdoll.internal.SkinCache;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bodies that come apart into their own pieces, sent as packets.
 *
 * <pre>{@code
 * PluginRagdolls ragdolls = Ragdolls.of(this);
 *
 * ragdolls.show(RagdollModel.of(victim).detail(2).light(15),
 *         RagdollMotion.builder().life(2.4).up(7).spin(2).build(),
 *         victim.getLocation(), nearby);
 * }</pre>
 *
 * <h2>What this is for</h2>
 * The moment a kill effect is actually about. A body that stands for a beat and
 * then leaves in six directions is the difference between a death that happened
 * to a player and a firework that happened near one.
 *
 * <h2>Why it is not an NPC</h2>
 * A fake player is one entity that can only be moved by teleporting it. It
 * moves once a tick at best, stutters whenever the server does, cannot turn
 * smoothly, and cannot come apart at all. A ragdoll is a handful of display
 * entities whose entire flight is worked out in advance and handed to the
 * client, which then draws it at its own frame rate. It is smooth on a server
 * that is not, and it costs fewer packets than the NPC it replaces.
 *
 * <h2>The skin</h2>
 * The head is a real player head, so the face is exact. With a MineSkin key the
 * rest of the body wears its real skin too: it is cut into pieces each
 * repainted as a head texture, made once and kept in the database of the
 * plugin that shows the bodies. Without a key, and for a piece whose texture
 * is still on its way, the piece is drawn in the nearest block to the colour
 * that part of the skin actually is. Skins are read once, in the background,
 * when their owner joins.
 *
 * <h2>Nothing the server has to carry</h2>
 * Every piece is a display, so this inherits the display module's whole
 * lifecycle: nothing is ticked, nothing is saved, the server's
 * {@code displays.yml} budget applies, and everything is taken off the clients
 * showing it when the plugin is disabled.
 *
 * @since 1.120.0
 */
public final class Ragdolls {

    private static final Map<String, PluginRagdolls> BY_PLUGIN = new ConcurrentHashMap<>();

    private Ragdolls() {
    }

    /**
     * This plugin's view of the module.
     *
     * @param plugin the plugin
     * @return its view, the same instance every time
     */
    public static @NotNull PluginRagdolls of(@NotNull Plugin plugin) {
        // A plugin that shows bodies keeps the textures they are drawn with, and
        // registering it now means its players' skins are ready before it does.
        net.exylia.lib.ragdoll.internal.RagdollTextures.register(plugin);
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), PluginRagdolls::new);
    }

    /**
     * Reads a player's skin into memory, in the background.
     *
     * <p>Called by the library when they join, minutes before anybody kills
     * them. A skin that has not been read yet costs a body its colours for one
     * death and nothing else.
     *
     * @param player whose skin
     */
    public static void warm(@NotNull Player player) {
        SkinCache.warm(player);
    }

    /** Whether this server can show ragdolls at all. */
    public static boolean isSupported() {
        return Displays.isSupported();
    }

    /** Forgets one plugin's view. Called by the library when it is disabled. */
    public static void release(@NotNull String pluginName) {
        // The pieces themselves are displays, and the display module takes them
        // off every client under the same plugin name. There is nothing else
        // here that outlives a plugin.
        BY_PLUGIN.remove(pluginName);
        net.exylia.lib.ragdoll.internal.RagdollTextures.release(pluginName);
    }

    /** Forgets every plugin's view and every decoded skin, on shutdown. */
    public static void releaseAll() {
        BY_PLUGIN.clear();
        SkinCache.clear();
        net.exylia.lib.ragdoll.internal.RagdollTextures.releaseAll();
    }
}
