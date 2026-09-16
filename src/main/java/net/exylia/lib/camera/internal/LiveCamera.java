package net.exylia.lib.camera.internal;

import net.exylia.lib.camera.CameraHandle;
import net.exylia.lib.camera.CameraShot;
import net.exylia.lib.packet.Packets;
import net.exylia.lib.packet.PluginPackets;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * One shot that is currently on somebody's screen.
 *
 * <p>Holds where it is in its own path and what it has to put back, and nothing
 * else. The driver walks these once a tick and asks each one whether it has
 * anything to send, which for most of them is a comparison of two longs.
 *
 * <h2>It only puts back what it took</h2>
 * A shot freezes its viewers and draws their client as a spectator, because a
 * player looking through a camera cannot see where they are walking and should
 * not be shown a hand and a hotbar belonging to a body that is not on screen.
 * Both of those are things another plugin may already have done to the same
 * player for its own reasons &mdash; a cutscene, a staff mode, a countdown that
 * pins them. What was already true when the shot started is therefore recorded
 * and left alone at the end: a camera that unfroze everybody it filmed would let
 * a player walk out of somebody else's cutscene.
 */
final class LiveCamera implements CameraHandle {

    private final Plugin plugin;
    private final List<Player> viewers;
    private final int entityId;
    private final List<CameraPath.Frame> frames;
    private final boolean loop;
    private long startedAt;

    /** What was already true of each viewer before the shot touched them. */
    private final boolean[] wasFrozen;
    private final boolean[] wasCameraView;

    /** The next position to send; one, because the first went out with the spawn. */
    private int nextFrame = 1;

    private volatile boolean gone;

    LiveCamera(Plugin plugin, List<Player> viewers, int entityId,
               List<CameraPath.Frame> frames, boolean loop, long now) {
        this.plugin = plugin;
        this.viewers = viewers;
        this.entityId = entityId;
        this.frames = frames;
        this.loop = loop;
        this.startedAt = now;
        this.wasFrozen = new boolean[viewers.size()];
        this.wasCameraView = new boolean[viewers.size()];
    }

    /** Which plugin's shot this is. */
    String owner() {
        return plugin.getName();
    }

    /** Who is looking through it. */
    List<Player> viewers() {
        return viewers;
    }

    /**
     * Takes every viewer's eyes, and pins the body each of them left behind.
     *
     * <p>Called from the thread that asked for the shot, which is the one that
     * owns these players.
     */
    void start(CameraSink sink) {
        PluginPackets packets = Packets.of(plugin);
        for (int index = 0; index < viewers.size(); index++) {
            Player viewer = viewers.get(index);
            wasFrozen[index] = packets.movement().isFrozen(viewer);
            wasCameraView[index] = packets.fakeGameMode().isCameraView(viewer);
            if (!wasFrozen[index]) {
                packets.movement().freeze(viewer);
            }
            if (!wasCameraView[index]) {
                packets.fakeGameMode().cameraView(viewer, true);
            }
        }
        sink.spawn(viewers, entityId, frames.get(0), holdTicks());
        sink.look(viewers, entityId);
    }

    /**
     * Sends whatever is due, and says whether this shot is finished.
     *
     * <p>Positions are evenly spaced, so which one is due is a division rather
     * than a search. More than one falls due together when the server misses a
     * tick, and they are all sent: skipping to the newest would cut the corner
     * the shot was written to go round.
     *
     * @param sink where packets go
     * @param now  the current time
     * @return whether it has ended and should be forgotten
     */
    boolean advance(CameraSink sink, long now) {
        if (gone) {
            return true;
        }
        long due = sendDue(sink, now);
        // One hold past the last position, not on it: the client is still
        // drawing its way into that position when the moment arrives, and
        // ending there takes the last beat off every shot.
        // A looping path comes round one sample early, because its last
        // position is its first one: holding the shared position for a sample
        // before starting again is a hitch once a cycle, forever.
        if (due >= (loop ? frames.size() - 1 : frames.size())) {
            if (!loop) {
                end(sink);
                return true;
            }
            // Round again from where the path was due to end rather than from
            // now, so a late tick costs the shot nothing. Frame zero is not
            // re-sent: a closed path ends where it began and the client is
            // already there.
            startedAt += (long) (frames.size() - 1) * CameraShot.SAMPLE_MILLIS;
            if (now - startedAt >= (long) (frames.size() - 1) * CameraShot.SAMPLE_MILLIS) {
                startedAt = now;
            }
            nextFrame = 1;
            // On this pass, not the next: a cycle that waited a tick to start
            // would lose a frame every time round.
            sendDue(sink, now);
        }
        return false;
    }

    /** Sends every position the clock has reached, and answers where it is. */
    private long sendDue(CameraSink sink, long now) {
        long due = (now - startedAt) / CameraShot.SAMPLE_MILLIS;
        while (nextFrame < frames.size() && nextFrame <= due) {
            sink.move(viewers, entityId, frames.get(nextFrame));
            nextFrame++;
        }
        return due;
    }

    /**
     * Gives every eye back and takes the camera away, once.
     *
     * <p>The view is restored before the entity is destroyed, never after: a
     * client still looking through an entity that stops existing is a client
     * rendering from nowhere, and nothing the server sends afterwards is
     * guaranteed to find it again.
     */
    void end(CameraSink sink) {
        if (gone) {
            return;
        }
        gone = true;
        PluginPackets packets = Packets.of(plugin);
        for (int index = 0; index < viewers.size(); index++) {
            Player viewer = viewers.get(index);
            sink.look(List.of(viewer), viewer.getEntityId());
            if (!wasCameraView[index]) {
                packets.fakeGameMode().cameraView(viewer, false);
            }
            if (!wasFrozen[index]) {
                packets.movement().unfreeze(viewer);
            }
        }
        sink.destroy(viewers, entityId);
    }

    /** How long the client has to reach each position, in ticks. */
    private static int holdTicks() {
        return (int) Math.max(1L, CameraShot.SAMPLE_MILLIS / 50L);
    }

    @Override
    public void stop() {
        CameraRuntime.stop(this);
    }

    @Override
    public boolean isRunning() {
        return !gone;
    }
}
