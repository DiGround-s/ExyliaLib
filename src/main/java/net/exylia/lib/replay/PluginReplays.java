package net.exylia.lib.replay;

import net.exylia.lib.replay.internal.ReplayRuntime;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

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
        return play(replay, at, viewers, ReplayWorld.PACKET);
    }

    /**
     * The same, saying how the arena's blocks should come back.
     *
     * <p>{@link ReplayWorld#SOLID} for a caller that owns the place outright,
     * which is the only way a viewer can stand on what the match built without
     * the server arguing with their client about it.
     *
     * @param replay  what to show
     * @param at      the anchor to rebuild it around
     * @param viewers who sees it
     * @param world   how the block changes are put back
     * @return the handle, or {@code null}
     * @since 1.179.0
     */
    public @Nullable ReplayPlayback play(@NotNull Replay replay, @NotNull Location at,
                                         @NotNull List<Player> viewers,
                                         @NotNull ReplayWorld world) {
        return ReplayRuntime.play(pluginName, replay, at, viewers, world);
    }

    /**
     * Plays a recording with one anchor per scene, for a recording that moved
     * from one place to another.
     *
     * <p>A scene with no anchor of its own is put where it was relative to the
     * first one, when both were recorded in the same world, and on top of the
     * first one otherwise. {@code List.of(replay.scenes().get(0).origin())} and
     * so on plays it back in the very places it happened.
     *
     * @param replay  what to show
     * @param anchors one per scene, in order; at least the first
     * @param viewers who sees it
     * @param world   how the block changes are put back
     * @return the handle, or {@code null}
     * @since 1.241.0
     */
    public @Nullable ReplayPlayback play(@NotNull Replay replay, @NotNull List<Location> anchors,
                                         @NotNull List<Player> viewers, @NotNull ReplayWorld world) {
        return ReplayRuntime.play(pluginName, replay, anchors, viewers, world, false);
    }

    /**
     * Rebuilds the ground a recording happened on, on the temporary world, so
     * it can be watched anywhere.
     *
     * <p>Only for a recording that carries its terrain, which is what a black
     * box capture does. The future completes once every block is down.
     *
     * @param replay what to stage
     * @return the stage
     * @since 1.241.0
     */
    public @NotNull CompletableFuture<ReplayStage> stage(@NotNull Replay replay) {
        return ReplayRuntime.stage(pluginName, replay);
    }

    /**
     * Starts this plugin's use of the black box: the last stretch of everything
     * around every player, cut out after the fact.
     *
     * <p>One box serves the whole server. A second plugin asking for one shares
     * it, and it keeps the larger of what everybody asked for.
     *
     * @param settings how much to keep
     * @return this plugin's handle to it
     * @since 1.241.0
     */
    public @NotNull ReplayBlackBox blackBox(@NotNull BlackBoxSettings settings) {
        return ReplayRuntime.openBox(pluginName, settings);
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
