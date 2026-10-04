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
import org.bukkit.GameMode;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
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
 * <h2>What is kept</h2>
 * A {@link Tape} per entity, the module's marks with where they happened, and a
 * log of every block change on the server with what the block was before. All
 * three are trimmed to the window as time passes.
 */
@ApiStatus.Internal
public final class BlackBox {

    /** How often each player's timer looks around again, in ticks. */
    private static final int LOOK_EVERY = 5;

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

    private final TaskScheduler scheduler;
    private volatile BlackBoxSettings settings;
    private final Map<UUID, Tape> tapes = new ConcurrentHashMap<>();
    private final Map<UUID, Watcher> watchers = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Happening> happenings = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Change> changes = new ConcurrentLinkedDeque<>();
    private final AtomicInteger changeCount = new AtomicInteger();
    private final AtomicInteger nonPlayers = new AtomicInteger();
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
        if (!running || !player.isOnline()) return;
        Watcher current = watchers.get(player.getUniqueId());
        int now = ReplayClock.now();
        TaskHandle running = current == null ? null : current.task;
        if (current != null && current.player == player && (running == null || !running.isCancelled())
                && now - current.sampledAt <= 20) {
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
        for (Watcher watcher : watchers.values()) {
            Location there = watcher.last;
            if (there == null || there.getWorld() != at.getWorld()) continue;
            if (there.distanceSquared(at) <= radius * radius) watcher.noticed.add(entity);
        }
    }

    private void sample(Watcher watcher) {
        Player player = watcher.player;
        if (!running || !player.isOnline()) return;
        BlackBoxSettings current = settings;
        int tick = ReplayClock.now();
        watcher.sampledAt = tick;
        Location here = player.getLocation();
        watcher.last = here;
        boolean hidden = current.hidden().test(player);
        if (!hidden && player.isValid() && player.getGameMode() != GameMode.SPECTATOR) {
            write(player, tick);
            if (watcher.age % 20 == 0) {
                happenings.add(new Happening(tick, ReplayMark.PING, player.getUniqueId(),
                        String.valueOf(player.getPing()).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        null, 0, 0, 0, null, 0f));
                if (regional) regionStats(here);
            }
        }
        double radius = current.radius();
        if (++watcher.age % LOOK_EVERY == 1) look(watcher, here, radius, current);
        double reach = (radius + 8) * (radius + 8);
        for (Entity entity : watcher.nearby) sampleNear(entity, here, reach, tick);
        if (!watcher.noticed.isEmpty()) {
            for (Iterator<Entity> it = watcher.noticed.iterator(); it.hasNext(); ) {
                Entity entity = it.next();
                if (!entity.isValid()) {
                    it.remove();
                    continue;
                }
                sampleNear(entity, here, reach, tick);
            }
        }
    }

    private void sampleNear(Entity entity, Location here, double reach, int tick) {
        try {
            if (!entity.isValid() || entity.getWorld() != here.getWorld()) return;
            // Folia: something that has crossed into another region is that
            // region's to read now, and its getters do not say so by throwing.
            if (!Bukkit.isOwnedByCurrentRegion(entity)) return;
            if (entity.getLocation().distanceSquared(here) > reach) return;
            write(entity, tick);
        } catch (IllegalStateException elsewhere) {
            // Folia: it has crossed into another region since the last look.
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
        List<Entity> players = new ArrayList<>();
        List<Entity> others = new ArrayList<>();
        for (Entity entity : around) {
            if (!Tracking.worthRecording(entity)) continue;
            if (entity instanceof Player other) {
                if (!current.hidden().test(other)) players.add(entity);
            } else {
                others.add(entity);
            }
        }
        if (others.size() > NEARBY_PER_PLAYER) {
            others.sort(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(here)));
            others = others.subList(0, NEARBY_PER_PLAYER);
        }
        players.addAll(others);
        watcher.nearby = players;
        watcher.noticed.removeIf(players::contains);
    }

