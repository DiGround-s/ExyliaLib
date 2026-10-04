package net.exylia.lib.replay;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The ground a recording happened on, rebuilt somewhere it can be watched.
 *
 * <p>A recording with terrain &mdash; what the black box captures &mdash; is
 * staged on the library's temporary world: every scene gets its own plot,
 * the ground is laid down there as it was when the scene started, and the
 * playback runs on it with the arena written for real. A viewer can walk on it,
 * into the crater, up the tower somebody built, and the world they are standing
 * in agrees with every block they see.
 *
 * <p>Nothing on the server the recording came from is touched, and it does not
 * matter whether that place still looks the same, or whether this is even the
 * same server.
 *
 * <pre>{@code
 * replays.stage(replay).thenAccept(stage -> Tasks.at(viewer, () -> {
 *     viewer.teleportAsync(stage.locate(0, focus)).thenRun(() -> stage.play(List.of(viewer)));
 * }));
 * }</pre>
 *
 * @since 1.241.0
 */
public interface ReplayStage {

    /** The recording on this stage. */
    @NotNull Replay replay();

    /**
     * Starts it for a list of viewers, who are carried along when it cuts from
     * one scene to the next.
     *
     * @param viewers who watches; already standing on the stage
     * @return the playback, or {@code null} when it cannot be shown
     */
    @Nullable ReplayPlayback play(@NotNull List<Player> viewers);

    /**
     * Where a scene's anchor is on this stage.
     *
     * @param scene its index in {@link Replay#scenes()}
     * @return the anchor
     */
    @NotNull Location anchor(int scene);

    /**
     * Where somebody was on a tick, on this stage.
     *
     * @param tick  the tick
     * @param actor who
     * @return where, or {@code null} when they were not there
     */
    @Nullable Location locate(int tick, @NotNull UUID actor);

    /**
     * Ends every playback on it and lets it go. The plots are never handed out
     * again in this run, and the temporary world is wiped at the next start.
     */
    void close();
}
