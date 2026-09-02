package net.exylia.lib.npc;

import net.exylia.lib.npc.internal.NpcRuntime;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player-shaped entities that exist only on a client.
 *
 * <pre>{@code
 * PluginNpcs npcs = Npcs.of(this);
 *
 * NpcModel corpse = NpcModel.of(victim)
 *         .wearing(victim)
 *         .pose(NpcPose.LYING)
 *         .glow(0xA33B53);
 *
 * npcs.show(corpse, victim.getLocation(), 4000, observers);
 * }</pre>
 *
 * <h2>What this is for</h2>
 * A body left where somebody died. A statue in a lobby. A double of a player
 * that walks off while the real one stands still. Anything that has to look
 * like a person and does not have to be one.
 *
 * <h2>Nothing the server has to carry</h2>
 * These are not entities. They are not ticked, not saved, not in any chunk,
 * have no hitbox the server knows about and cannot be hit, damaged or pushed,
 * and two players standing together can be shown different ones. What that
 * costs is that nothing else will clean them up, so the module owns their lives
 * itself: an NPC goes when its life ends, when its plugin is disabled, or when
 * the server stops, and there is no fourth case.
 *
 * <h2>Its own identity, always</h2>
 * An NPC is announced to the client under a UUID of its own, never the UUID of
 * the player it is wearing. Announcing a second entry under a real player's id
 * is how an NPC takes that player's own skin off their own body, and there is
 * no way back from it short of a relog.
 *
 * <h2>The skin costs nothing</h2>
 * {@link NpcModel#of(org.bukkit.entity.Player)} reads the texture from the
 * connection this server already holds. No lookup, no waiting, no failure
 * halfway &mdash; which matters, because the moment an NPC is usually wanted is
 * a moment nothing may block in.
 *
 * <h2>Written in configuration</h2>
 * Most callers never touch this API. The sequence module has an {@code [NPC]}
 * line &mdash; see {@code docs/npcs.md}.
 *
 * @since 1.88.2
 */
public final class Npcs {

    private static final Map<String, PluginNpcs> BY_PLUGIN = new ConcurrentHashMap<>();

    private Npcs() {
    }

    /**
     * This plugin's view of the module.
     *
     * @param plugin the plugin
     * @return its view, the same instance every time
     */
    public static @NotNull PluginNpcs of(@NotNull Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), PluginNpcs::new);
    }

    /**
     * Removes one plugin's NPCs and forgets it.
     *
     * <p>Called by the library when a plugin is disabled. One left behind
     * stands there wearing somebody's name until that player relogs.
     *
     * @param pluginName the plugin's name
     */
    public static void release(@NotNull String pluginName) {
        BY_PLUGIN.remove(pluginName);
        NpcRuntime.release(pluginName);
    }

    /** Removes every plugin's NPCs, on shutdown. */
    public static void releaseAll() {
        BY_PLUGIN.clear();
        NpcRuntime.releaseAll();
    }

    /** How many NPCs are on screen across every plugin, for diagnostics. */
    public static int active() {
        return NpcRuntime.active();
    }

    /** Whether this server can show NPCs at all. */
    public static boolean isSupported() {
        return NpcRuntime.isSupported();
    }
}