    /** Writes one entity's frame for this tick, once however many timers see it. */
    private void write(Entity entity, int tick) {
        UUID id = entity.getUniqueId();
        Tape tape = tapes.get(id);
        boolean player = entity instanceof Player;
        if (tape == null) {
            if (!player && nonPlayers.get() >= settings.maxEntities()) return;
            tape = new Tape(id, player ? ReplayActor.of((Player) entity) : ReplayActor.of(entity),
                    player, entity instanceof LivingEntity);
            Tape raced = tapes.putIfAbsent(id, tape);
            if (raced != null) tape = raced;
            else if (!player) nonPlayers.incrementAndGet();
        }
        Location at = entity.getLocation();
        boolean already = tape.put(tick, entity.getWorld().getUID(), at.getX(), at.getY(), at.getZ(),
                at.getYaw(), at.getPitch(), Sampler.flagsOf(entity), (float) Sampler.healthOf(entity));
        if (already || !(entity instanceof LivingEntity living)) return;
        if (!player && tick % MOB_EQUIPMENT_EVERY != 0) return;
        for (int slot = 0; slot < Sampler.SLOTS.length; slot++) {
            tape.wear(tick, slot, Sampler.worn(living, Sampler.SLOTS[slot]));
        }
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
                snapshots(plan).whenComplete((shots, failure) -> scheduler.runAsync(() -> {
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
                minY = Math.min(minY, (int) Math.floor(frame.y()));
                maxY = Math.max(maxY, (int) Math.floor(frame.y()));
                int chunkX = (int) Math.floor(frame.x()) >> 4;
                int chunkZ = (int) Math.floor(frame.z()) >> 4;
                for (int dx = -reach; dx <= reach && budget > 0; dx++) {
                    for (int dz = -reach; dz <= reach && budget > 0; dz++) {
                        if (dx * dx + dz * dz > (reach + 0.5) * (reach + 0.5)) continue;
                        if (draft.chunks.add(key(chunkX + dx, chunkZ + dz))) budget--;
                    }
                }
            }
            int margin = current.verticalMargin();
            int bottom = loaded == null ? -64 : loaded.getMinHeight();
            int top = loaded == null ? 320 : loaded.getMaxHeight() - 1;
            draft.minSection = Math.max(bottom, minY - margin) >> 4;
            draft.maxSection = Math.min(top, maxY + margin) >> 4;
        }
        return new Plan(focus, start, end, drafts);
    }

    /** Reads every chunk the plan covers as it is now, each on its own region. */
    private CompletableFuture<Map<Long, ChunkSnapshot>[]> snapshots(Plan plan) {
        @SuppressWarnings("unchecked")
        Map<Long, ChunkSnapshot>[] shots = new Map[plan.scenes.size()];
        List<CompletableFuture<?>> loading = new ArrayList<>();
        for (int index = 0; index < plan.scenes.size(); index++) {
            Draft draft = plan.scenes.get(index);
            Map<Long, ChunkSnapshot> mine = new ConcurrentHashMap<>();
            shots[index] = mine;
            World world = Bukkit.getWorld(draft.world);
            if (world == null) continue;
            for (long chunk : draft.chunks) {
                int x = (int) (chunk >> 32);
                int z = (int) chunk;
                CompletableFuture<Void> read = world.getChunkAtAsync(x, z, false)
                        .thenAccept((Chunk loaded) -> {
                            if (loaded != null) mine.put(chunk, loaded.getChunkSnapshot(false, false, false));
                        })
                        .exceptionally(failure -> null);
                loading.add(read);
            }
        }
        return CompletableFuture.allOf(loading.toArray(CompletableFuture[]::new)).thenApply(done -> shots);
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
            MotionTrack track = trackOf(tape, scenes, start, end);
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
                    || !draft.chunks.contains(key((int) Math.floor(happening.x) >> 4,
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
    private static @Nullable MotionTrack trackOf(Tape tape, List<Draft> scenes, int start, int end) {
        List<Tape.Frame> frames = tape.from(start);
        if (frames.isEmpty()) return null;
        MotionTrack.Builder builder = new MotionTrack.Builder();
        boolean any = false;
        int cursor = -1;
        int lastPut = Integer.MIN_VALUE;
        for (int tick = start; tick <= end; tick++) {
            while (cursor + 1 < frames.size() && frames.get(cursor + 1).tick() <= tick) cursor++;
            if (cursor < 0) continue;
            Tape.Frame frame = frames.get(cursor);
            // A tape with no frame for longer than its heartbeat was not being
            // sampled: gone, dead, or out of everybody's reach.
            if (tick - frame.tick() > Tape.HEARTBEAT + 2) continue;
            if ((frame.flags() & MotionTrack.PRESENT) == 0) continue;
            Draft scene = scenes.get(sceneOf(scenes, tick));
            if (!scene.world.equals(frame.world())) continue;
            if (!scene.chunks.contains(key((int) Math.floor(frame.x()) >> 4, (int) Math.floor(frame.z()) >> 4))) {
                continue;
            }
            int relative = tick - start;
            if (any && lastPut != tick - 1) builder.absentUntil(relative);
            builder.put(relative, frame.x() - scene.x, frame.y() - scene.y, frame.z() - scene.z,
                    frame.yaw(), frame.pitch(), frame.yaw(), frame.health(), frame.flags());
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
                ChunkSnapshot snapshot = entry.getValue();
                for (int section = draft.minSection; section <= draft.maxSection; section++) {
                    column.put(section, read(snapshot, section, names));
                }
                sections.put(entry.getKey(), column);
            }
            for (int at = log.size() - 1; at >= 0; at--) {
                Change change = log.get(at);
                if (change.tick < draft.from || !change.world.equals(draft.world)) continue;
                Map<Integer, TerrainSection.Builder> column = sections.get(key(change.x >> 4, change.z >> 4));
                if (column == null) continue;
                TerrainSection.Builder section = column.get(change.y >> 4);
                if (section == null) continue;
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

    private static double distance(Tape.Frame a, Tape.Frame b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------------ state

    /** One player's timer and what it last found around them. */
    private static final class Watcher {

        final Player player;
        final Set<Entity> noticed = ConcurrentHashMap.newKeySet();
        volatile List<Entity> nearby = List.of();
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
    private record Change(int tick, UUID world, int x, int y, int z, BlockData was) {
    }

    /** One scene being worked out. */
    private static final class Draft {

        final int from;
        int to;
        final UUID world;
        final double x;
        final double y;
        final double z;
        final List<Tape.Frame> path = new ArrayList<>();
        final Set<Long> chunks = new LinkedHashSet<>();
        int minSection;
        int maxSection;

        Draft(int from, UUID world, double x, double y, double z) {
            this.from = from;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** The scenes of one capture, and the window it covers. */
    private record Plan(@Nullable UUID focus, int start, int end, List<Draft> scenes) {
    }
}
