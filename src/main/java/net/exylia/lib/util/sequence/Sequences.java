package net.exylia.lib.util.sequence;

import net.exylia.lib.util.sequence.internal.SequenceAccess;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Choreographed effects, written in configuration.
 *
 * <pre>{@code
 * // config:
 * //   effects:
 * //     - '[CIRCLE] FLAME;radius:1.5;points:24'
 * //     - '[SOUND] ENTITY_BLAZE_DEATH;1.5;0.8'
 * //     - '[DELAY] 0.15'
 * //     - '[EXPLOSION]'
 *
 * PluginSequences sequences = Sequences.of(this);
 * Sequence blast = sequences.compile(config.getStringList("effects"));
 *
 * sequences.play(blast, SequenceTarget.at(victim.getLocation()).by(killer));
 * }</pre>
 *
 * <h2>What this is for</h2>
 * An effect that is more than one thing: a ring of flame, then a sound, then a
 * pause, then an explosion. {@code Effects} plays one title, one sound, one
 * particle from a structured record; a sequence plays a list of them in order,
 * which is what a kill effect or an arrow trail actually is.
 *
 * <p>The two are meant to be used together. A menu's open sound is an
 * {@code EffectConfig}; a hundred-line firework display is a {@code Sequence}.
 *
 * <h2>The syntax is ExyliaCommons'</h2>
 * Deliberately identical, down to the parameter names and defaults, so the
 * files a server already has keep working when a plugin migrates. Three
 * behaviours were fixed rather than copied, all documented in
 * {@code docs/sequences.md}: {@code SPHERE} now honours {@code y:}, {@code
 * TORUS} no longer adds a hidden block of height, and a dust particle with no
 * colour is drawn white instead of being silently dropped.
 *
 * <h2>Compiled once</h2>
 * {@link PluginSequences#compile} does the parsing and the trigonometry when
 * the file is read. Playing is arithmetic and packets. ExyliaCommons re-parsed
 * every string and re-derived every point on each play, so a full arena of
 * players dying re-did that work on the region thread every time.
 *
 * @since 1.30.0
 */
public final class Sequences {

    private static final Map<String, PluginSequences> BY_PLUGIN = new ConcurrentHashMap<>();

    private Sequences() {
    }

    /**
     * This plugin's view of the module.
     *
     * <p>The same instance every time, so a plugin may call this wherever it
     * needs one rather than passing it around.
     *
     * @param plugin the plugin
     * @return its view
     */
    public static @NotNull PluginSequences of(@NotNull Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(),
                key -> new PluginSequences(plugin, SequenceAccess.builtInShapes()));
    }

    /**
     * Stops and forgets one plugin's sequences.
     *
     * <p>Called by the library when a plugin is disabled. Anything still
     * drawing stops there and then: a frame scheduled by a plugin whose
     * classloader is going away must not fire.
     *
     * @param pluginName the plugin's name
     */
    public static void release(@NotNull String pluginName) {
        PluginSequences sequences = BY_PLUGIN.remove(pluginName);
        if (sequences != null) {
            sequences.stopAll();
        }
    }

    /** Stops and forgets every plugin's sequences, on shutdown. */
    public static void releaseAll() {
        for (String name : Map.copyOf(BY_PLUGIN).keySet()) {
            release(name);
        }
    }

    /** How many sequences are playing across every plugin, for diagnostics. */
    public static int active() {
        int total = 0;
        for (PluginSequences sequences : BY_PLUGIN.values()) {
            total += sequences.active();
        }
        return total;
    }
}
