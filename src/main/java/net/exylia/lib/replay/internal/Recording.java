package net.exylia.lib.replay.internal;

import net.exylia.lib.replay.Replay;
import net.exylia.lib.replay.ReplayActor;
import net.exylia.lib.replay.ReplayMark;
import net.exylia.lib.replay.ReplayRecorder;
import net.exylia.lib.replay.ReplayScene;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A recording while it is being made.
 *
 * <h2>One tick, one stamp</h2>
 * Every sample and every mark is stamped with the server tick it happened on,
 * read from {@link ReplayClock}, never with the wall clock. See there for why
 * that matters to how a playback looks.
 *
 * <h2>Who samples what</h2>
 * A player is sampled by a timer bound to that player, which is what makes a
 * read legal on Folia: where somebody is may only be read from the region that
 * owns them. Everything else &mdash; arrows, pearls, crystals &mdash; is sampled
 * together by one timer bound to the anchor, and dropped when it leaves that
 * region.
 */
@ApiStatus.Internal
public final class Recording implements ReplayRecorder {

    /** Half an hour, which no fight lasts. */
    private static final int MAX_FRAMES = 30 * 60 * 20;

    /** Things that are not players, at most. */
    private static final int MAX_ACTORS = 400;

    /** Block changes and explosions, at most. */
    private static final int MAX_WORLD_MARKS = 40_000;

    /** How often a mob's equipment is compared, in ticks. A player's is every tick. */
    private static final int MOB_EQUIPMENT_EVERY = 10;

    /** How often a watched zone is searched for something new, in ticks. */
    private static final int WATCH_EVERY = 5;

    private final String owner;
    private final Location anchor;
    private final TaskScheduler scheduler;
    private final UUID id = UUID.randomUUID();
    private final long createdAt = System.currentTimeMillis();
    private final int startedAt = ReplayClock.now();
    /**
     * Everybody followed, in the order they were followed. Synchronized: it is
     * written under this recording's lock and read from event handlers on any
     * region.
     */
    private final Map<UUID, Follower> followers = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    private final ConcurrentLinkedQueue<ReplayMark> marks = new ConcurrentLinkedQueue<>();
    private final List<Follower> debris = new CopyOnWriteArrayList<>();

    private volatile TaskHandle debrisTask;
    private volatile TaskHandle watchTask;
    private volatile double watchRadius;
    private volatile boolean running = true;
    private volatile int worldMarks;
    private Replay finished;

    Recording(String owner, Location anchor, TaskScheduler scheduler) {
        this.owner = owner;
        this.anchor = anchor.clone();
        this.scheduler = scheduler;
    }

    String owner() {
        return owner;
    }

    public @NotNull Location anchor() {
        return anchor.clone();
    }

