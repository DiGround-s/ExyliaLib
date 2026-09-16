package net.exylia.lib.replay;

import net.exylia.lib.replay.internal.ReplayRuntime;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recording what happened, and watching it again.
 *
 * <pre>{@code
 * PluginReplays replays = Replays.of(this);
 *
 * ReplayRecorder recorder = replays.record(arena.spawn());
 * recorder.follow(red);
 * recorder.follow(blue);
 *
 * // when the duel ends
 * duels.saveReplay(duelId, recorder.stop().toBytes());
 *
 * // when somebody asks to see it
 * replays.play(Replay.from(blob), arena.spawn(), List.of(viewer));
 * }</pre>
 *
 * <h2>What a recording actually is</h2>
 * Not a copy of the packets that went out. Every tick, for everybody being
 * followed, the server's own answer to where they were, which way they were
 * looking, how they were standing and what they were holding &mdash; the same
 * state the server decided the fight on. Played back, that is not an
 * approximation of the duel: for the question anybody actually asks a replay,
 * <em>did that hit land</em>, it is the authority, and the player's own screen
 * is the thing that was guessing.
 *
 * <p>What it cannot give back is what a player <em>saw</em>. A client predicts
 * its own movement and is corrected afterwards, so somebody on a bad connection
 * watched their hit land while the server was deciding it had not. The
 * recording shows the server's answer. That is the right answer, and it is not
 * always the popular one.
 *
 * <h2>What it costs</h2>
 * A three minute duel between two players is about twenty kilobytes once it is
 * written out, which is a column in a table rather than a file on a disk.
 * Recording itself is a handful of field reads per followed player per tick.
 *
 * <h2>Watching is private</h2>
 * The bodies are packets. A playback exists on the screens it was given and
 * nowhere else, so two players can watch two different recordings standing in
 * the same arena and neither can touch what the other is looking at.
 *
 * @since 1.175.0
 */
public final class Replays {

    private static final Map<String, PluginReplays> BY_PLUGIN = new ConcurrentHashMap<>();

    private Replays() {
    }

    /**
     * This plugin's view of the module.
     *
     * @param plugin the plugin
     * @return its view, the same instance every time
     */
    public static @NotNull PluginReplays of(@NotNull Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), PluginReplays::new);
    }

    /**
     * Ends one plugin's recordings and playbacks and forgets it.
     *
     * <p>Called by the library when a plugin is disabled. A playback left
     * behind is two bodies standing in an arena wearing somebody's name until
     * those clients relog.
     *
     * @param pluginName the plugin's name
     */
    public static void release(@NotNull String pluginName) {
        BY_PLUGIN.remove(pluginName);
        ReplayRuntime.release(pluginName);
    }

    /** Ends every plugin's recordings and playbacks, on shutdown. */
    public static void releaseAll() {
        BY_PLUGIN.clear();
        ReplayRuntime.releaseAll();
    }

    /** How many recordings are being made across every plugin, for diagnostics. */
    public static int recording() {
        return ReplayRuntime.recording();
    }

    /** How many are being watched. */
    public static int playing() {
        return ReplayRuntime.playing();
    }

    /** Whether a recording can be played back on this server. */
    public static boolean isSupported() {
        return ReplayRuntime.isSupported();
    }
}
