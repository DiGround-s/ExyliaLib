package net.exylia.lib.npc.internal;

import net.exylia.lib.npc.NpcHandle;
import net.exylia.lib.npc.NpcModel;
import net.exylia.lib.npc.NpcMotion;
import net.exylia.lib.npc.NpcPose;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One NPC that is currently on somebody's screen.
 *
 * <p>Holds where it stands and when it goes, and nothing else. The driver walks
 * these once a second and asks each one whether its time is up, which is a
 * comparison of two longs.
 */
final class LiveNpc implements NpcHandle {

    /**
     * How far a relative step can carry, in blocks.
     *
     * <p>The protocol writes one as a short in 4096ths of a block, so eight is
     * the ceiling and anything at all near it overflows into a body that
     * appears on the other side of the arena. Beyond this it is teleported.
     */
    private static final double MAX_STEP = 7.5;

    /**
     * What a relative step is measured in: 4096ths of a block.
     *
     * <p>The protocol carries one as a short in these units, so a step of any
     * other size is not the step the client applies. Believing otherwise is how
     * a body driven twenty times a second walks slowly away from where it is
     * supposed to be: each move loses a fraction, nothing puts it back, and
     * after a minute the body and the place it is meant to be are a block
     * apart.
     */
    private static final double STEP_UNIT = 4096.0;

    /**
     * How many relative steps before one is sent outright instead.
     *
     * <p>Quantising each step keeps the drift at zero in theory. This is for the
     * practice: a chunk the client reloads, a packet it drops, a rounding rule
     * that differs by a unit. Once every five seconds a body says where it
     * actually is, which costs one packet and ends any argument.
     */
    private static final int RESYNC_EVERY = 100;

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

    /** Relative steps since the last outright one. */
    private int stepsSinceResync;

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
        // A life of zero is one the caller keeps: the replay module drives
        // bodies for as long as the recording runs, which is longer than any
        // cap this module would put on a body nobody is watching over.
        this.endsAt = lifeMillis <= 0L ? Long.MAX_VALUE : now + lifeMillis;
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

    /**
     * Draws the body, once, on the tick after the identity was announced.
     *
     * <p>At where it has been moved to rather than where it appeared: a body
     * driven by a replay is put somewhere before the runtime's first tick gets
     * round to drawing it, and spawning it at the old place would have it
     * appear at the arena's anchor and then jump to the player.
     */
    private void draw(NpcSink sink) {
        drawn = true;
        sink.spawn(viewers, entityId, model, current());
    }

    /** Where the client has it, or is about to be told it is. */
    private Location current() {
        Location here = at.clone();
        here.add(sentX, sentY, sentZ);
        here.setYaw(sentYaw);
        return here;
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

    @Override
    public void moveTo(@NotNull Location to) {
        if (gone) {
            return;
        }
        NpcSink sink = NpcRuntime.sink();
        if (sink == null) {
            return;
        }
        double dx = to.getX() - (at.getX() + sentX);
        double dy = to.getY() - (at.getY() + sentY);
        double dz = to.getZ() - (at.getZ() + sentZ);
        // Where the client has it is updated by whichever branch below runs,
        // because a relative step and a teleport leave it in different places:
        // one lands on a 4096th of a block, the other exactly where asked.
        sentYaw = to.getYaw();
        if (!drawn) {
            // Nothing to move yet: the body is drawn on the runtime's next
            // tick, and draw() reads the position recorded here.
            sentX = to.getX() - at.getX();
            sentY = to.getY() - at.getY();
            sentZ = to.getZ() - at.getZ();
            at.setPitch(to.getPitch());
            return;
        }
        if (Math.abs(dx) < MAX_STEP && Math.abs(dy) < MAX_STEP && Math.abs(dz) < MAX_STEP
                && ++stepsSinceResync < RESYNC_EVERY) {
            // Rounded to what the packet can actually carry, and then believed
            // to be exactly that. Recording the step we wanted instead of the
            // step we sent is what makes a body drift.
            double qx = Math.round(dx * STEP_UNIT) / STEP_UNIT;
            double qy = Math.round(dy * STEP_UNIT) / STEP_UNIT;
            double qz = Math.round(dz * STEP_UNIT) / STEP_UNIT;
            sentX += qx;
            sentY += qy;
            sentZ += qz;
            sink.move(viewers, entityId, qx, qy, qz, to.getYaw(), to.getPitch());
            return;
        }
        stepsSinceResync = 0;
        sentX = to.getX() - at.getX();
        sentY = to.getY() - at.getY();
        sentZ = to.getZ() - at.getZ();
        sink.teleport(viewers, entityId, to);
    }

    @Override
    public void equip(@NotNull EquipmentSlot slot, @Nullable ItemStack item) {
        if (!gone) {
            NpcRuntime.sink().equip(viewers, entityId, slot, item);
        }
    }

    @Override
    public void swing() {
        if (!gone) {
            NpcRuntime.sink().swing(viewers, entityId);
        }
    }

    @Override
    public void hurt() {
        if (!gone) {
            NpcRuntime.sink().hurt(viewers, entityId);
        }
    }
}
