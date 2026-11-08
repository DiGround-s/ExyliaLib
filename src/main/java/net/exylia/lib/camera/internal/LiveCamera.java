package net.exylia.lib.camera.internal;

import net.exylia.lib.camera.CameraHandle;
import net.exylia.lib.camera.CameraShot;
import net.exylia.lib.packet.Packets;
import net.exylia.lib.packet.PluginPackets;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import net.kyori.adventure.text.Component;
import java.util.UUID;
import java.util.function.Consumer;

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

    private static final long PRESS_GAP = 250L;

    private final Plugin plugin;
    private final List<Player> viewers;
    private final int entityId;
    private final List<CameraPath.Frame> frames;
    private final boolean loop;
    /** What rides the camera, or {@code null} for a bare shot. */
    private final CameraOverlay overlay;
    private volatile long startedAt;

    /** The moment to hold at, or {@code -1}; the moment it is held at, or {@code -1}. */
    private volatile long holdAt = -1;
    private volatile long heldAt = -1;

    /** Who hears presses while it is held, and the click catcher for it. */
    private volatile Consumer<Player> press;
    private volatile int hitboxId = -1;
    private volatile long lastPress;
    private volatile double drift;
    private long lastBreath;

    /** What was already true of each viewer before the shot touched them. */
    private final boolean[] wasFrozen;
    private final boolean[] wasCameraView;

    /** The next position to send; one, because the first went out with the spawn. */
    private int nextFrame = 1;

    private volatile boolean gone;

    /** The last position sent: where the viewer sees from, for everything that asks. */
    private volatile int shown;

    LiveCamera(Plugin plugin, List<Player> viewers, int entityId,
               List<CameraPath.Frame> frames, boolean loop, long now) {
        this(plugin, viewers, entityId, frames, loop, now, null);
    }

    LiveCamera(Plugin plugin, List<Player> viewers, int entityId,
               List<CameraPath.Frame> frames, boolean loop, long now, CameraOverlay overlay) {
        this.overlay = overlay;
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
        if (overlay != null) {
            // Seated before the view moves into the camera, so the first frame
            // the player sees through it already has its bars and its fade.
            overlay.start(sink, viewers, entityId, frames.get(0));
        }
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
        long target = holdAt;
        if (target >= 0 && now - startedAt >= target) {
            heldAt = target;
            holdAt = -1;
        }
        long due = sendDue(sink, now);
        if (heldAt >= 0) {
            breathe(sink, now);
            return false;
        }
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

    /** A slow circle round the place a held shot stands at, one sample at a time. */
    private void breathe(CameraSink sink, long now) {
        if (drift <= 0 || nextFrame <= 0 || now - lastBreath < CameraShot.SAMPLE_MILLIS) {
            return;
        }
        lastBreath = now;
        CameraPath.Frame at = frames.get(Math.min(shown, frames.size() - 1));
        double t = now / 1000.0;
        sink.move(viewers, entityId, new CameraPath.Frame(at.x() + drift * Math.sin(t * 0.7),
                at.y() + drift * 0.6 * Math.sin(t * 0.43 + 1), at.z() + drift * Math.cos(t * 0.53),
                at.yaw(), at.pitch(), false));
    }

    /** How far the camera breathes while held. */
    void drift(double blocks) {
        drift = blocks;
    }

    /** Sends every position the clock has reached, and answers where it is. */
    private long sendDue(CameraSink sink, long now) {
        long elapsed = heldAt >= 0 ? heldAt : now - startedAt;
        long due = elapsed / CameraShot.SAMPLE_MILLIS;
        while (nextFrame < frames.size() && nextFrame <= due) {
            sink.move(viewers, entityId, frames.get(nextFrame));
            shown = nextFrame;
            nextFrame++;
        }
        if (overlay != null) {
            overlay.update(sink, viewers, elapsed, now);
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
        press = null;
        int hitbox = hitboxId;
        if (hitbox >= 0) {
            hitboxId = -1;
            sink.destroy(viewers, hitbox);
        }
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
        if (overlay != null) {
            for (int panel : overlay.ids()) {
                sink.destroy(viewers, panel);
            }
        }
        sink.destroy(viewers, entityId);
    }

    /** How long the client has to reach each position, in ticks. */
    private static int holdTicks() {
        return (int) Math.max(1L, CameraShot.SAMPLE_MILLIS / 50L);
    }

    /** Where the camera is now, {@code x, y, z, yaw, pitch}; {@code null} once it has ended. */
    double[] where() {
        if (gone) {
            return null;
        }
        CameraPath.Frame at = frames.get(Math.min(shown, frames.size() - 1));
        return new double[]{at.x(), at.y(), at.z(), at.yaw(), at.pitch()};
    }

    @Override
    public void pauseAt(long millis) {
        holdAt = Math.max(0, millis);
    }

    @Override
    public void resume() {
        long held = heldAt;
        holdAt = -1;
        if (held >= 0) {
            startedAt = CameraRuntime.now() - held;
            heldAt = -1;
        }
    }

    @Override
    public boolean isPaused() {
        return heldAt >= 0;
    }

    @Override
    public long elapsedMillis() {
        long held = heldAt;
        return held >= 0 ? held : CameraRuntime.now() - startedAt;
    }

    @Override
    public void onPress(Consumer<Player> listener) {
        if (gone) {
            return;
        }
        press = listener;
        CameraSink sink = CameraRuntime.sink();
        int hitbox = hitboxId;
        if (listener == null && hitbox >= 0) {
            hitboxId = -1;
            sink.destroy(viewers, hitbox);
        } else if (listener != null && hitbox < 0) {
            int id = sink.newEntityId();
            sink.spawnHitbox(viewers, id, frames.get(Math.min(shown, frames.size() - 1)));
            hitboxId = id;
        }
    }

    /** Whether this shot takes a click on this entity: its catcher. */
    boolean catches(int entityId) {
        return entityId == hitboxId;
    }

    /** A viewer pressed something; one press may arrive as a click and a swing. */
    void pressed(UUID who) {
        Consumer<Player> listener = press;
        if (listener == null || heldAt < 0) {
            return;
        }
        long now = CameraRuntime.now();
        if (now - lastPress < PRESS_GAP) {
            return;
        }
        lastPress = now;
        for (Player viewer : viewers) {
            if (viewer.getUniqueId().equals(who)) {
                listener.accept(viewer);
                return;
            }
        }
    }

    @Override
    public void revealAll() {
        if (overlay != null) {
            overlay.revealAll();
        }
    }

    @Override
    public boolean isRevealing() {
        return overlay != null && overlay.revealing();
    }

    @Override
    public void caption(Component line) {
        if (overlay != null) {
            overlay.caption(line);
        }
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
