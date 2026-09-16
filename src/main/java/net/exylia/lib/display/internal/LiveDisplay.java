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

    /**
     * The longest anything is allowed to live, whatever it asked for.
     *
     * <p>A looping display is given a life that is really a safety net, and a
     * caller that means "forever" writes a very large number. A day is past any
     * honest use and keeps the arithmetic off the end of a {@code long}.
     */
    private static final long MAX_LIFE_MS = 24L * 60L * 60L * 1000L;

    private final String owner;
    private final int entityId;
    private final DisplayModel model;
    private final List<DisplayKeyframe> poses;
    private final List<Player> viewers;
    private final long endsAt;

    /**
     * How long one cycle lasts, or {@code 0} when the poses play once.
     *
     * <p>Not the last pose's moment: a piece whose poses were thinned out ends
     * its list wherever its own movement stopped mattering, and every piece of
     * one body has to come round again on the same beat or the body tears
     * itself apart over a few dozen cycles.
     */
    private final long cycleMillis;
    private final double accel;
    private final double maxSpeed;

    /** When the cycle being played began, which moves on at every wrap. */
    private long cycleStartedAt;

    /** How much quicker than written this cycle is played. */
    private double speed = 1.0;

    /** What it rides, or {@code 0} when it stands where it was drawn. */
    private final int vehicleId;

    /** The next pose to send; one, because the first went out with the spawn. */
    private int nextPose = 1;

    private volatile boolean gone;

    LiveDisplay(String owner, int entityId, DisplayModel model, DisplayMotion motion,
                List<Player> viewers, long now, int vehicleId) {
        this.vehicleId = vehicleId;
        this.owner = owner;
        this.entityId = entityId;
        this.model = model;
        this.poses = motion.poses();
        this.viewers = viewers;
        this.cycleStartedAt = now;
        this.cycleMillis = motion.cycleMillis();
        this.accel = motion.accel();
        this.maxSpeed = motion.maxSpeed();
        // One tick past the last pose. The client is still drawing its way into
        // that pose when the moment arrives, and removing it then took every
        // falling boulder away a frame before it landed.
        this.endsAt = now + Math.min(MAX_LIFE_MS, Math.max(0L, motion.lifeMillis())) + TICK_MS;
    }

    /** Which plugin's effect this belongs to. */
    String owner() {
        return owner;
    }

    /** The entity it rides, or {@code 0}. */
    int vehicleId() {
        return vehicleId;
    }

    /** Its own entity id, so the seat list can name it. */
    int entityId() {
        return entityId;
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
        long elapsed = sendDue(sink, now);
        if (cycleMillis > 0L && elapsed >= cycleMillis) {
            wrap(now, elapsed);
            // The new cycle's first due pose goes out on this same pass: a loop
            // that waited for the next tick would lose one every cycle.
            sendDue(sink, now);
        }
        return false;
    }

    /** Sends every pose the clock has reached, and answers where the clock is. */
    private long sendDue(DisplaySink sink, long now) {
        long elapsed = (long) ((now - cycleStartedAt) * speed);
        while (nextPose < poses.size() && elapsed >= poses.get(nextPose - 1).atMillis()) {
            DisplayKeyframe target = poses.get(nextPose);
            long span = (long) ((target.atMillis() - poses.get(nextPose - 1).atMillis()) / speed);
            sink.pose(viewers, entityId, model, target, (int) Math.max(1L, span / TICK_MS));
            nextPose++;
        }
        return elapsed;
    }

    /**
     * Starts the cycle again, from wherever the clock actually is.
     *
     * <p>The new cycle begins at the moment the old one was due to end rather
     * than at now, so a tick the server was late for is not a beat the dance
     * loses. Speed climbs on the wrap, which is what makes a loop wind up.
     *
     * <p>The first pose is never re-sent: a closed cycle ends in the pose it
     * began in, so the client is already standing in it and telling it so again
     * is a packet that draws nothing.
     */
    private void wrap(long now, long elapsed) {
        cycleStartedAt += (long) (cycleMillis / speed);
        speed = Math.min(maxSpeed, speed * accel);
        nextPose = 1;
        if (elapsed - cycleMillis >= cycleMillis) {
            // A cycle or more behind, which is a server that stopped for a
            // second: catching up pose by pose would play the whole dance at
            // once, so the clock is moved to now and the beat is simply lost.
            cycleStartedAt = now;
        }
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
