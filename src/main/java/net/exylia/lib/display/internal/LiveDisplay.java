package net.exylia.lib.display.internal;

import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * One display that is currently on somebody's screen.
 *
 * <p>Holds where it is in its own animation and nothing else. The driver walks
 * these once a tick and asks each one whether it has anything to send, which is
 * a comparison of two longs for the great majority of them.
 */
final class LiveDisplay implements DisplayHandle {

    private static final long TICK_MS = 50L;

    private final String owner;
    private final int entityId;
    private final DisplayModel model;
    private final List<DisplayKeyframe> poses;
    private final List<Player> viewers;
    private final long startedAt;
    private final long endsAt;

    /** The next pose to send; one, because the first went out with the spawn. */
    private int nextPose = 1;

    private volatile boolean gone;

    LiveDisplay(String owner, int entityId, DisplayModel model, DisplayMotion motion,
                List<Player> viewers, long now) {
        this.owner = owner;
        this.entityId = entityId;
        this.model = model;
        this.poses = motion.poses();
        this.viewers = viewers;
        this.startedAt = now;
        this.endsAt = now + motion.lifeMillis();
    }

    /** Which plugin's effect this belongs to. */
    String owner() {
        return owner;
    }

    /**
     * What this display costs the server's budget: one per player it is sent
     * to.
     *
     * <p>Read back when it ends, so the number given back is the number that
     * was taken even if a viewer has since logged off.
     */
    int viewerCost() {
        return viewers.size();
    }

    /** Sends the first pose along with the spawn. */
    void spawn(DisplaySink sink, Location at) {
        sink.spawn(viewers, entityId, model, at, poses.get(0));
    }

    /**
     * Sends whatever is due, and says whether this display is finished.
     *
     * <p>A pose is sent when the display reaches the <em>previous</em> pose's
     * moment, carrying the gap between them as its duration: the client is
     * always drawing towards the next pose rather than catching up to the last
     * one.
     *
     * @param sink where packets go
     * @param now  the current time
     * @return whether it has been destroyed and should be forgotten
     */
    boolean advance(DisplaySink sink, long now) {
        if (gone) {
            return true;
        }
        if (now >= endsAt) {
            destroy(sink);
            return true;
        }
        long elapsed = now - startedAt;
        while (nextPose < poses.size() && elapsed >= poses.get(nextPose - 1).atMillis()) {
            DisplayKeyframe target = poses.get(nextPose);
            long span = target.atMillis() - poses.get(nextPose - 1).atMillis();
            sink.pose(viewers, entityId, model, target, (int) Math.max(1L, span / TICK_MS));
            nextPose++;
        }
        return false;
    }

    /** Removes it from every client, once. */
    void destroy(DisplaySink sink) {
        if (gone) {
            return;
        }
        gone = true;
        sink.destroy(viewers, entityId);
    }

    @Override
    public void remove() {
        DisplayRuntime.remove(this);
    }

    @Override
    public boolean isShowing() {
        return !gone;
    }
}
