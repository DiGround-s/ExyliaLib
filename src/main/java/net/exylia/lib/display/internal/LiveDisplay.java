package net.exylia.lib.display.internal;

import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private volatile DisplayModel model;
    private List<DisplayKeyframe> poses;
    private volatile List<Player> viewers;
    private long endsAt;

    /** Where it was spawned, so a player who walks up later is shown it there. */
    private Location at;

    /** Who it is meant for when that is re-read as players move; {@code null} for a fixed list. */
    private volatile DisplayRuntime.Audience audience;

    /** How long the way back to the first pose should take, once asked; {@code -1} until then. */
    private volatile long settleMillis = -1L;

    /** Whether it is on its way back to the first pose. */
    private boolean settling;

    /**
     * How long one cycle lasts, or {@code 0} when the poses play once.
     *
     * <p>Not the last pose's moment: a piece whose poses were thinned out ends
     * its list wherever its own movement stopped mattering, and every piece of
     * one body has to come round again on the same beat or the body tears
     * itself apart over a few dozen cycles.
     */
    private long cycleMillis;

    /** Where a cycle goes back to: the end of the entry, which plays once. */
    private long loopFromMillis;
    private double accel;
    private double maxSpeed;

    /** When the cycle being played began, which moves on at every wrap. */
    private long cycleStartedAt;

    /**
     * How much quicker than written this cycle is played.
     *
     * <p>Starts wherever the motion says rather than at one: a loop that is
     * handed a rolled tempo has to be quicker from its first cycle, not from
     * its second.
     */
    private double speed;

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
        this.loopFromMillis = motion.loopFromMillis();
        this.accel = motion.accel();
        this.maxSpeed = motion.maxSpeed();
        this.speed = motion.startSpeed();
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
    synchronized void spawn(DisplaySink sink, Location at) {
        this.at = at;
        sink.spawn(viewers, entityId, model, at, poses.get(0));
    }

    /** Who it is meant for, or {@code null} when its viewers were fixed at spawn. */
    DisplayRuntime.Audience audience() {
        return audience;
    }

    void audience(DisplayRuntime.Audience tracked) {
        this.audience = tracked;
    }

    /** Who it is on screen for right now. */
    List<Player> viewers() {
        return viewers;
    }

    /** Where it was spawned. */
    synchronized Location location() {
        return at;
    }

    /**
     * Brings who sees it in line with who should.
     *
     * <p>A player who has walked into range is spawned it in the pose it is in
     * now and sent the pose it is heading for over the time left, so they see
     * the same frame as everyone else rather than the start of the effect. A
     * player who has walked out is sent the destroy.
     *
     * @param present who should see it now
     * @param room    how many more viewers the budget allows
     * @return how many viewers it gained, negative when it lost them
     */
    synchronized int track(DisplaySink sink, long now, List<Player> present, int room) {
        if (gone) {
            return 0;
        }
        List<Player> before = viewers;
        Set<Player> kept = new HashSet<>(present);
        List<Player> left = new ArrayList<>();
        List<Player> after = new ArrayList<>(present.size());
        for (Player viewer : before) {
            if (kept.remove(viewer)) {
                after.add(viewer);
            } else {
                left.add(viewer);
            }
        }
        List<Player> joined = new ArrayList<>(kept.size());
        for (Player viewer : present) {
            if (kept.contains(viewer) && joined.size() < room) {
                joined.add(viewer);
            }
        }
        if (!left.isEmpty()) {
            sink.destroy(left, entityId);
        }
        if (!joined.isEmpty()) {
            sink.spawn(joined, entityId, model, at, poseAt(now));
            if (!settling && nextPose > 1) {
                DisplayKeyframe heading = poses.get(nextPose - 1);
                long remaining = (long) ((heading.atMillis() - elapsed(now)) / speed);
                if (remaining > 0L) {
                    sink.pose(joined, entityId, model, heading, (int) Math.max(1L, remaining / TICK_MS));
                }
            }
            after.addAll(joined);
        }
        viewers = List.copyOf(after);
        return joined.size() - left.size();
    }

    /** Draws something else from now on, sent as metadata only. */
    synchronized void restyle(DisplaySink sink, DisplayModel next) {
        if (gone || next.kind() != model.kind()) {
            return;
        }
        model = next;
        sink.restyle(viewers, entityId, next);
    }

    /** How far through its motion the clock is, in the motion's own time. */
    private long elapsed(long now) {
        return (long) ((now - cycleStartedAt) * speed);
    }

    /**
     * Where the client is drawing it at this moment.
     *
     * <p>The last pose sent is being drawn towards, from the one before it,
     * starting when that one was reached: a straight line for offsets and
     * sizes, the shorter arc for the rotation, as the client does it.
     */
    synchronized DisplayKeyframe poseAt(long now) {
        if (settling || nextPose <= 1) {
            return poses.get(0).at(0L);
        }
        DisplayKeyframe heading = poses.get(nextPose - 1);
        DisplayKeyframe from = poses.get(nextPose - 2);
        long span = heading.atMillis() - from.atMillis();
        double fraction = span <= 0L ? 1.0 : (double) (elapsed(now) - from.atMillis()) / span;
        return from.towards(heading, fraction).at(Math.max(0L, now - cycleStartedAt));
    }

    /**
     * Plays another motion from wherever it is now.
     *
     * <p>The motion's first pose is replaced by the pose on screen, so the
     * client glides from exactly where it was into the rest of it. Its clock,
     * its loop and its life start now.
     *
     * @param keepLife whether the life left is kept when it outlasts the motion
     */
    synchronized void retarget(DisplaySink sink, long now, DisplayMotion next, boolean keepLife) {
        if (gone || settling) {
            return;
        }
        DisplayKeyframe here = poseAt(now);
        List<DisplayKeyframe> joined = new ArrayList<>(next.poses());
        joined.set(0, here.at(joined.get(0).atMillis()));
        poses = joined;
        cycleMillis = next.cycleMillis();
        loopFromMillis = next.loopFromMillis();
        accel = next.accel();
        maxSpeed = next.maxSpeed();
        speed = next.startSpeed();
        cycleStartedAt = now - (long) (joined.get(0).atMillis() / speed);
        nextPose = 1;
        long life = now + Math.min(MAX_LIFE_MS, Math.max(0L, next.lifeMillis())) + TICK_MS;
        endsAt = keepLife ? Math.max(endsAt, life) : life;
        sendDue(sink, now);
    }

    /**
     * Plays the rest of its motion {@code factor} times quicker, from where it
     * is now.
     *
     * <p>The clock is re-anchored so the motion carries on from the same
     * moment, the pose being drawn towards is sent again over the shorter time
     * left, and the life left shrinks by the same factor.
     */
    synchronized void speedUp(DisplaySink sink, long now, double factor) {
        if (gone || settling || !(factor > 0.0) || factor == 1.0) {
            return;
        }
        long elapsed = elapsed(now);
        speed *= factor;
        maxSpeed *= factor;
        cycleStartedAt = now - (long) (elapsed / speed);
        endsAt = now + (long) (Math.max(0L, endsAt - now) / factor);
        if (nextPose > 1) {
            DisplayKeyframe heading = poses.get(nextPose - 1);
            long remaining = (long) ((heading.atMillis() - elapsed) / speed);
            if (remaining > 0L) {
                sink.pose(viewers, entityId, model, heading, (int) Math.max(1L, remaining / TICK_MS));
            }
        }
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
    synchronized boolean advance(DisplaySink sink, long now) {
        if (gone) {
            return true;
        }
        if (now >= endsAt) {
            destroy(sink);
            return true;
        }
        long settle = settleMillis;
        if (settle >= 0L && !settling) {
            settling = true;
            sink.pose(viewers, entityId, model, poses.get(0), (int) Math.max(1L, settle / TICK_MS));
            endsAt = Math.min(endsAt, now + settle + TICK_MS);
        }
        if (settling) {
            return false;
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
        long elapsed = elapsed(now);
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
        long cycle = cycleMillis - loopFromMillis;
        // The moment the cycle was due to end, before the speed changes: the
        // new cycle hangs off that rather than off now, so a late tick costs
        // the dance nothing.
        long dueEnd = cycleStartedAt + (long) (cycleMillis / speed);
        speed = Math.min(maxSpeed, speed * accel);
        cycleStartedAt = dueEnd - (long) (loopFromMillis / speed);
        if (now - dueEnd >= (long) (cycle / speed)) {
            // A cycle or more behind, which is a server that stopped for a
            // second: catching up pose by pose would play the whole dance at
            // once, so the clock is moved to now and the beat is simply lost.
            cycleStartedAt = now - (long) (loopFromMillis / speed);
        }
        // Back to the first pose the cycle proper contains. The pose it goes
        // back to is the one it just reached — a closed cycle ends where it
        // begins — so sending that one again would draw nothing.
        nextPose = 1;
        while (nextPose < poses.size() && poses.get(nextPose).atMillis() <= loopFromMillis) {
            nextPose++;
        }
    }

    /** Removes it from every client, once. */
    synchronized void destroy(DisplaySink sink) {
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

    @Override
    public DisplayModel model() {
        return model;
    }

    @Override
    public void restyle(DisplayModel next) {
        DisplayRuntime.restyle(this, next);
    }

    @Override
    public DisplayKeyframe pose() {
        return gone ? null : DisplayRuntime.poseOf(this);
    }

    @Override
    public void then(DisplayMotion next) {
        DisplayRuntime.retarget(this, next, false);
    }

    @Override
    public void moveTo(DisplayKeyframe target, long millis, DisplayMotion.Easing easing) {
        DisplayKeyframe here = pose();
        if (here == null) {
            return;
        }
        DisplayRuntime.retarget(this, DisplayMotion.between(here, target, millis, easing), true);
    }

    @Override
    public void viewers(List<Player> viewers) {
        DisplayRuntime.retrack(this, List.copyOf(viewers));
    }

    @Override
    public void speedUp(double factor) {
        DisplayRuntime.speedUp(this, factor);
    }

    @Override
    public void settle(long millis) {
        if (millis <= 0L) {
            remove();
            return;
        }
        settleMillis = millis;
    }
}
