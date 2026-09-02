package net.exylia.lib.npc.internal;

import net.exylia.lib.npc.NpcHandle;
import net.exylia.lib.npc.NpcModel;
import net.exylia.lib.npc.NpcMotion;
import net.exylia.lib.npc.NpcPose;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * One NPC that is currently on somebody's screen.
 *
 * <p>Holds where it stands and when it goes, and nothing else. The driver walks
 * these once a second and asks each one whether its time is up, which is a
 * comparison of two longs.
 */
final class LiveNpc implements NpcHandle {

    private final String owner;
    private final int entityId;
    private final NpcModel model;
    private final List<Player> viewers;
    private final Location at;
    private final NpcMotion motion;
    private final long startedAt;
    private final long endsAt;

    /** Where the body has been put so far, relative to where it appeared. */
    private double sentX;
    private double sentY;
    private double sentZ;
    private boolean posed;

    /** Whether the body itself has been drawn yet. See {@link #spawn}. */
    private boolean drawn;

    /** Whether it has flinched, which waits for whatever is done to it. */
    private boolean flinched;

    /** The yaw last sent, so a body that only turns still turns. */
    private float sentYaw;

    /** When the arm last swung. */
    private long swungAt = Long.MIN_VALUE / 2;

    private volatile boolean gone;

    LiveNpc(String owner, int entityId, NpcModel model, NpcMotion motion, List<Player> viewers,
            Location at, long now, long lifeMillis) {
        this.sentYaw = at.getYaw();
        this.owner = owner;
        this.entityId = entityId;
        this.model = model;
        this.motion = motion;
        this.viewers = viewers;
        this.at = at;
        this.startedAt = now;
        this.endsAt = now + lifeMillis;
    }

    /** Which plugin's effect this belongs to. */
    String owner() {
        return owner;
    }

    /**
     * Announces the identity, and leaves the body for the next tick.
     *
     * <p>The client hangs a player entity's skin and name off its player-list
     * entry, and it looks that entry up when the spawn packet arrives. Sent in
     * the same burst, the entry has not always been processed by then, and what
     * is drawn is nothing at all &mdash; the single most common way a
     * packet NPC comes out invisible. A tick is free here: nothing this module
     * draws lives less than a second.
     */
    void spawn(NpcSink sink) {
        sink.announce(viewers, model);
    }

    /** Draws the body, once, on the tick after the identity was announced. */
    private void draw(NpcSink sink) {
        drawn = true;
        sink.spawn(viewers, entityId, model, at);
    }

    /**
     * Says whether this NPC is finished, taking it away if it is.
     *
     * @param sink where packets go
     * @param now  the current time
     * @return whether it has been removed and should be forgotten
     */
    boolean expired(NpcSink sink, long now) {
        if (gone) {
            return true;
        }
        if (now >= endsAt) {
            destroy(sink);
            return true;
        }
        if (!drawn) {
            // Drawn and then driven in the same tick: a body whose movement
            // waited an extra tick would start a frame behind whatever threw it.
            draw(sink);
        }
        if (!motion.isStill()) {
            drive(sink, now - startedAt);
        }
        return false;
    }

    /**
     * Moves and re-poses the body for this moment.
     *
     * <p>Sends only what changed. A body that has finished moving is a
     * comparison of three doubles a tick, which is what lets one driver run
     * every tick for every NPC on the server without the still ones costing
     * anything.
     */
    private void drive(NpcSink sink, long elapsed) {
        if (!flinched && motion.hurt() && elapsed >= motion.startAfterMillis()) {
            flinched = true;
            sink.hurt(viewers, entityId);
        }
        long swingEvery = motion.swingEveryMillis();
        if (swingEvery > 0 && elapsed >= motion.startAfterMillis()
                && elapsed - swungAt >= swingEvery) {
            swungAt = elapsed;
            sink.swing(viewers, entityId);
        }
        NpcPose then = motion.poseThen();
        if (!posed && then != null && elapsed >= motion.poseAfterMillis()) {
            posed = true;
            sink.pose(viewers, entityId, model, then);
        }
        double[] target = motion.at(elapsed);
        float yaw = at.getYaw() + motion.turnedBy(elapsed);
        double dx = target[0] - sentX;
        double dy = target[1] - sentY;
        double dz = target[2] - sentZ;
        // A quarter of a degree, and a step the protocol can still carry: a
        // relative move is written in 4096ths of a block, so anything above that
        // is a step the client will draw. The difference is kept rather than
        // dropped — sentX only moves when a step is sent — so a hover too slow
        // to clear this in one tick still happens instead of never happening.
        if (Math.abs(dx) < 0.004 && Math.abs(dy) < 0.004 && Math.abs(dz) < 0.004
                && Math.abs(yaw - sentYaw) < 0.25f) {
            return;
        }
        sentX = target[0];
        sentY = target[1];
        sentZ = target[2];
        sentYaw = yaw;
        sink.move(viewers, entityId, dx, dy, dz, yaw, at.getPitch());
    }

    /** Takes it off every client, once. */
    void destroy(NpcSink sink) {
        if (gone) {
            return;
        }
        gone = true;
        // Even one that was never drawn: the identity went out on its own, and
        // an announced entry nobody withdraws is a name the client keeps.
        sink.destroy(viewers, entityId, model.id());
    }

    @Override
    public void remove() {
        NpcRuntime.remove(this);
    }

    @Override
    public boolean isShowing() {
        return !gone;
    }

    @Override
    public void look(float yaw, float pitch) {
        if (!gone) {
            NpcRuntime.sink().look(viewers, entityId, yaw, pitch);
        }
    }

    @Override
    public void lookAt(@NotNull Location target) {
        if (gone || target.getWorld() == null || !target.getWorld().equals(at.getWorld())) {
            return;
        }
        double dx = target.getX() - at.getX();
        double dy = target.getY() - at.getY();
        double dz = target.getZ() - at.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, flat));
        look(yaw, pitch);
    }

    @Override
    public void pose(@NotNull NpcPose pose) {
        if (!gone) {
            NpcRuntime.sink().pose(viewers, entityId, model, pose);
        }
    }
}
