package net.exylia.lib.replay.internal;

import net.exylia.lib.replay.BlackBoxSettings;
import net.exylia.lib.replay.Replay;
import net.exylia.lib.replay.ReplayActor;
import net.exylia.lib.replay.ReplayMark;
import net.exylia.lib.replay.ReplayScene;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The server-wide rolling recorder behind {@link net.exylia.lib.replay.ReplayBlackBox}.
 *
 * <h2>Who samples what</h2>
 * Every online player has a timer bound to them, which is the only place on
 * Folia where they and the things around them may be read. Each tick it samples
 * the player and whatever it last found within the radius; every few ticks it
 * looks around again. Something sampled by two players' timers in one tick is
 * written once.
 *
 * <p>A projectile, a dropped item or a primed block is handed to the timers of
 * the players around it the moment it appears, rather than waiting for the next
 * look around: an arrow covers fifteen blocks before then.
 *
 * <h2>Reading each thing once</h2>
 * A crowd is a hundred players with the same two thousand mobs around them,
 * and anything read by one timer is skipped by every other one until it is
 * due again: every tick for a player, every
 * {@link BlackBoxSettings#entitySampleEvery()} ticks for anything else, the
 * ticks in between filled in when a capture is cut. Past
 * {@link BlackBoxSettings#maxEntities()} a look leaves out whatever has no tape,
 * so what the box cannot keep is never read at all.
 *
 * <h2>What is kept</h2>
 * A {@link Tape} per entity, the module's marks with where they happened, and a
 * log of every block change on the server with what the block was before. All
 * three are trimmed to the window as time passes.
 */
@ApiStatus.Internal
public final class BlackBox {

    /**
     * How often each player's timer looks around again, in ticks. Spawns are
     * picked up at once through notice() and a teleport looks at once, so the
     * look only finds what walked in: a second is time for a mob to cover a
     * few blocks at the edge of the radius, and the lookup is an entity query
     * per player.
     */
    private static final int LOOK_EVERY = 20;

    /** Moving this far since the last look looks again at once: a teleport or a pearl. */
    private static final double LOOK_AFTER_MOVING = 8.0;

    /** The nearest this many non-players around one player, at most. */
    private static final int NEARBY_PER_PLAYER = 96;

    /** Block changes kept, at most, across the server. */
    private static final int MAX_CHANGES = 400_000;

    /** How often a mob's equipment is compared; a player's is every tick. */
    private static final int MOB_EQUIPMENT_EVERY = 10;

    /**
     * Slack kept past the window, in ticks: a capture that waits a few seconds
     * after its event still finds the start of its window.
     */
    private static final int SLACK = 300;

    /**
     * Chunk snapshots a capture asks for per tick. Each one is taken on the
     * chunk's own thread; four hundred at once is a tick of tens of
     * milliseconds, while this many is a fraction of one.
     */
    static final int SNAPSHOTS_PER_TICK = 24;

    /** How long one chunk read may take before the capture goes on without it. */
    private static final long READ_TIMEOUT_SECONDS = 60;

    private final TaskScheduler scheduler;
    private volatile BlackBoxSettings settings;
    final Map<UUID, Tape> tapes = new ConcurrentHashMap<>();
    private final Map<UUID, Watcher> watchers = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Happening> happenings = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Change> changes = new ConcurrentLinkedDeque<>();
    private final AtomicInteger changeCount = new AtomicInteger();
    private final AtomicInteger nonPlayers = new AtomicInteger();

    /** Things a look left out because the box was full, this second and the last. */
    private final AtomicInteger capped = new AtomicInteger();
    private volatile int cappedLastSecond;
    /** Time spent in the players' timers, this second and the last, summed over every thread. */
    private final java.util.concurrent.atomic.LongAdder sampling = new java.util.concurrent.atomic.LongAdder();
    private volatile long samplingLastSecond;

    /**
     * Whether the server tells when equipment changes. With it a slot is read
     * when it changed and once a second besides; without it, every few ticks.
     */
    static volatile boolean equipmentEvents;
    private volatile TaskHandle trimmer;

    /** Whether the server's figures are per region (Folia) rather than global. */
    private volatile boolean regional;
    private volatile java.lang.reflect.Method regionTps;
    private volatile boolean running;

    BlackBox(TaskScheduler scheduler, BlackBoxSettings settings) {
        this.scheduler = scheduler;
        this.settings = settings;
    }

    BlackBoxSettings settings() {
        return settings;
    }

    void settings(BlackBoxSettings settings) {
        this.settings = settings;
    }

    boolean isRunning() {
        return running;
    }

    void start() {
        if (running) return;
        running = true;
        for (Player player : Bukkit.getOnlinePlayers()) watch(player);
        trimmer = scheduler.runAsyncTimer(20L, 20L, this::trim);
    }

    void stop() {
        running = false;
        TaskHandle trim = trimmer;
        trimmer = null;
        if (trim != null) trim.cancel();
        watchers.values().forEach(watcher -> watcher.task.cancel());
        watchers.clear();
        tapes.clear();
        happenings.clear();
        changes.clear();
        changeCount.set(0);
        nonPlayers.set(0);
        capped.set(0);
        cappedLastSecond = 0;
        sampling.reset();
        samplingLastSecond = 0;
    }

    // --------------------------------------------------------------- sampling

    /** Starts the timer bound to one player. */
    void watch(Player player) {
        if (!running) return;
        Watcher watcher = new Watcher(player);
        watcher.sampledAt = ReplayClock.now();
        if (watchers.putIfAbsent(player.getUniqueId(), watcher) != null) return;
        watcher.task = scheduler.runAtEntityTimer(player, 1L, 1L, () -> sample(watcher));
    }

    /**
     * Makes sure somebody is being sampled by a live timer.
     *
     * <p>Folia retires an entity's tasks when the entity is replaced: a death
     * and respawn, a change of world. Nothing tells the timer, it simply never
     * runs again, and everything after that moment would be missing from every
     * recording. A timer that has not sampled for a second is replaced.
     */
    void ensure(Player player) {
        ensure(player, 20);
    }

    /**
     * Same, with a timer counted as dead once it has not sampled for this many
     * ticks. Right after a respawn the old timer sampled moments ago, while the
     * player lay dead, and may already be retired: a short wait catches it
     * there instead of a second of nobody on the tape.
     */
    void ensure(Player player, int staleAfter) {
        if (!running || !player.isOnline()) return;
        Watcher current = watchers.get(player.getUniqueId());
        int now = ReplayClock.now();
        TaskHandle running = current == null ? null : current.task;
        if (current != null && current.player == player && (running == null || !running.isCancelled())
                && now - current.sampledAt <= staleAfter) {
            return;
        }
        Watcher fresh = new Watcher(player);
        fresh.sampledAt = now;
        boolean replaced = current == null
                ? watchers.putIfAbsent(player.getUniqueId(), fresh) == null
                : watchers.replace(player.getUniqueId(), current, fresh);
        if (!replaced) return;
        if (running != null) running.cancel();
        fresh.task = scheduler.runAtEntityTimer(player, 1L, 1L, () -> sample(fresh));
    }

    /** Stops it, when they leave. What was recorded of them stays until it ages out. */
    void unwatch(UUID player) {
        Watcher watcher = watchers.remove(player);
        if (watcher != null) watcher.task.cancel();
    }

    /** Something left the world: its tape says so from this tick. */
    void gone(UUID entity) {
        Tape tape = tapes.get(entity);
        if (tape != null) tape.gone(ReplayClock.now());
    }

    /** Something new near somebody: sampled from the next tick on rather than the next look. */
    void notice(Entity entity) {
        if (!running || !Tracking.worthRecording(entity)) return;
        if (entity instanceof Player player && settings.hidden().test(player)) return;
        double radius = settings.radius();
        Location at = entity.getLocation();
        Tape tape = null;
        for (Watcher watcher : watchers.values()) {
            Location there = watcher.last;
            if (there == null || there.getWorld() != at.getWorld()) continue;
            if (there.distanceSquared(at) > radius * radius) continue;
            // Its first frame now: a crystal is often placed and blown up
            // before the next sample, and was never there at all.
            if (tape == null) {
                tape = write(entity, tapes.get(entity.getUniqueId()), at, ReplayClock.now());
                if (tape == null) {
                    // The box is full: nobody will read it.
                    capped.incrementAndGet();
                    return;
                }
            }
            // A volley in a crowd waits for the next look rather than growing
            // every timer's list without end.
            if (watcher.noticed.size() < NEARBY_PER_PLAYER) watcher.noticed.put(entity, tape);
        }
    }

    private void sample(Watcher watcher) {
        long began = System.nanoTime();
        try {
            sampleTimed(watcher);
        } finally {
            sampling.add(System.nanoTime() - began);
        }
    }

    private void sampleTimed(Watcher watcher) {
        Player player = watcher.player;
        if (!running || !player.isOnline()) return;
        BlackBoxSettings current = settings;
        int tick = ReplayClock.now();
        watcher.sampledAt = tick;
        Location here = player.getLocation();
        watcher.last = here;
        boolean hidden = current.hidden().test(player);
        if (!hidden && player.isValid() && !ReplayEvents.spectating(player)) {
            write(player, tick);
            if (watcher.age % 20 == 0) {
                happenings.add(new Happening(tick, ReplayMark.PING, player.getUniqueId(),
                        String.valueOf(player.getPing()).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        null, 0, 0, 0, null, 0f));
                if (regional) regionStats(here);
            }
        }
        double radius = current.radius();
        Location lookedAt = watcher.lookedAt;
        if (++watcher.age % LOOK_EVERY == 1 || lookedAt == null || lookedAt.getWorld() != here.getWorld()
                || lookedAt.distanceSquared(here) > LOOK_AFTER_MOVING * LOOK_AFTER_MOVING) {
            look(watcher, here, radius, current);
        }
        double reach = (radius + 8) * (radius + 8);
        int every = current.entitySampleEvery();
        List<Entity> nearby = watcher.nearby;
        Tape[] known = watcher.nearbyTapes;
        Location scratch = watcher.scratch;
        for (int i = 0; i < nearby.size(); i++) {
            Tape tape = sampleNear(nearby.get(i), known[i], here, reach, tick, every, scratch);
            if (tape != null) known[i] = tape;
        }
        if (!watcher.noticed.isEmpty()) {
            for (Iterator<Map.Entry<Entity, Tape>> it = watcher.noticed.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Entity, Tape> noticed = it.next();
                Entity entity = noticed.getKey();
                if (!due(noticed.getValue(), entity, tick, every)) continue;
                if (!entity.isValid()) {
                    it.remove();
                    continue;
                }
                sampleNear(entity, noticed.getValue(), here, reach, tick, every, scratch);
            }
        }
    }

    /**
     * Whether something with a tape is to be read on this tick: a player
     * always, anything else once its last read is {@code every} ticks old,
     * whichever timer read it. A stamp from ahead of this one is read anyway,
     * the way it always was: on Folia it is another region's clock.
     */
    private static boolean due(Tape tape, Entity entity, int tick, int every) {
        int since = tick - tape.seen;
        return since < 0 || since >= every || entity instanceof Player;
    }

    /**
     * Samples one entity near somebody.
     *
     * @param known   its tape when the caller remembers it from an earlier tick,
     *                which saves looking it up; a look forgets it again, so a
     *                tape trimmed away is never written to for long
     * @param scratch filled with where it is: up to ninety-six entities per
     *                player per tick, each a new {@code Location} otherwise
     * @return its tape, when it has one
     */
    @Nullable Tape sampleNear(Entity entity, @Nullable Tape known, Location here, double reach, int tick,
                              int every, Location scratch) {
        // Already read by somebody else's timer since it was last due:
        // everything below would be read only for write() to throw it away.
        // The id is final, so asking for it is safe whichever region the
        // entity is in now. Players are not skipped, the same as in write().
        Tape tape = known;
        if (tape == null) {
            // Nothing without a tape gets one while the box is full: not even
            // looking it up, which with two thousand mobs around a crowd was
            // the most expensive line of the box.
            if (!(entity instanceof Player) && nonPlayers.get() >= settings.maxEntities()) return null;
            tape = tapes.get(entity.getUniqueId());
        }
        if (tape != null && !due(tape, entity, tick, every)) return tape;
        try {
            if (!entity.isValid() || entity.getWorld() != here.getWorld()) return tape;
            // Folia: something that has crossed into another region is that
            // region's to read now, and its getters do not say so by throwing.
            if (!Bukkit.isOwnedByCurrentRegion(entity)) return tape;
            Location at = entity.getLocation(scratch);
            if (at.distanceSquared(here) > reach) return tape;
            return write(entity, tape, at, tick);
        } catch (IllegalStateException elsewhere) {
            // Folia: it has crossed into another region since the last look.
            return tape;
        }
    }

    /** Finds what is around one player now. */
    private void look(Watcher watcher, Location here, double radius, BlackBoxSettings current) {
        Collection<Entity> around;
        try {
            around = watcher.player.getNearbyEntities(radius, radius, radius);
        } catch (IllegalStateException elsewhere) {
            return;
        }
        // Players are left to their own timers: every one of them is sampled
        // by itself already, and reading each of them again for everybody
        // around is three hundred players read ninety thousand times a tick.
        // Tapes are looked up here, once a second, rather than every tick.
        boolean full = nonPlayers.get() >= current.maxEntities();
        List<Entity> others = new ArrayList<>();
        List<Tape> found = new ArrayList<>();
        int left = 0;
        for (Entity entity : around) {
            if (entity instanceof Player || !Tracking.worthRecording(entity)) continue;
            Tape tape = tapes.get(entity.getUniqueId());
            if (tape == null && full) {
                left++;
                continue;
            }
            others.add(entity);
            found.add(tape);
        }
        if (left > 0) capped.addAndGet(left);
        if (others.size() > NEARBY_PER_PLAYER) {
            // The nearest ones, each distance worked out once: sorting by a
            // distance read inside the comparison was two new locations a
            // comparison, gigabytes a minute in a crowd. A non-negative float
            // sorts the same as its bits, so distance and index share a long.
            Location scratch = watcher.scratch;
            long[] order = new long[others.size()];
            for (int index = 0; index < order.length; index++) {
                float distance = (float) others.get(index).getLocation(scratch).distanceSquared(here);
                order[index] = ((long) Float.floatToRawIntBits(distance) << 32) | index;
            }
            java.util.Arrays.sort(order);
            List<Entity> nearest = new ArrayList<>(NEARBY_PER_PLAYER);
            List<Tape> theirs = new ArrayList<>(NEARBY_PER_PLAYER);
            for (int rank = 0; rank < NEARBY_PER_PLAYER; rank++) {
                int index = (int) order[rank];
                nearest.add(others.get(index));
                theirs.add(found.get(index));
            }
            others = nearest;
            found = theirs;
        }
        watcher.nearbyTapes = found.toArray(new Tape[0]);
        watcher.nearby = others;
        watcher.lookedAt = here;
        // Whatever was noticed since the last look is in what it found, or
        // too far down the list to be kept.
        watcher.noticed.clear();
    }

    /** Writes one entity's frame for this tick, once however many timers see it. */
    private void write(Entity entity, int tick) {
        write(entity, tapes.get(entity.getUniqueId()), entity.getLocation(), tick);
    }

    /** Same, with its tape already looked up, or {@code null} for none yet, and where it is now. */
    private @Nullable Tape write(Entity entity, @Nullable Tape tape, Location at, int tick) {
        UUID id = entity.getUniqueId();
        boolean player = entity instanceof Player;
        // Seen by somebody else's timer this tick already: an arrow between
        // twenty players is read once, not twenty times. Players have one
        // timer each, and on Folia their own stamp can repeat a tick later.
        if (!player && tape != null && tape.seen == tick) return tape;
        if (tape == null) {
            if (!player && nonPlayers.get() >= settings.maxEntities()) return null;
            tape = new Tape(id, player ? ReplayActor.of((Player) entity) : ReplayActor.of(entity),
                    player, entity instanceof LivingEntity);
            Tape raced = tapes.putIfAbsent(id, tape);
            if (raced != null) tape = raced;
            else if (!player) nonPlayers.incrementAndGet();
        }
        tape.seen = tick;
        boolean already = tape.put(tick, entity.getWorld().getUID(), at.getX(), at.getY(), at.getZ(),
                at.getYaw(), at.getPitch(), Sampler.flagsOf(entity), (float) Sampler.healthOf(entity));
        if (already || !(entity instanceof LivingEntity living)) return tape;
        wear(living, tape, player, tick);
        return tape;
    }

    /**
     * Reads what somebody wears, when it may have changed.
     *
     * <p>With the server's equipment event, a slot is read on the tick it
     * changed and everything once a second, which catches whatever changed
     * without one. Without it, a player's hands every other tick and their
     * armour every five, a mob's everything every half second. Each read is
     * a copy of the item, and a hundred players' hands every other tick were
     * most of what the box did for a player.
     */
    private static void wear(LivingEntity living, Tape tape, boolean player, int tick) {
        long since = (long) tick - tape.wornAt;
        boolean all = since < 0 || since >= (equipmentEvents ? Sampler.FULL_LOOK_EVERY : MOB_EQUIPMENT_EVERY);
        if (equipmentEvents) {
            int dirty = tape.dirty;
            if (dirty == 0 && !all) return;
            if (dirty != 0) tape.dirty = 0;
            if (all) tape.wornAt = tick;
            for (int slot = 0; slot < Sampler.SLOTS.length; slot++) {
                if (all || (dirty & (1 << slot)) != 0) {
                    tape.wear(tick, slot, Sampler.worn(living, Sampler.SLOTS[slot]), true);
                }
            }
            return;
        }
        if (!player) {
            if (!all) return;
            tape.wornAt = tick;
        }
        for (int slot = 0; slot < Sampler.SLOTS.length; slot++) {
            if (player && !Sampler.due(slot, tick)) continue;
            tape.wear(tick, slot, Sampler.worn(living, Sampler.SLOTS[slot]));
        }
    }

    /** The server says somebody's equipment changed: those slots are read on their next sample. */
    void equipped(UUID entity, int slots) {
        Tape tape = tapes.get(entity);
        if (tape != null) tape.dirty |= slots;
    }

    // ------------------------------------------------------------------ marks

    /** Whether the box has anything of this actor, which is when a mark about them matters. */
    boolean knows(UUID actor) {
        return running && tapes.containsKey(actor);
    }

    void mark(String kind, UUID actor, byte @Nullable [] data) {
        if (!running) return;
        happenings.add(new Happening(ReplayClock.now(), kind, actor, data, null, 0, 0, 0, null, 0f));
    }

    void markAt(String kind, @Nullable UUID actor, Location at, @Nullable String text, float power) {
        if (!running || at.getWorld() == null) return;
        happenings.add(new Happening(ReplayClock.now(), kind, actor, null, at.getWorld().getUID(),
                at.getX(), at.getY(), at.getZ(), text, power));
    }

    /** A block is about to change, or just has: what it was before. */
    void changed(World world, int x, int y, int z, BlockData was) {
        if (!running) return;
        if (changeCount.incrementAndGet() > MAX_CHANGES) {
            changes.pollFirst();
            changeCount.decrementAndGet();
        }
        changes.add(new Change(ReplayClock.now(), world.getUID(), x, y, z, was));
    }

    /** Drops everything older than the window. */
    private void trim() {
        cappedLastSecond = capped.getAndSet(0);
        samplingLastSecond = sampling.sumThenReset();
        server();
        for (Player player : Bukkit.getOnlinePlayers()) ensure(player);
        int oldest = ReplayClock.now() - settings.seconds() * 20 - SLACK;
        for (Iterator<Tape> it = tapes.values().iterator(); it.hasNext(); ) {
            Tape tape = it.next();
            tape.prune(oldest);
            if (tape.lastTick() < oldest) {
                it.remove();
                if (!tape.player) nonPlayers.decrementAndGet();
            }
        }
        while (true) {
            Happening first = happenings.peekFirst();
            if (first == null || first.tick >= oldest) break;
            happenings.pollFirst();
        }
        while (true) {
            Change first = changes.peekFirst();
            if (first == null || first.tick >= oldest) break;
            if (changes.pollFirst() != null) changeCount.decrementAndGet();
        }
    }

    /**
     * How the server is doing, once a second, so a replay can tell a death to
     * lag from a death to a player.
     *
     * <p>On Folia there is no server-wide figure: the global one is all the
     * API gives, and it is written when it can be read.
     */
    private void server() {
        if (regional) return;
        try {
            double tps = Math.min(20.0, Bukkit.getTPS()[0]);
            double mspt = Bukkit.getAverageTickTime();
            stats(String.format(java.util.Locale.ROOT, "%.2f;%.2f", tps, mspt));
        } catch (RuntimeException | NoSuchMethodError unsupported) {
            // Folia has no server-wide figure: each player's timer writes the
            // one of the region they are in instead.
            regional = true;
        }
    }

    /**
     * Folia: the TPS of the region a player is in, read on that region.
     * Through reflection, because the method is Folia's own API.
     */
    private void regionStats(Location at) {
        try {
            if (regionTps == null) {
                regionTps = Bukkit.getServer().getClass().getMethod("getRegionTPS", Location.class);
            }
            double[] tps = (double[]) regionTps.invoke(Bukkit.getServer(), at);
            if (tps != null && tps.length > 0) {
                stats(String.format(java.util.Locale.ROOT, "%.2f;?", Math.min(20.0, tps[0])));
            }
        } catch (ReflectiveOperationException | RuntimeException unsupported) {
            regional = false;
        }
    }

    private void stats(String text) {
        happenings.add(new Happening(ReplayClock.now(), ReplayMark.SERVER, null,
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8), null, 0, 0, 0, null, 0f));
    }

    /** What it holds and costs; walks every tape and every mark, so not for every tick. */
    net.exylia.lib.replay.BlackBoxStats stats() {
        if (!running) return net.exylia.lib.replay.BlackBoxStats.idle();
        int players = 0;
        int entities = 0;
        long frames = 0;
        long tapeBytes = 0;
        for (Tape tape : tapes.values()) {
            if (tape.player) players++;
            else entities++;
            frames += tape.frames();
            tapeBytes += tape.bytes();
        }
        int marks = 0;
        long markBytes = 0;
        for (Happening happening : happenings) {
            marks++;
            // The record and its node in the queue, and what it carries.
            markBytes += 104 + (happening.data == null ? 0 : 16 + happening.data.length)
                    + (happening.text == null ? 0 : 40 + happening.text.length());
        }
        int changed = changeCount.get();
        // The record and its node; block data is shared between changes.
        return new net.exylia.lib.replay.BlackBoxStats(true, players, entities, frames, tapeBytes,
                marks, markBytes, changed, changed * 64L, cappedLastSecond, samplingLastSecond);
    }

    // --------------------------------------------------------------- captures

    /**
     * Cuts a recording out of the box.
     *
     * @param focus  who it is about, or {@code null} for a place
     * @param center the place, when there is no focus
     */
    CompletableFuture<Replay> capture(@Nullable UUID focus, @Nullable Location center, int seconds) {
        return capture(focus, center, seconds, 0);
    }

    /**
     * The same, carrying on for a while after now.
     *
     * <p>For a death: the fall, the red and the puff are the second after it,
     * and a replay cut on the killing blow ends before anybody can see what
     * happened. The person it is about is followed only up to now, so a
     * respawn on the other side of the map in those seconds is not a scene.
     */
    CompletableFuture<Replay> capture(@Nullable UUID focus, @Nullable Location center, int seconds,
                                      int afterSeconds) {
        if (afterSeconds <= 0) return cut(focus, center, seconds, ReplayClock.now(), 0);
        int event = ReplayClock.now();
        CompletableFuture<Replay> later = new CompletableFuture<>();
        scheduler.runAsyncLater(afterSeconds * 20L, () -> cut(focus, center, seconds, event, afterSeconds)
                .whenComplete((replay, failure) -> {
                    if (failure != null) later.completeExceptionally(failure);
                    else later.complete(replay);
                }));
        return later;
    }

    private CompletableFuture<Replay> cut(@Nullable UUID focus, @Nullable Location center, int seconds,
                                          int event, int afterSeconds) {
        CompletableFuture<Replay> result = new CompletableFuture<>();
        BlackBoxSettings current = settings;
        int window = Math.clamp(seconds, 1, current.seconds()) * 20;
        int end = Math.max(event, ReplayClock.now());
        int start = event - window;
        UUID centerWorld = center == null || center.getWorld() == null ? null : center.getWorld().getUID();
        double cx = center == null ? 0 : center.getX();
        double cy = center == null ? 0 : center.getY();
        double cz = center == null ? 0 : center.getZ();
        scheduler.runAsync(() -> {
            try {
                Plan plan = plan(focus, centerWorld, cx, cy, cz, start, end, event, current);
                snapshots(plan, current).whenComplete((shots, failure) -> scheduler.runAsync(() -> {
                    try {
                        if (failure != null) throw failure;
                        result.complete(assemble(plan, shots, current));
                    } catch (Throwable broken) {
                        result.completeExceptionally(broken);
                    }
                }));
            } catch (Throwable broken) {
                result.completeExceptionally(broken);
            }
        });
        return result;
    }

    /** Where the recording happens: its scenes, and the chunks each one covers. */
    private Plan plan(@Nullable UUID focus, @Nullable UUID world, double x, double y, double z,
                      int start, int end, int event, BlackBoxSettings current) {
        List<Tape.Frame> path = new ArrayList<>();
        if (focus != null) {
            Tape tape = tapes.get(focus);
            if (tape != null) {
                for (Tape.Frame frame : tape.from(start)) {
                    if (frame.tick() <= event) path.add(frame);
                }
            }
            // A tape whose newest frame is from before the window is somebody
            // who was not being recorded then: in vanish, in spectator, gone.
            // Their last frame is not a minute of them.
            if (!path.isEmpty() && path.getLast().tick() < start - Tape.HEARTBEAT) path.clear();
            if (path.isEmpty()) {
                throw new IllegalStateException("The black box has nothing of " + focus
                        + " in the last " + (end - start) / 20 + " seconds");
            }
        } else {
            if (world == null) throw new IllegalArgumentException("A place needs a world");
            path.add(new Tape.Frame(start, world, x, y, z, 0f, 0f, 0, 0f));
        }

        double radius = current.radius();
        // Further than two corridors can bridge is somewhere else: a cut.
        double jump = radius * 2 + 16;
        List<Draft> drafts = new ArrayList<>();
        Draft scene = null;
        Tape.Frame previous = null;
        for (Tape.Frame frame : path) {
            int tick = Math.max(frame.tick(), start);
            boolean elsewhere = previous != null && (!frame.world().equals(previous.world())
                    || distance(previous, frame) > jump);
            if (scene == null || elsewhere) {
                scene = new Draft(tick, frame.world(), Math.floor(frame.x()), Math.floor(frame.y()),
                        Math.floor(frame.z()));
                drafts.add(scene);
            }
            scene.path.add(frame);
            previous = frame;
        }
        for (int index = 0; index < drafts.size(); index++) {
            drafts.get(index).to = index + 1 < drafts.size() ? drafts.get(index + 1).from : end + 1;
        }

        // Chunks along the way, newest first, until the budget is spent: in a
        // long chase the end of it is the part anybody is asking about.
        int budget = current.maxChunks();
        int reach = (int) Math.ceil(radius / 16.0);
        for (int index = drafts.size() - 1; index >= 0 && budget > 0; index--) {
            Draft draft = drafts.get(index);
            World loaded = Bukkit.getWorld(draft.world);
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            for (int step = draft.path.size() - 1; step >= 0; step--) {
                Tape.Frame frame = draft.path.get(step);
                int frameY = (int) Math.floor(frame.y());
                minY = Math.min(minY, frameY);
                maxY = Math.max(maxY, frameY);
                int chunkX = (int) Math.floor(frame.x()) >> 4;
                int chunkZ = (int) Math.floor(frame.z()) >> 4;
                for (int dx = -reach; dx <= reach; dx++) {
                    for (int dz = -reach; dz <= reach; dz++) {
                        if (dx * dx + dz * dz > (reach + 0.5) * (reach + 0.5)) continue;
                        long chunk = key(chunkX + dx, chunkZ + dz);
                        int[] band = draft.chunks.get(chunk);
                        if (band != null) {
                            widen(band, frameY);
                        } else if (budget > 0) {
                            draft.chunks.put(chunk, new int[] {frameY, frameY});
                            budget--;
                        }
                    }
                }
            }
            int margin = current.verticalMargin();
            draft.margin = margin;
            int bottom = loaded == null ? -64 : loaded.getMinHeight();
            int top = loaded == null ? 320 : loaded.getMaxHeight() - 1;
            draft.minSection = Math.max(bottom, minY - margin) >> 4;
            draft.maxSection = Math.min(top, maxY + margin) >> 4;
            draft.bottom = bottom >> 4;
            draft.top = top >> 4;
        }
        return new Plan(focus, start, end, drafts);
    }

    /**
     * Reads every chunk the plan needs as it is now, each on its own region,
     * {@link #SNAPSHOTS_PER_TICK} a tick.
     *
     * <p>Without terrain only the chunks a block changed in are read: the
     * ground is then wanted for nothing but what each change turned a block
     * into.
     */
    private CompletableFuture<Map<Long, ChunkSnapshot>[]> snapshots(Plan plan, BlackBoxSettings current) {
        @SuppressWarnings("unchecked")
        Map<Long, ChunkSnapshot>[] shots = new Map[plan.scenes.size()];
        List<Runnable> reads = new ArrayList<>();
        List<CompletableFuture<Void>> loading = new ArrayList<>();
        for (int index = 0; index < plan.scenes.size(); index++) {
            Draft draft = plan.scenes.get(index);
            Map<Long, ChunkSnapshot> mine = new ConcurrentHashMap<>();
            shots[index] = mine;
            World world = Bukkit.getWorld(draft.world);
            if (world == null) continue;
            Set<Long> changed = current.terrain() ? null
                    : changedChunks(changes, draft.world, draft.from, draft.minSection, draft.maxSection);
            for (long chunk : draft.chunks.keySet()) {
                if (changed != null && !changed.contains(chunk)) continue;
                int x = (int) (chunk >> 32);
                int z = (int) chunk;
                // A read nobody answers, a chunk stuck loading or a batch whose
                // task never ran, is a chunk without ground, not a capture that
                // never finishes.
                CompletableFuture<Void> read = new CompletableFuture<Void>()
                        .completeOnTimeout(null, READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                loading.add(read);
                reads.add(() -> {
                    try {
                        world.getChunkAtAsync(x, z, false)
                                .thenAccept((Chunk loaded) -> {
                                    if (loaded != null) mine.put(chunk, loaded.getChunkSnapshot(false, false, false));
                                })
                                .whenComplete((done, failure) -> read.complete(null));
                    } catch (RuntimeException unloaded) {
                        // The world went away in the meantime: that chunk has no ground.
                        read.complete(null);
                    }
                });
            }
        }
        inBatches(reads, loading, scheduler);
        return CompletableFuture.allOf(loading.toArray(CompletableFuture[]::new)).thenApply(done -> shots);
    }

    /**
     * Runs the reads {@link #SNAPSHOTS_PER_TICK} a tick: asking is free, and
     * the snapshot is taken on the chunk's thread when it answers, so spacing
     * out the asking spaces out the work there. A batch the scheduler refuses,
     * because the plugin is going away, completes its reads empty.
     */
    static void inBatches(List<Runnable> reads, List<CompletableFuture<Void>> loading, TaskScheduler scheduler) {
        for (int from = 0; from < reads.size(); from += SNAPSHOTS_PER_TICK) {
            int to = Math.min(reads.size(), from + SNAPSHOTS_PER_TICK);
            List<Runnable> batch = reads.subList(from, to);
            long delay = from / SNAPSHOTS_PER_TICK;
            if (delay == 0) {
                batch.forEach(Runnable::run);
                continue;
            }
            try {
                scheduler.runAsyncLater(delay, () -> batch.forEach(Runnable::run));
            } catch (RuntimeException refused) {
                loading.subList(from, to).forEach(read -> read.complete(null));
            }
        }
    }

    /** The chunks a block changed in, in one world since a tick and between two sections. */
    static Set<Long> changedChunks(Iterable<Change> log, UUID world, int from, int minSection, int maxSection) {
        Set<Long> chunks = new HashSet<>();
        for (Change change : log) {
            if (change.tick < from || !change.world.equals(world)) continue;
            int section = change.y >> 4;
            if (section < minSection || section > maxSection) continue;
            chunks.add(key(change.x >> 4, change.z >> 4));
        }
        return chunks;
    }

    /** Puts the recording together from the plan, the tapes and the ground. */
    private Replay assemble(Plan plan, Map<Long, ChunkSnapshot>[] shots, BlackBoxSettings current) {
        int start = plan.start;
        int end = plan.end;
        List<Draft> scenes = plan.scenes;
        List<ReplayScene> sceneList = new ArrayList<>(scenes.size());
        Location[] anchors = new Location[scenes.size()];
        for (int index = 0; index < scenes.size(); index++) {
            Draft draft = scenes.get(index);
            World world = Bukkit.getWorld(draft.world);
            sceneList.add(new ReplayScene(draft.from - start, world == null ? null : world.getName(),
                    draft.x, draft.y, draft.z));
            anchors[index] = new Location(world, draft.x, draft.y, draft.z);
        }

        // Actors: the focus first, then whoever was inside a scene's corridor.
        List<Tape> ordered = new ArrayList<>(tapes.values());
        if (plan.focus != null) {
            ordered.sort(Comparator.comparing(tape -> !tape.id.equals(plan.focus)));
        }
        List<ReplayActor> actors = new ArrayList<>();
        List<MotionTrack> tracks = new ArrayList<>();
        Set<UUID> included = new HashSet<>();
        Map<UUID, Tape> byId = new HashMap<>();
        for (Tape tape : ordered) {
            MotionTrack track = trackOf(tape, scenes, start, end, tape.player ? 1 : current.entitySampleEvery());
            if (track == null) continue;
            actors.add(tape.actor);
            tracks.add(track);
            included.add(tape.id);
            byId.put(tape.id, tape);
        }

        List<ReplayMark> marks = new ArrayList<>();
        for (UUID id : included) {
            Tape tape = byId.get(id);
            if (!tape.living) continue;
            ItemStack[] atStart = new ItemStack[Sampler.SLOTS.length];
            for (Tape.Worn worn : tape.worn()) {
                if (worn.tick() <= start) {
                    atStart[worn.slot()] = worn.item();
                } else if (worn.tick() <= end) {
                    marks.add(new ReplayMark(worn.tick() - start, ReplayMark.EQUIP, id,
                            Recording.Equipment.write(Sampler.SLOTS[worn.slot()], worn.item())));
                }
            }
            for (int slot = 0; slot < atStart.length; slot++) {
                if (atStart[slot] != null) {
                    marks.add(new ReplayMark(0, ReplayMark.EQUIP, id,
                            Recording.Equipment.write(Sampler.SLOTS[slot], atStart[slot])));
                }
            }
        }
        for (Happening happening : happenings) {
            if (happening.tick < start || happening.tick > end) continue;
            int tick = happening.tick - start;
            if (happening.world == null) {
                if (happening.actor == null || included.contains(happening.actor)) {
                    marks.add(new ReplayMark(tick, happening.kind, happening.actor, happening.data));
                }
                continue;
            }
            int scene = sceneOf(scenes, happening.tick);
            Draft draft = scenes.get(scene);
            if (!draft.world.equals(happening.world)
                    || !draft.chunks.containsKey(key((int) Math.floor(happening.x) >> 4,
                    (int) Math.floor(happening.z) >> 4))) {
                continue;
            }
            Location at = new Location(anchors[scene].getWorld(), happening.x, happening.y, happening.z);
            byte[] data = ReplayMark.EXPLOSION.equals(happening.kind)
                    ? WorldMarks.explosion(anchors[scene], at, happening.power)
                    : WorldMarks.place(anchors[scene], at, happening.text);
            UUID actor = happening.actor != null && included.contains(happening.actor) ? happening.actor : null;
            marks.add(new ReplayMark(tick, happening.kind, actor, data));
        }

        List<TerrainSection> terrain = current.terrain() ? new ArrayList<>() : null;
        ground(plan, shots, anchors, marks, terrain);

        marks.sort(Comparator.comparingInt(ReplayMark::tick));
        return new Replay(UUID.randomUUID(), System.currentTimeMillis(), end - start + 1,
                actors, tracks, marks, sceneList, terrain);
    }

    /**
     * One entity's track over the window, present only on the ticks it was
     * inside the corridor of the scene those ticks belong to.
     */
    static @Nullable MotionTrack trackOf(Tape tape, List<Draft> scenes, int start, int end) {
        return trackOf(tape, scenes, start, end, 1);
    }

    /**
     * Same, for something read only every {@code every} ticks: the last
     * {@code every} ticks before each frame move it there in a straight line
     * instead of holding it still and jumping, which is what it would have
     * done had it been read on each of them. A frame further on than that was
     * a read that found it standing still, so it stands until then.
     */
    static @Nullable MotionTrack trackOf(Tape tape, List<Draft> scenes, int start, int end, int every) {
        // Every tape in the box is asked, and most are not in this window at
        // all: a mob that despawned twenty seconds ago would be copied whole
        // and walked tick by tick to find that out.
        if (tape.lastTick() < start - Tape.HEARTBEAT - 2) return null;
        List<Tape.Frame> frames = tape.from(start);
        if (frames.isEmpty()) return null;
        MotionTrack.Builder builder = new MotionTrack.Builder();
        boolean any = false;
        int cursor = -1;
        int lastPut = Integer.MIN_VALUE;
        // A frame holds for every tick until the next one, so where it stands
        // is looked up once per frame and scene, not once per tick.
        int bandCursor = -1;
        Draft bandScene = null;
        int[] band = null;
        for (int tick = start; tick <= end; tick++) {
            while (cursor + 1 < frames.size() && frames.get(cursor + 1).tick() <= tick) cursor++;
            if (cursor < 0) continue;
            Tape.Frame frame = frames.get(cursor);
            // A tape with no frame for longer than its heartbeat was not being
            // sampled: gone, dead, or out of everybody's reach.
            if (tick - frame.tick() > Tape.HEARTBEAT + 2) continue;
            if ((frame.flags() & MotionTrack.PRESENT) == 0) continue;
            Draft scene = scenes.get(sceneOf(scenes, tick));
            if (cursor != bandCursor || scene != bandScene) {
                bandCursor = cursor;
                bandScene = scene;
                band = scene.world.equals(frame.world())
                        ? scene.chunks.get(key((int) Math.floor(frame.x()) >> 4, (int) Math.floor(frame.z()) >> 4))
                        : null;
                // The ground under everybody in it, not only under the focus.
                if (band != null) widen(band, (int) Math.floor(frame.y()));
            }
            if (band == null) continue;
            int relative = tick - start;
            if (any && lastPut != tick - 1) builder.absentUntil(relative);
            double px = frame.x();
            double py = frame.y();
            double pz = frame.z();
            float yaw = frame.yaw();
            float pitch = frame.pitch();
            Tape.Frame next = every > 1 && cursor + 1 < frames.size() ? frames.get(cursor + 1) : null;
            if (next != null) {
                int from = next.tick() - Math.min(every, next.tick() - frame.tick());
                if (tick > from && (next.flags() & MotionTrack.PRESENT) != 0 && next.world().equals(frame.world())
                        && distance(frame, next) <= MAX_FILL * (next.tick() - from)) {
                    double fraction = (tick - from) / (double) (next.tick() - from);
                    px += (next.x() - px) * fraction;
                    py += (next.y() - py) * fraction;
                    pz += (next.z() - pz) * fraction;
                    yaw = lerpAngle(yaw, next.yaw(), fraction);
                    pitch = lerpAngle(pitch, next.pitch(), fraction);
                }
            }
            builder.put(relative, px - scene.x, py - scene.y, pz - scene.z,
                    yaw, pitch, yaw, frame.health(), frame.flags());
            lastPut = tick;
            any = true;
        }
        return any ? builder.build() : null;
    }

    /**
     * The ground each scene was played on, as it was when that scene started,
     * and every block change inside it with what the block became.
     *
     * <p>A snapshot is the ground now. Walking the change log back from the
     * newest entry, each change's block is set back to what it was before it,
     * which leaves the ground as it was when the scene began; and what a block
     * held just before being set back is exactly what that change turned it
     * into, which is the half of a change the log never had to store.
     */
    private void ground(Plan plan, Map<Long, ChunkSnapshot>[] shots, Location[] anchors,
                        List<ReplayMark> marks, @Nullable List<TerrainSection> terrain) {
        List<Change> log = new ArrayList<>(changes);
        Map<BlockData, String> names = new HashMap<>();
        Map<String, BlockData> parsed = new HashMap<>();
        for (int index = 0; index < plan.scenes.size(); index++) {
            Draft draft = plan.scenes.get(index);
            Map<Long, ChunkSnapshot> shot = shots[index];
            Map<Long, Map<Integer, TerrainSection.Builder>> sections = new HashMap<>();
            for (Map.Entry<Long, ChunkSnapshot> entry : shot.entrySet()) {
                Map<Integer, TerrainSection.Builder> column = new HashMap<>();
                // Without terrain a section is read only once a change needs it.
                // Only the height people moved at above that chunk: a fight on
                // a floor keeps that floor, not the caves under it.
                int[] band = draft.chunks.get(entry.getKey());
                if (terrain != null && band != null) {
                    int from = Math.max(draft.bottom, (band[0] - draft.margin) >> 4);
                    int to = Math.min(draft.top, (band[1] + draft.margin) >> 4);
                    for (int section = from; section <= to; section++) {
                        column.put(section, read(entry.getValue(), section, names));
                    }
                }
                sections.put(entry.getKey(), column);
            }
            for (int at = log.size() - 1; at >= 0; at--) {
                Change change = log.get(at);
                if (change.tick < draft.from || !change.world.equals(draft.world)) continue;
                long chunk = key(change.x >> 4, change.z >> 4);
                Map<Integer, TerrainSection.Builder> column = sections.get(chunk);
                if (column == null) continue;
                int y = change.y >> 4;
                TerrainSection.Builder section = column.get(y);
                if (section == null) {
                    // A change outside the band brings its section in with it.
                    if (!draft.keeps(chunk, y)) continue;
                    section = read(shot.get(chunk), y, names);
                    column.put(y, section);
                }
                int lx = change.x & 15;
                int ly = change.y & 15;
                int lz = change.z & 15;
                String became = section.get(lx, ly, lz);
                String was = names.computeIfAbsent(change.was, BlockData::getAsString);
                section.set(lx, ly, lz, was);
                if (change.tick < draft.to && change.tick <= plan.end && !became.equals(was)) {
                    Location where = new Location(anchors[index].getWorld(), change.x, change.y, change.z);
                    marks.add(new ReplayMark(change.tick - plan.start, ReplayMark.BLOCK, null,
                            WorldMarks.block(anchors[index], where,
                                    parsed.computeIfAbsent(became, Bukkit::createBlockData), change.was)));
                }
            }
            if (terrain == null) continue;
            int anchorChunkX = (int) Math.floor(draft.x) >> 4;
            int anchorChunkZ = (int) Math.floor(draft.z) >> 4;
            for (Map.Entry<Long, Map<Integer, TerrainSection.Builder>> column : sections.entrySet()) {
                int chunkX = (int) (column.getKey() >> 32);
                int chunkZ = (int) (long) column.getKey();
                for (Map.Entry<Integer, TerrainSection.Builder> section : column.getValue().entrySet()) {
                    TerrainSection built = section.getValue().build(index, chunkX - anchorChunkX,
                            chunkZ - anchorChunkZ, section.getKey());
                    // The stage is a void world: air is already there.
                    if (!built.isEmpty()) terrain.add(built);
                }
            }
        }
    }

    /** One section of a snapshot, as a palette and indices. */
    private static TerrainSection.Builder read(ChunkSnapshot snapshot, int section,
                                               Map<BlockData, String> names) {
        TerrainSection.Builder builder = new TerrainSection.Builder("minecraft:air");
        try {
            if (snapshot.isSectionEmpty(section - (minSectionOf(snapshot)))) return builder;
        } catch (RuntimeException outside) {
            return builder;
        }
        int base = section << 4;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockData data = snapshot.getBlockData(x, base + y, z);
                    // Cave air and void air are air: the stage is a void world.
                    if (data.getMaterial().isAir()) continue;
                    builder.set(x, y, z, names.computeIfAbsent(data, BlockData::getAsString));
                }
            }
        }
        return builder;
    }

    /**
     * What {@link ChunkSnapshot#isSectionEmpty} counts from. Bukkit numbers the
     * sections of a snapshot from the bottom of the world, not from Y zero.
     */
    private static int minSectionOf(ChunkSnapshot snapshot) {
        World world = Bukkit.getWorld(snapshot.getWorldName());
        return world == null ? 0 : world.getMinHeight() >> 4;
    }

    private static int sceneOf(List<Draft> scenes, int tick) {
        int found = 0;
        for (int index = 1; index < scenes.size(); index++) {
            if (scenes.get(index).from <= tick) found = index;
            else break;
        }
        return found;
    }

    /**
     * Blocks a tick, at most, that a filled-in tick moves something: further
     * than an arrow flies is a teleport, which jumps. The same as a playback
     * is willing to smooth between two frames.
     */
    private static final double MAX_FILL = 4.0;

    private static float lerpAngle(float from, float to, double fraction) {
        float delta = ((to - from) % 360f + 540f) % 360f - 180f;
        return (float) (from + delta * fraction);
    }

    private static double distance(Tape.Frame a, Tape.Frame b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void widen(int[] band, int y) {
        if (y < band[0]) band[0] = y;
        if (y > band[1]) band[1] = y;
    }

    static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------------ state

    /** One player's timer and what it last found around them. */
    private static final class Watcher {

        final Player player;
        /** Spawned near them since the last look, with their tapes; emptied by every look. */
        final Map<Entity, Tape> noticed = new ConcurrentHashMap<>();
        volatile List<Entity> nearby = List.of();
        /** The tapes of {@link #nearby}, by index, filled as they are found; forgotten on every look. */
        Tape[] nearbyTapes = new Tape[0];
        Location lookedAt;
        /** Where the entity being sampled is; only this watcher's timer touches it. */
        final Location scratch = new Location(null, 0, 0, 0);
        volatile Location last;
        volatile int sampledAt;
        volatile TaskHandle task;
        int age;

        Watcher(Player player) {
            this.player = player;
        }
    }

    /** Something the module marks, kept with where it happened when that matters. */
    private record Happening(int tick, String kind, @Nullable UUID actor, byte @Nullable [] data,
                             @Nullable UUID world, double x, double y, double z,
                             @Nullable String text, float power) {
    }

    /** One block change: where, when, and what the block was before it. */
    record Change(int tick, UUID world, int x, int y, int z, BlockData was) {
    }

    /** One scene being worked out. */
    static final class Draft {

        final int from;
        int to;
        final UUID world;
        final double x;
        final double y;
        final double z;
        final List<Tape.Frame> path = new ArrayList<>();
        /** The chunks it covers, each with the lowest and highest Y anybody stood at in it. */
        final Map<Long, int[]> chunks = new LinkedHashMap<>();
        int minSection;
        int maxSection;
        int margin;
        int bottom;
        int top;

        Draft(int from, UUID world, double x, double y, double z) {
            this.from = from;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        /** Whether a section of a chunk is within the height kept there. */
        boolean keeps(long chunk, int section) {
            if (section >= minSection && section <= maxSection) return true;
            int[] band = chunks.get(chunk);
            return band != null && section >= (band[0] - margin) >> 4 && section <= (band[1] + margin) >> 4
                    && section >= bottom && section <= top;
        }
    }

    /** The scenes of one capture, and the window it covers. */
    private record Plan(@Nullable UUID focus, int start, int end, List<Draft> scenes) {
    }
}
