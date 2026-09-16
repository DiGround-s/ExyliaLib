package net.exylia.lib.replay.internal;

import net.exylia.lib.npc.NpcPose;
import net.exylia.lib.replay.Replay;
import net.exylia.lib.replay.ReplayActor;
import net.exylia.lib.replay.ReplayMark;
import net.exylia.lib.replay.ReplayRecorder;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * One recording while it is running.
 *
 * <h2>A timer each, not one timer</h2>
 * Every followed player is sampled by a task bound to that player, which is
 * what makes this work on Folia: a player's position may only be read from the
 * region that owns them, and one shared timer reading everybody would be
 * reading across regions. Each task writes only into its own arrays, so there
 * is nothing to lock either.
 *
 * <h2>The clock is the wall, not the task</h2>
 * Which frame a sample belongs to is worked out from how long the recording has
 * been running, not from how many times the task has fired. A server that skips
 * ticks therefore leaves gaps, and a gap is filled by repeating the frame
 * before it: the recording stays the same length as the fight really was, and
 * plays back showing what everybody watching actually saw, which was somebody
 * standing still for a moment.
 */
@ApiStatus.Internal
public final class Recording implements ReplayRecorder {

    /** A tick in milliseconds, which is what turns elapsed time into a frame. */
    private static final long MILLIS_PER_TICK = 50L;

    /**
     * How long one may run before it stops itself.
     *
     * <p>Half an hour of two players is a few megabytes of arrays. A recorder
     * that a plugin forgets to stop would otherwise grow until the server ran
     * out of memory, and the bug that causes it &mdash; an early return on a
     * path that was meant to call {@code stop()} &mdash; is invisible until
     * then.
     */
    private static final int MAX_FRAMES = 30 * 60 * 20;

    /** The six slots that are watched for changes. */
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final String owner;
    private final Location anchor;
    private final TaskScheduler scheduler;
    private final UUID id = UUID.randomUUID();
    private final long createdAt = System.currentTimeMillis();
    private final long startedAt = System.nanoTime();
    private final Map<UUID, Follower> followers = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<ReplayMark> marks = new ConcurrentLinkedQueue<>();

    private volatile boolean running = true;
    private Replay finished;

    Recording(String owner, Location anchor, TaskScheduler scheduler) {
        this.owner = owner;
        this.anchor = anchor.clone();
        this.scheduler = scheduler;
    }

    /** Which plugin's recording this is. */
    String owner() {
        return owner;
    }

    @Override
    public synchronized void follow(@NotNull Player player) {
        if (!running) {
            return;
        }
        Follower existing = followers.get(player.getUniqueId());
        if (existing != null) {
            if (existing.task != null) {
                return;
            }
            // Somebody who logged out and came back. The ticks in between are
            // theirs and empty rather than a body standing where they left it,
            // and everything before them is still theirs.
            existing.track.absentUntil(tick());
            start(player, existing);
            return;
        }
        Follower follower = new Follower(ReplayActor.of(player));
        followers.put(player.getUniqueId(), follower);
        start(player, follower);
    }

    /** Puts one player's sampling timer back on. */
    private void start(Player player, Follower follower) {
        // Bound to the player rather than to the server, which is what makes a
        // sample legal on Folia: where somebody is may only be read from the
        // region that owns them.
        follower.task = scheduler.runAtEntityTimer(player, 1L, 1L,
                () -> sample(player, follower));
    }

    @Override
    public synchronized void forget(@NotNull Player player) {
        Follower follower = followers.get(player.getUniqueId());
        if (follower != null) {
            follower.stop();
        }
    }

    @Override
    public void mark(@NotNull String kind, @Nullable Player actor, byte @Nullable [] data) {
        if (running) {
            marks.add(new ReplayMark(tick(), kind,
                    actor == null ? null : actor.getUniqueId(), data));
        }
    }

