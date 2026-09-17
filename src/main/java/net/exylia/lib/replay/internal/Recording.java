package net.exylia.lib.replay.internal;

import net.exylia.lib.npc.NpcPose;
import net.exylia.lib.replay.Replay;
import net.exylia.lib.replay.ReplayActor;
import net.exylia.lib.replay.ReplayMark;
import net.exylia.lib.replay.ReplayRecorder;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
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
 * Every followed player and every followed entity is sampled by a task bound to
 * that entity, which is what makes this work on Folia: where something is may
 * only be read from the region that owns it, and one shared timer reading
 * everything would be reading across regions. Each task writes only into its
 * own arrays, so there is nothing to lock either.
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

    /**
     * How many things one recording may follow at once.
     *
     * <p>Players are a handful; arrows, crystals and primed TNT are not. A
     * crystal fight can put one in the arena every tick, and each one followed
     * is a timer and a growing set of arrays. Past this the newest are not
     * recorded, which is a replay missing some of its debris rather than a
     * server running out of memory.
     */
    private static final int MAX_ACTORS = 400;

    /**
     * How many block changes and blasts one recording may hold.
     *
     * <p>The ceiling that matters for a match spent digging. Past it the arena
     * stops being recorded and the fight does not, which is the right half to
     * keep.
     */
    private static final int MAX_WORLD_MARKS = 40_000;

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
    private volatile int worldMarks;
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

    /** What every position in this recording is measured against. */
    public @NotNull Location anchor() {
        return anchor.clone();
    }

    @Override
    public synchronized void follow(@NotNull Player player) {
        Follower existing = follower(player.getUniqueId());
        if (existing != null) {
            if (existing.task != null) return;
            // Somebody who logged out and came back. The ticks in between are
            // theirs and empty rather than a body standing where they left it,
            // and everything before them is still theirs.
            existing.track.absentUntil(tick());
            start(player, existing);
            return;
        }
        add(player.getUniqueId(), ReplayActor.of(player), true, player);
    }

    @Override
    public synchronized void follow(@NotNull Entity entity) {
        if (entity instanceof Player player) {
            follow(player);
            return;
        }
        if (follower(entity.getUniqueId()) != null) return;
        add(entity.getUniqueId(), ReplayActor.of(entity), false, entity);
    }

    /** Starts following one thing, if there is room for it. */
    private void add(UUID id, ReplayActor actor, boolean player, Entity entity) {
        if (!running || followers.size() >= MAX_ACTORS) return;
        Follower follower = new Follower(actor, player);
        followers.put(id, follower);
        start(entity, follower);
    }

    /** Puts one thing's sampling timer on. */
    private void start(Entity entity, Follower follower) {
        // Bound to the entity rather than to the server, which is what makes a
        // sample legal on Folia: where something is may only be read from the
        // region that owns it.
        follower.task = scheduler.runAtEntityTimer(entity, 1L, 1L,
                () -> sample(entity, follower));
    }

    @Override
    public synchronized void forget(@NotNull Player player) {
        Follower follower = followers.get(player.getUniqueId());
        if (follower != null) follower.stop();
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
    public void block(@NotNull Location at, @Nullable BlockData became) {
        if (!running || worldMarks >= MAX_WORLD_MARKS) return;
        worldMarks++;
        marks.add(new ReplayMark(tick(), ReplayMark.BLOCK, null,
                WorldMarks.block(anchor, at, became)));
    }

    @Override
    public void explosion(@NotNull Location at, float power) {
        if (!running || worldMarks >= MAX_WORLD_MARKS) return;
        worldMarks++;
        marks.add(new ReplayMark(tick(), ReplayMark.EXPLOSION, null,
                WorldMarks.explosion(anchor, at, power)));
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
        if (finished != null) return finished;
        running = false;
        int frames = 0;
        for (Follower follower : followers.values()) {
            follower.stop();
            frames = Math.max(frames, follower.track.lastTick());
        }
        List<ReplayActor> actors = new ArrayList<>(followers.size());
        List<MotionTrack> tracks = new ArrayList<>(followers.size());
        for (Follower follower : followers.values()) {
            // Something that appeared and vanished without ever being sampled —
            // an arrow that hit the wall it was fired at — is not in the
            // recording at all rather than in it as an empty track.
            if (follower.track.isEmpty()) continue;
            actors.add(follower.actor);
            tracks.add(follower.track.build());
        }
        List<ReplayMark> ordered = new ArrayList<>(marks);
        // Stable by tick: two things on the same tick keep the order they
        // happened in, which is what makes a hit that killed somebody come
        // before the death rather than after it, and the blast that took a wall
        // out come before the wall going.
        ordered.sort(Comparator.comparingInt(ReplayMark::tick));
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
        followers.values().forEach(Follower::stop);
        followers.clear();
        marks.clear();
        ReplayRuntime.forget(this);
    }

    /** Whether this recording is following somebody, for the shared listener. */
    boolean follows(UUID player) {
        Follower follower = followers.get(player);
        return follower != null && follower.player;
    }

    private Follower follower(UUID id) {
        return followers.get(id);
    }

    /**
     * Writes one tick of one thing.
     *
     * <p>Whether a player is on the ground is the flag the client reported,
     * which Bukkit deprecates for being exactly that. For a recording it is the
     * right one and the only one: it is what the server itself used, so a replay
     * that recomputed it would disagree with the fight it is showing.
     */
    @SuppressWarnings("deprecation")
    private void sample(Entity entity, Follower follower) {
        if (!running || follower.task == null) return;
        int tick = tick();
        if (tick >= MAX_FRAMES) {
            // Stopped rather than truncated: a recorder nobody ended is a bug,
            // and one that silently kept running while dropping everything
            // would hide it.
            ReplayRuntime.overran(owner);
            stop();
            return;
        }
        if (!entity.isValid()) {
            // An arrow that landed, a crystal that went off, a player who
            // logged out. Nothing tells an entity timer that its entity is
            // gone: on Folia it is simply never run again, and on Bukkit it
            // would keep sampling something that no longer exists.
            follower.stop();
            return;
        }
        Location at = entity.getLocation();
        if (!(entity instanceof Player player)) {
            follower.track.put(tick, at.getX() - anchor.getX(), at.getY() - anchor.getY(),
                    at.getZ() - anchor.getZ(), at.getYaw(), at.getPitch(), 0.0,
                    MotionTrack.flagsOf(NpcPose.STANDING, false, false, false, true));
            return;
        }
        follower.track.put(tick, at.getX() - anchor.getX(), at.getY() - anchor.getY(),
                at.getZ() - anchor.getZ(), at.getYaw(), at.getPitch(), player.getHealth(),
                MotionTrack.flagsOf(poseOf(player), player.isSprinting(),
                        player.isOnGround(), isUsing(player), true));
        sampleEquipment(player, follower, tick);
    }

    /** Whether a bow is being drawn, a shield is up or a gapple is going down. */
    private static boolean isUsing(LivingEntity entity) {
        try {
            return entity.isHandRaised();
        } catch (NoSuchMethodError older) {
            // A server whose API predates the accessor. One flag short of a
            // perfect replay is not a reason to record nothing.
            return false;
        }
    }

    /** Writes a mark for each slot that is holding something new. */
    private void sampleEquipment(Player player, Follower follower, int tick) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < SLOTS.length; slot++) {
            ItemStack worn = inventory.getItem(SLOTS[slot]);
            if (worn != null && worn.getType().isAir()) worn = null;
            if (Objects.equals(worn, follower.equipment[slot])) continue;
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

    /** One thing being recorded. */
    private static final class Follower {

        private final ReplayActor actor;
        private final boolean player;
        private final MotionTrack.Builder track = new MotionTrack.Builder();
        private final ItemStack[] equipment = new ItemStack[SLOTS.length];
        private volatile TaskHandle task;

        Follower(ReplayActor actor, boolean player) {
            this.actor = actor;
            this.player = player;
        }

        void stop() {
            TaskHandle running = task;
            task = null;
            if (running != null) running.cancel();
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
            if (data == null || data.length == 0 || data.length < 1 + data[0]) return null;
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
            if (data == null || data.length <= 1 + data[0]) return null;
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