    @Override
    public synchronized void follow(@NotNull Player player) {
        Follower existing = follower(player.getUniqueId());
        if (existing != null) {
            existing.forgotten = false;
            // A timer that has not sampled for a while is a dead one: Folia
            // retires an entity's tasks when they log out without telling it.
            if (existing.task != null && !existing.task.isCancelled()
                    && tick() - existing.sampledAt <= 2) {
                return;
            }
            existing.stop();
            // Somebody who logged out and came back. The ticks in between are
            // theirs and empty rather than a body standing where they left it.
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

    private void add(UUID id, ReplayActor actor, boolean player, Entity entity) {
        if (!running || (!player && followers.size() >= MAX_ACTORS)) return;
        Follower follower = new Follower(actor, player);
        followers.put(id, follower);
        if (player) {
            start(entity, follower);
            return;
        }
        follower.entity = entity;
        debris.add(follower);
        startDebris();
    }

    private void start(Entity entity, Follower follower) {
        follower.task = scheduler.runAtEntityTimer(entity, 1L, 1L, () -> sample(entity, follower));
    }

    private void startDebris() {
        if (debrisTask != null) return;
        debrisTask = scheduler.runAtLocationTimer(anchor, 1L, 1L, this::sampleDebris);
    }

    private void sampleDebris() {
        if (!running || debris.isEmpty()) return;
        for (Follower follower : debris) {
            Entity entity = follower.entity;
            if (entity == null || !entity.isValid()) {
                debris.remove(follower);
                follower.entity = null;
                continue;
            }
            try {
                sample(entity, follower);
            } catch (IllegalStateException elsewhere) {
                // Folia: no longer this region's to read. Not worth chasing.
                debris.remove(follower);
                follower.entity = null;
            }
        }
    }

    @Override
    public void watch(double radius) {
        if (!running || radius <= 0) return;
        watchRadius = radius;
        if (watchTask != null) return;
        watchTask = scheduler.runAtLocationTimer(anchor, 1L, WATCH_EVERY, this::scanZone);
    }

    /** Follows whatever has wandered into the watched zone since the last look. */
    private void scanZone() {
        double radius = watchRadius;
        if (!running || radius <= 0 || anchor.getWorld() == null) return;
        try {
            for (Entity entity : anchor.getWorld().getNearbyEntities(anchor, radius, radius, radius)) {
                if (!Tracking.worthRecording(entity)) continue;
                if (followers.containsKey(entity.getUniqueId())) {
                    if (entity instanceof Player player) follow(player);
                    continue;
                }
                follow(entity);
            }
        } catch (IllegalStateException elsewhere) {
            // Folia: part of the box belongs to another region this tick.
        }
    }

    /** Whether a place is inside the zone this recording watches by itself. */
    boolean watches(Location at) {
        double radius = watchRadius;
        if (radius <= 0 || at.getWorld() == null || !at.getWorld().equals(anchor.getWorld())) {
            return false;
        }
        return Math.abs(at.getX() - anchor.getX()) <= radius
                && Math.abs(at.getY() - anchor.getY()) <= radius
                && Math.abs(at.getZ() - anchor.getZ()) <= radius;
    }

    @Override
    public synchronized void forget(@NotNull Player player) {
        Follower follower = followers.get(player.getUniqueId());
        if (follower == null) return;
        follower.forgotten = true;
        follower.stop();
    }

    /**
     * Picks somebody up again after the server dropped the timer bound to them,
     * unless the plugin let them go on purpose.
     */
    synchronized void resume(Player player) {
        Follower follower = followers.get(player.getUniqueId());
        if (follower != null && !follower.forgotten && running) follow(player);
    }

    @Override
    public void mark(@NotNull String kind, @Nullable Player actor, byte @Nullable [] data) {
        if (running) {
            marks.add(new ReplayMark(tick(), kind, actor == null ? null : actor.getUniqueId(), data));
        }
    }

    @Override
    public void mark(@NotNull String kind, @Nullable Player actor, @NotNull String text) {
        mark(kind, actor, text.getBytes(StandardCharsets.UTF_8));
    }

    /** A mark the module writes against any actor, player or not. */
    void markActor(String kind, UUID actor, byte @Nullable [] data) {
        if (running) marks.add(new ReplayMark(tick(), kind, actor, data));
    }

    @Override
    public void block(@NotNull Location at, @Nullable BlockData became, @Nullable BlockData was) {
        if (!running || worldMarks >= MAX_WORLD_MARKS) return;
        worldMarks++;
        marks.add(new ReplayMark(tick(), ReplayMark.BLOCK, null,
                WorldMarks.block(anchor, at, became, was)));
    }

    /** A block change seen by the module's own listener, inside a watched zone. */
    void blockSeen(Location at, BlockData was) {
        if (!running || worldMarks >= MAX_WORLD_MARKS) return;
        int when = tick();
        // What it became is only there once the event is over, so it is read a
        // tick later from the region that owns it, and stamped with the tick
        // the change actually happened on.
        scheduler.runAtLocationLater(at, 1L, () -> {
            if (!running || worldMarks >= MAX_WORLD_MARKS) return;
            worldMarks++;
            marks.add(new ReplayMark(when, ReplayMark.BLOCK, null,
                    WorldMarks.block(anchor, at, at.getBlock().getBlockData(), was)));
        });
    }

    @Override
    public void explosion(@NotNull Location at, float power) {
        if (!running || worldMarks >= MAX_WORLD_MARKS) return;
        worldMarks++;
        marks.add(new ReplayMark(tick(), ReplayMark.EXPLOSION, null,
                WorldMarks.explosion(anchor, at, power)));
    }

    @Override
    public void markAt(@NotNull String kind, @NotNull Location at, @Nullable String text) {
        if (running) {
            marks.add(new ReplayMark(tick(), kind, null, WorldMarks.place(anchor, at, text)));
        }
    }

    @Override
    public void reset() {
        if (!running) return;
        marks.add(new ReplayMark(tick(), ReplayMark.RESET, null, null));
    }

    @Override
    public int tick() {
        return Math.clamp(ReplayClock.now() - startedAt, 0, MAX_FRAMES);
    }

    @Override
    public boolean isRecording() {
        return running;
    }

    @Override
    public synchronized @NotNull Replay stop() {
        if (finished != null) return finished;
        running = false;
        stopTimers();
        List<Follower> everybody;
        synchronized (followers) {
            everybody = new ArrayList<>(followers.values());
        }
        // As long as it ran, not only as long as somebody was followed: a
        // recording kept going for a few seconds after the end of a fight is
        // the fall of the last body, even once everybody has left the arena.
        int frames = tick();
        for (Follower follower : everybody) {
            follower.stop();
            synchronized (follower) {
                frames = Math.max(frames, follower.track.lastTick());
            }
        }
        List<ReplayActor> actors = new ArrayList<>(everybody.size());
        List<MotionTrack> tracks = new ArrayList<>(everybody.size());
        for (Follower follower : everybody) {
            // A sample still running on another region finishes its frame
            // before the track is copied, rather than tearing it.
            synchronized (follower) {
                // Something that appeared and vanished without ever being
                // sampled is not in the recording rather than an empty track.
                if (follower.track.isEmpty()) continue;
                actors.add(follower.actor);
                tracks.add(follower.track.build());
            }
        }
        List<ReplayMark> ordered = new ArrayList<>(marks);
        // Stable by tick: two things on the same tick keep the order they
        // happened in, so a killing hit comes before the death.
        ordered.sort(Comparator.comparingInt(ReplayMark::tick));
        int last = Math.max(0, frames - 1);
        ordered.replaceAll(mark -> mark.tick() <= last ? mark
                : new ReplayMark(last, mark.kind(), mark.actor(), mark.data()));
        finished = new Replay(id, createdAt, frames, actors, tracks, ordered,
                List.of(ReplayScene.of(anchor)), null);
        ReplayRuntime.forget(this);
        return finished;
    }

    @Override
    public synchronized void cancel() {
        running = false;
        stopTimers();
        synchronized (followers) {
            followers.values().forEach(Follower::stop);
            followers.clear();
        }
        marks.clear();
        ReplayRuntime.forget(this);
    }

    private void stopTimers() {
        TaskHandle task = debrisTask;
        debrisTask = null;
        debris.clear();
        if (task != null) task.cancel();
        TaskHandle watching = watchTask;
        watchTask = null;
        if (watching != null) watching.cancel();
    }

    /** Whether this recording follows somebody, player or not. */
    boolean follows(UUID actor) {
        Follower follower = followers.get(actor);
        return follower != null;
    }

    private Follower follower(UUID id) {
        return followers.get(id);
    }

    private void sample(Entity entity, Follower follower) {
        if (!running || (follower.player && follower.task == null)) return;
        int tick = tick();
        if (tick >= MAX_FRAMES) {
            // Stopped rather than truncated: a recorder nobody ended is a bug,
            // and one that silently kept going while dropping everything would
            // hide it.
            ReplayRuntime.overran(owner);
            stop();
            return;
        }
        if (entity instanceof Player player) {
            if (!player.isOnline()) {
                follower.stop();
                return;
            }
            // Dead, on the respawn screen: not here, but coming back. The
            // ticks in between are written as absent once they are back.
            // Or in spectator: flying through walls, invisible to everybody who
            // was fighting. Not part of what happened.
            if (player.isDead() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                follower.away = true;
                follower.sampledAt = tick;
                return;
            }
        } else if (!entity.isValid()) {
            follower.stop();
            return;
        }
        // Somewhere else entirely: sent to the lobby, through a portal. Their
        // position measured against this anchor would be a body flying off
        // across the arena, so they are away until they come back.
        if (anchor.getWorld() != null && entity.getWorld() != anchor.getWorld()) {
            follower.away = true;
            follower.sampledAt = tick;
            return;
        }
        long now = System.nanoTime();
        if (ReplayClock.isWall() && tick == follower.sampledAt
                && now - follower.sampledNanos > Sampler.SAME_TICK_NANOS) {
            // Folia: the same stamp read in two of this region's ticks. It is
            // the next one.
            tick++;
        }
        follower.sampledNanos = now;
        Location at = entity.getLocation();
        synchronized (follower) {
            if (follower.away) {
                follower.track.absentUntil(tick);
                follower.away = false;
            }
            follower.track.put(tick, at.getX() - anchor.getX(), at.getY() - anchor.getY(),
                    at.getZ() - anchor.getZ(), at.getYaw(), at.getPitch(), at.getYaw(),
                    Sampler.healthOf(entity), Sampler.flagsOf(entity));
        }
        follower.sampledAt = tick;
        if (entity instanceof Player player && tick % 20 == 0) {
            marks.add(ReplayMark.of(tick, ReplayMark.PING, player.getUniqueId(),
                    String.valueOf(player.getPing())));
        }
        if (entity instanceof LivingEntity living
                && (follower.player || tick % MOB_EQUIPMENT_EVERY == 0)) {
            sampleEquipment(living, follower, tick);
        }
    }

    /** Writes a mark for every slot that changed since the last look. */
    private void sampleEquipment(LivingEntity living, Follower follower, int tick) {
        for (int slot = 0; slot < Sampler.SLOTS.length; slot++) {
            ItemStack worn = Sampler.worn(living, Sampler.SLOTS[slot]);
            if (Objects.equals(worn, follower.equipment[slot])) continue;
            follower.equipment[slot] = worn == null ? null : worn.clone();
            marks.add(new ReplayMark(tick, ReplayMark.EQUIP, follower.actor.id(),
                    Equipment.write(Sampler.SLOTS[slot], worn)));
        }
    }

    /** One actor and everything recorded of them. */
    private static final class Follower {

        private final ReplayActor actor;
        private final boolean player;
        private final MotionTrack.Builder track = new MotionTrack.Builder();
        private final ItemStack[] equipment = new ItemStack[Sampler.SLOTS.length];

        private volatile Entity entity;
        private volatile TaskHandle task;
        private volatile int sampledAt = Integer.MIN_VALUE / 2;
        private volatile long sampledNanos;
        private volatile boolean away;
        private volatile boolean forgotten;

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
     * How an equipment mark's data is laid out: the slot's name, then the item
     * in Paper's own byte form.
     */
    public static final class Equipment {

        private Equipment() {
        }

        static byte[] write(EquipmentSlot slot, ItemStack item) {
            byte[] name = slot.name().getBytes(StandardCharsets.UTF_8);
            byte[] stack = item == null ? new byte[0] : item.serializeAsBytes();
            ByteArrayOutputStream out = new ByteArrayOutputStream(name.length + stack.length + 1);
            out.write(name.length);
            out.write(name, 0, name.length);
            out.write(stack, 0, stack.length);
            return out.toByteArray();
        }

        public static @Nullable EquipmentSlot slotOf(byte[] data) {
            if (data == null || data.length == 0 || data.length < 1 + data[0]) return null;
            try {
                return EquipmentSlot.valueOf(new String(data, 1, data[0], StandardCharsets.UTF_8));
            } catch (IllegalArgumentException gone) {
                return null;
            }
        }

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