    @Override
    public void mark(@NotNull String kind, @Nullable Player actor, @NotNull String text) {
        mark(kind, actor, text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public int tick() {
        return (int) Math.min(MAX_FRAMES,
                (System.nanoTime() - startedAt) / 1_000_000L / MILLIS_PER_TICK);
    }

    @Override
    public boolean isRecording() {
        return running;
    }

    @Override
    public synchronized @NotNull Replay stop() {
        if (finished != null) {
            return finished;
        }
        running = false;
        int frames = 0;
        for (Follower follower : followers.values()) {
            follower.stop();
            frames = Math.max(frames, follower.track.length());
        }
        List<ReplayActor> actors = new ArrayList<>(followers.size());
        List<MotionTrack> tracks = new ArrayList<>(followers.size());
        for (Follower follower : followers.values()) {
            actors.add(follower.actor);
            tracks.add(follower.track.build(frames));
        }
        List<ReplayMark> ordered = new ArrayList<>(marks);
        // Stable by tick: two things on the same tick keep the order they
        // happened in, which is what makes a hit that killed somebody come
        // before the death rather than after it.
        ordered.sort(Comparator.comparingInt(ReplayMark::tick));
        // Anything written after the last sampled frame belongs to the last
        // frame, not past the end of the recording.
        int last = Math.max(0, frames - 1);
        ordered.replaceAll(mark -> mark.tick() <= last ? mark
                : new ReplayMark(last, mark.kind(), mark.actor(), mark.data()));
        finished = new Replay(id, createdAt, frames, actors, tracks, ordered);
        ReplayRuntime.forget(this);
        return finished;
    }

    @Override
    public synchronized void cancel() {
        running = false;
        for (Follower follower : followers.values()) {
            follower.stop();
        }
        followers.clear();
        marks.clear();
        ReplayRuntime.forget(this);
    }

    /** Whether this recording is following somebody, for the shared listener. */
    boolean follows(UUID player) {
        return followers.containsKey(player);
    }

    /**
     * Writes one tick of one player.
     *
     * <p>Whether they are on the ground is the flag the client reported, which
     * Bukkit deprecates for being exactly that. For a recording it is the right
     * one and the only one: it is what the server itself used, so a replay that
     * recomputed it would disagree with the fight it is showing.
     */
    @SuppressWarnings("deprecation")
    private void sample(Player player, Follower follower) {
        if (!running || follower.task == null) {
            return;
        }
        if (!player.isOnline()) {
            // Nothing tells an entity timer that its player logged out: on
            // Folia it is simply never run again, and on Bukkit it would keep
            // sampling a connection that is gone. From here they are recorded
            // as not being there, which is what happened.
            follower.stop();
            return;
        }
        int tick = tick();
        if (tick >= MAX_FRAMES) {
            // Stopped rather than truncated: a recorder nobody ended is a bug,
            // and one that silently kept running while dropping everything
            // would hide it.
            ReplayRuntime.overran(owner);
            stop();
            return;
        }
        Location at = player.getLocation();
        follower.track.put(tick, at.getX() - anchor.getX(), at.getY() - anchor.getY(),
                at.getZ() - anchor.getZ(), at.getYaw(), at.getPitch(), player.getHealth(),
                MotionTrack.flagsOf(poseOf(player), player.isSprinting(),
                        player.isOnGround(), true));
        sampleEquipment(player, follower, tick);
    }

    /** Writes a mark for each slot that is holding something new. */
    private void sampleEquipment(Player player, Follower follower, int tick) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < SLOTS.length; slot++) {
            ItemStack worn = inventory.getItem(SLOTS[slot]);
            if (worn != null && worn.getType().isAir()) {
                worn = null;
            }
            if (Objects.equals(worn, follower.equipment[slot])) {
                continue;
            }
            follower.equipment[slot] = worn == null ? null : worn.clone();
            marks.add(new ReplayMark(tick, ReplayMark.EQUIP, follower.actor.id(),
                    Equipment.write(SLOTS[slot], worn)));
        }
    }

    /** How the client is drawing them, in the vocabulary an NPC understands. */
    private static NpcPose poseOf(Player player) {
        Pose pose = player.getPose();
        return switch (pose) {
            case SLEEPING -> NpcPose.LYING;
            case SWIMMING -> NpcPose.CRAWLING;
            case SNEAKING -> NpcPose.SNEAKING;
            case SPIN_ATTACK -> NpcPose.SPINNING;
            default -> NpcPose.STANDING;
        };
    }

    /** One person being recorded. */
    private static final class Follower {

        private final ReplayActor actor;
        private final MotionTrack.Builder track = new MotionTrack.Builder();
        private final ItemStack[] equipment = new ItemStack[SLOTS.length];
        private volatile TaskHandle task;

        Follower(ReplayActor actor) {
            this.actor = actor;
        }

        void stop() {
            TaskHandle running = task;
            task = null;
            if (running != null) {
                running.cancel();
            }
        }
    }

    /**
     * What an {@link ReplayMark#EQUIP} mark carries.
     *
     * <p>The slot is written by name rather than by its position in the enum:
     * Bukkit has added slots in the middle of that enum before, and a recording
     * kept from before one of those would come back with a sword on a horse's
     * saddle.
     */
    public static final class Equipment {

        private Equipment() {
        }

        /** Packs a slot and what is in it. */
        static byte[] write(EquipmentSlot slot, ItemStack item) {
            byte[] name = slot.name().getBytes(StandardCharsets.UTF_8);
            byte[] stack = item == null ? new byte[0] : item.serializeAsBytes();
            ByteArrayOutputStream out = new ByteArrayOutputStream(name.length + stack.length + 1);
            out.write(name.length);
            out.write(name, 0, name.length);
            out.write(stack, 0, stack.length);
            return out.toByteArray();
        }

        /** Which slot a mark is about, or {@code null} when it cannot be read. */
        public static @Nullable EquipmentSlot slotOf(byte[] data) {
            if (data == null || data.length == 0 || data.length < 1 + data[0]) {
                return null;
            }
            try {
                return EquipmentSlot.valueOf(
                        new String(data, 1, data[0], StandardCharsets.UTF_8));
            } catch (IllegalArgumentException gone) {
                // A slot this server no longer has. The rest of the recording
                // is still worth watching.
                return null;
            }
        }

        /** What goes in it, or {@code null} when the slot was emptied. */
        public static @Nullable ItemStack itemOf(byte[] data) {
            if (data == null || data.length <= 1 + data[0]) {
                return null;
            }
            byte[] stack = new byte[data.length - 1 - data[0]];
            System.arraycopy(data, 1 + data[0], stack, 0, stack.length);
            try {
                return ItemStack.deserializeBytes(stack);
            } catch (RuntimeException gone) {
                return null;
            }
        }
    }
}
