package net.exylia.lib.replay;

import net.exylia.lib.replay.internal.ReplayRuntime;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One plugin's view of the replay module.
 *
 * <pre>{@code
 * PluginReplays replays = Replays.of(this);
 *
 * ReplayRecorder recorder = replays.record(arena.spawn());
 * recorder.follow(red);
 * recorder.follow(blue);
 * // ...
 * byte[] blob = recorder.stop().toBytes();
 *
 * replays.play(Replay.from(blob), arena.spawn(), List.of(viewer));
 * }</pre>
 *
 * @since 1.175.0
 */
public final class PluginReplays {

    private final String pluginName;

    PluginReplays(@NotNull String pluginName) {
        this.pluginName = pluginName;
    }

    /**
     * Starts a recording.
     *
     * <p>Nobody is in it until somebody is {@link ReplayRecorder#follow
     * followed}, and it records nothing until then.
     *
     * <h2>What the anchor is for</h2>
     * Every position in the recording is kept relative to it, so the file
     * carries no world and no coordinates of its own. An arena's spawn is the
     * obvious one: the same recording then plays back in that arena wherever it
     * has been pasted this time, on this server or another.
     *
     * @param anchor what the recording is measured against
     * @return the recording, which must be stopped or cancelled
     */
    public @NotNull ReplayRecorder record(@NotNull Location anchor) {
        return ReplayRuntime.record(pluginName, anchor);
    }

    /**
     * Shows a recording to a list of players, from its first tick.
     *
     * <p>It starts running immediately. Pause it right away for a playback that
     * waits on a button.
     *
     * @param replay  what to show
     * @param at      the anchor to rebuild it around, which should be the same
     *                place in the arena the recording was anchored to
     * @param viewers who sees it; the list is kept, so hand over one nobody
     *                else is going to change
     * @return the handle, or {@code null} when nobody can see it, the recording
     *         is empty, or the server has no PacketEvents
     */
    public @Nullable ReplayPlayback play(@NotNull Replay replay, @NotNull Location at,
                                         @NotNull List<Player> viewers) {
        return ReplayRuntime.play(pluginName, replay, at, viewers);
    }

    /** Ends everything this plugin is recording and showing. */
    public void stopAll() {
        ReplayRuntime.release(pluginName);
    }

    /** How many recordings this plugin is making. */
    public int recording() {
        return ReplayRuntime.recording(pluginName);
    }

    /** How many playbacks this plugin is showing. */
    public int playing() {
        return ReplayRuntime.playing(pluginName);
    }

    /**
     * Whether a recording can be played back on this server.
     *
     * <p>Recording works anywhere: it reads the server's own state and writes
     * numbers. Playing one back draws packet bodies, which needs PacketEvents.
     */
    public boolean isSupported() {
        return ReplayRuntime.isSupported();
    }
}
