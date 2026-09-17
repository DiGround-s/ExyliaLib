package net.exylia.lib.replay.internal;

import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import net.exylia.lib.npc.NpcHandle;
import net.exylia.lib.npc.NpcModel;
import net.exylia.lib.npc.NpcPose;
import net.exylia.lib.npc.internal.NpcRuntime;
import net.exylia.lib.replay.Replay;
import net.exylia.lib.replay.ReplayActor;
import net.exylia.lib.replay.ReplayMark;
import net.exylia.lib.replay.ReplayPlayback;
import net.exylia.lib.task.TaskScheduler;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One recording while somebody is watching it.
 *
 * <h2>It is the client that makes it look real</h2>
 * A body is put where it was with a relative step, and the client draws its own
 * frames between one step and the next exactly as it does for a real player.
 * Nothing about a playback is smoothed or animated here; what makes it
 * indistinguishable from spectating is that it is the same packets a real
 * player's movement produces.
 *
 * <h2>The arena is never actually touched</h2>
 * Blocks that changed during the match are shown to the viewer as fake blocks,
 * which is a packet and not a placement. So a crater from a match an hour ago
 * appears in an arena that is pristine underneath it, and two people can watch
 * two different fights in two clones of it without either seeing the other's
 * rubble.
 *
 * <h2>Only what changed</h2>
 * A frame is applied when the tick it belongs to changes, not every time the
 * driver runs: at a quarter speed that is one set of packets every four ticks
 * rather than four copies of the same one.
 */
@ApiStatus.Internal
public final class Playback implements ReplayPlayback {

    /** Slow enough to read a hit, fast enough to skip to the end. */
    private static final double MIN_SPEED = 1.0 / 16.0;
    private static final double MAX_SPEED = 8.0;

    /** No entity drawn yet. */
    private static final int NONE = 0;

    /** No seek waiting; a real tick is never this. */
    private static final int NO_SEEK = Integer.MIN_VALUE;

    private final String owner;
    private final Replay replay;
    private final Location anchor;
    private final List<Player> viewers;
    private final TaskScheduler scheduler;

    /** Players, drawn by the NPC module. */
    private final NpcHandle[] bodies;
    private final NpcPose[] poses;

    /** Whether each body is currently drawn with its arm up. */
    private final boolean[] using;

    /** Everything that is not a player, drawn as a bare packet entity. */
    private final int[] entities;
    private final EntityType[] types;

    /**
     * Every equipment mark, already turned back into an item.
     *
     * <p>Decoded here rather than when it is passed, because the driver runs on
     * a packet thread and reading an {@link ItemStack} out of bytes is not
     * something to do off the server's own. It is a few dozen items for a whole
     * duel, done once, on the thread that asked for the playback.
     */
    private final Dressed[] dressed;

    /**
     * Every position this recording ever changes, and what was there before the
     * first change to it.
     *
     * <p>Both halves are needed to go backwards. Seeking to a tick before a wall
     * was blown up has to put that wall back, and the only place the wall still
     * exists is the {@code before} side of the mark that took it away.
     */
    private final Map<Location, BlockData> originals;

    /** What has actually been drawn, so only that is taken away again. */
    private final Set<Location> drawn = ConcurrentHashMap.newKeySet();

    /**
     * Whether the arena is really changed rather than drawn over.
     *
     * <p>Off by default, because a library that writes to somebody's world owes
     * them a cleanup it cannot guarantee. On, when the caller owns the arena and
     * says so &mdash; and then it is the better answer by a distance: a client
     * predicts its own movement against its own copy of the world, so a viewer
     * walking into a block the server does not have is corrected back out of it,
     * tick after tick. That is the rubber-banding a packet-only replay has, and
     * the one thing that cannot be fixed without agreeing with the server.
     */
    private volatile boolean solid;

    private volatile double position;
    private volatile double speed = 1.0;
    private volatile int rendered = -1;
    private volatile boolean paused;
    private volatile boolean looping;
    private volatile boolean audible = true;
    private volatile boolean stopped;

    /**
     * A seek somebody asked for, waiting for the driver to carry it out.
     *
     * <p>Seeking used to draw the new frame on whatever thread asked for it — a
     * hotbar click, a command — while the driver was drawing the old one on its
     * own. Two threads inside the same bodies, each computing a relative step
     * from a position the other was in the middle of changing: the steps came
     * out wrong and the bodies ended up somewhere neither side agreed on. Every
     * frame is drawn by the driver now, and a seek is a number left for it.
     */
    private volatile int pendingSeek = NO_SEEK;
    private volatile Consumer<ReplayMark> onMark;
    private volatile Runnable onEnd;

    Playback(String owner, Replay replay, Location anchor, List<Player> viewers,
             TaskScheduler scheduler) {
        this.owner = owner;
        this.replay = replay;
        this.anchor = anchor.clone();
        this.viewers = viewers;
        this.scheduler = scheduler;
        int actors = replay.actors().size();
        this.bodies = new NpcHandle[actors];
        this.poses = new NpcPose[actors];
        this.using = new boolean[actors];
        this.entities = new int[actors];
        this.types = new EntityType[actors];
        for (int index = 0; index < actors; index++) {
            ReplayActor actor = replay.actors().get(index);
            if (!actor.isPlayer()) types[index] = ReplayEntities.typeOf(actor.entityType());
        }
        this.dressed = dress(replay);
        this.originals = originals(replay, this.anchor);
    }

    /** What was at each changed position before the recording touched it. */
    private static Map<Location, BlockData> originals(Replay replay, Location anchor) {
        Map<Location, BlockData> first = new LinkedHashMap<>();
        for (ReplayMark mark : replay.marks()) {
            if (!ReplayMark.BLOCK.equals(mark.kind())) continue;
            Location at = WorldMarks.blockAt(anchor, mark.data());
            // The first word on a position is the one that remembers what the
            // arena looked like; every later one is already the replay's doing.
            if (at != null) first.putIfAbsent(at, WorldMarks.blockBefore(mark.data()));
        }
        return first;
    }

    /** Whether the arena is really changed rather than drawn over for one viewer. */
    void solid(boolean solid) {
        this.solid = solid;
    }

    /** Reads every equipment mark back into a slot and an item, once. */
    private static Dressed[] dress(Replay replay) {
        List<ReplayMark> marks = replay.marks();
        Dressed[] items = new Dressed[marks.size()];
        for (int index = 0; index < marks.size(); index++) {
            ReplayMark mark = marks.get(index);
            if (!ReplayMark.EQUIP.equals(mark.kind())) continue;
            EquipmentSlot slot = Recording.Equipment.slotOf(mark.data());
            if (slot != null) {
                items[index] = new Dressed(slot, Recording.Equipment.itemOf(mark.data()));
            }
        }
        return items;
    }

    /** One slot and what went in it, read out of a mark in advance. */
    private record Dressed(EquipmentSlot slot, ItemStack item) {
    }

    /** Which plugin's playback this is. */
    String owner() {
        return owner;
    }

    /** Whether this playback is being shown to anybody who is still here. */
    boolean hasViewers() {
        for (Player viewer : viewers) {
            if (viewer != null && viewer.isOnline()) return true;
        }
        return false;
    }

    /**
     * Advances it by one tick of real time.
     *
     * <p>Called by the runtime's single driver, on a packet-sending thread.
     */
    void step() {
        if (stopped) return;
        int wanted = pendingSeek;
        if (wanted != NO_SEEK) {
            pendingSeek = NO_SEEK;
            jumpTo(wanted);
            return;
        }
        if (!paused) {
            int target = (int) Math.min(position, replay.frames() - 1);
            if (target != rendered) {
                marks(rendered + 1, target);
                render(target);
                rendered = target;
            }
            position += speed;
        }
        if (position < replay.frames()) return;
        if (looping) {
            jumpTo(0);
            return;
        }
        // Held on the last frame rather than taken away: the end of a duel is
        // the one moment somebody watching wants to sit on, and a playback that
        // deleted both bodies the instant it finished took it away from them.
        position = replay.frames() - 1;
        paused = true;
        Runnable end = onEnd;
        onEnd = null;
        if (end != null) scheduler.run(end);
    }

    @Override
    public void pause() {
        paused = true;
    }

    @Override
    public void resume() {
        if (!stopped) paused = false;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public void speed(double multiplier) {
        speed = Math.clamp(multiplier, MIN_SPEED, MAX_SPEED);
    }

    @Override
    public double speed() {
        return speed;
    }

    @Override
    public void seek(int tick) {
        if (stopped) return;
        // Left for the driver rather than done here. See pendingSeek.
        pendingSeek = Math.clamp(tick, 0, Math.max(0, replay.frames() - 1));
    }

    /**
     * Carries out a seek. Only ever on the driver's thread.
     *
     * <p>The one place a playback cannot carry on from what it last drew,
     * because what it last drew may be an hour later in the fight — and going
     * backwards, a wall that was blown up is a wall that has to come back.
     */
    private void jumpTo(int target) {
        position = target;
        rendered = target;
        rebuildWorld(target);
        for (int actor = 0; actor < bodies.length; actor++) {
            restore(actor, target);
        }
        render(target);
    }

    @Override
    public int tick() {
        return Math.max(0, rendered);
    }

    @Override
    public int frames() {
        return replay.frames();
    }

    @Override
    public void loop(boolean loop) {
        this.looping = loop;
    }

    @Override
    public void sounds(boolean audible) {
        this.audible = audible;
    }

    @Override
    public boolean sounds() {
        return audible;
    }

    @Override
    public @Nullable Location locationOf(@NotNull UUID actor) {
        int index = indexOf(actor);
        if (index < 0 || stopped) return null;
        MotionTrack track = replay.tracks().get(index);
        int at = tick();
        return track.present(at) ? placed(track, at) : null;
    }

    @Override
    public void onMark(@NotNull Consumer<ReplayMark> listener) {
        this.onMark = listener;
    }

    @Override
    public void onEnd(@NotNull Runnable listener) {
        this.onEnd = listener;
    }

    @Override
    public void stop() {
        if (stopped) return;
        stopped = true;
        for (int index = 0; index < bodies.length; index++) {
            remove(index);
        }
        clearWorld();
        ReplayRuntime.forget(this);
    }

    @Override
    public boolean isPlaying() {
        return !stopped;
    }

    /** Puts every actor where it was on this tick. */
    private void render(int tick) {
        for (int index = 0; index < bodies.length; index++) {
            MotionTrack track = replay.tracks().get(index);
            if (!track.present(tick)) {
                remove(index);
                continue;
            }
            if (replay.actors().get(index).isPlayer()) {
                renderPlayer(index, track, tick);
            } else {
                renderEntity(index, track, tick);
            }
        }
    }

    /** A person: a packet body that walks, poses and holds things. */
    private void renderPlayer(int index, MotionTrack track, int tick) {
        NpcHandle body = bodies[index];
        if (body == null) {
            body = spawn(index, track, tick);
            if (body == null) return;
        }
        body.moveTo(placed(track, tick), track.onGround(tick));
        NpcPose pose = track.pose(tick);
        if (poses[index] != pose) {
            poses[index] = pose;
            body.pose(pose);
        }
        // Only on the tick it changes: a bow held drawn for two seconds is one
        // packet, not forty.
        boolean raised = track.using(tick);
        if (using[index] != raised) {
            using[index] = raised;
            body.using(raised);
        }
    }

    /** Anything else: a type at a place, teleported rather than stepped. */
    private void renderEntity(int index, MotionTrack track, int tick) {
        EntityType type = types[index];
        if (type == null || !ReplayEntities.isKnown(type)) return;
        Location at = placed(track, tick);
        if (entities[index] == NONE) {
            entities[index] = ReplayEntities.newEntityId();
            ReplayEntities.spawn(viewers, entities[index], type, at);
            if (audible) {
                String kind = replay.actors().get(index).entityType();
                scheduler.run(() -> Ambience.appeared(viewers, kind, at));
            }
            return;
        }
        ReplayEntities.teleport(viewers, entities[index], at);
    }

    /** Draws one body for the first time, dressed as it was on this tick. */
    private NpcHandle spawn(int index, MotionTrack track, int tick) {
        ReplayActor actor = replay.actors().get(index);
        NpcModel model = actor.texture() == null
                ? NpcModel.of(actor.name())
                : NpcModel.of(actor.name(), actor.texture(), actor.signature());
        NpcHandle body = NpcRuntime.showOwned(owner, model.pose(track.pose(tick)),
                placed(track, tick), viewers);
        bodies[index] = body;
        poses[index] = track.pose(tick);
        using[index] = false;
        if (body != null) restore(index, tick);
        return body;
    }

    /** Takes one actor away, if it is there. */
    private void remove(int index) {
        NpcHandle body = bodies[index];
        bodies[index] = null;
        poses[index] = null;
        using[index] = false;
        if (body != null) body.remove();
        if (entities[index] != NONE) {
            ReplayEntities.destroy(viewers, entities[index]);
            entities[index] = NONE;
            if (audible) {
                ReplayActor actor = replay.actors().get(index);
                MotionTrack track = replay.tracks().get(index);
                int last = Math.max(track.firstTick(), track.lastTick() - 1);
                if (track.present(last)) {
                    Location at = placed(track, last);
                    scheduler.run(() -> Ambience.gone(viewers, actor.entityType(), at));
                }
            }
        }
    }

    /** Where a frame sits in the world this playback is being shown in. */
    private Location placed(MotionTrack track, int tick) {
        Location at = anchor.clone();
        at.add(track.x(tick), track.y(tick), track.z(tick));
        at.setYaw(track.yaw(tick));
        at.setPitch(track.pitch(tick));
        return at;
    }

    /**
     * Walks the marks between two ticks, drawing the ones this module owns and
     * handing the rest over.
     */
    private void marks(int from, int to) {
        if (from > to) return;
        Consumer<ReplayMark> listener = onMark;
        List<ReplayMark> passed = null;
        Map<Location, BlockData> changed = null;
        List<Location> heard = null;
        List<BlockData> became = null;
        List<BlockData> were = null;
        List<ReplayMark> all = replay.marks();
        for (int index = 0; index < all.size(); index++) {
            ReplayMark mark = all.get(index);
            if (mark.tick() < from) continue;
            if (mark.tick() > to) break;
            if (ReplayMark.BLOCK.equals(mark.kind())) {
                // Collected rather than sent one at a time: a blast is dozens of
                // blocks on the same tick, and each one on its own is a packet
                // per block per viewer where one map is a packet per section.
                Location at = WorldMarks.blockAt(anchor, mark.data());
                if (at != null) {
                    if (changed == null) {
                        changed = new LinkedHashMap<>();
                        heard = new ArrayList<>();
                        became = new ArrayList<>();
                        were = new ArrayList<>();
                    }
                    BlockData now = WorldMarks.blockData(mark.data());
                    changed.put(at, now);
                    heard.add(at);
                    became.add(now);
                    were.add(WorldMarks.blockBefore(mark.data()));
                }
                continue;
            }
            if (draw(index, mark)) continue;
            if (listener != null) {
                if (passed == null) passed = new ArrayList<>(2);
                passed.add(mark);
            }
        }
        if (changed != null) {
            showBlocks(changed);
            if (audible) {
                List<Location> places = heard;
                List<BlockData> now = became;
                List<BlockData> before = were;
                scheduler.run(() -> Ambience.blocks(viewers, places, now, before));
            }
        }
        if (passed == null) return;
        // Handed over on the main thread, because whatever a plugin does with
        // one of these is almost always something Bukkit will not let it do
        // from here.
        List<ReplayMark> batch = passed;
        scheduler.run(() -> batch.forEach(listener));
    }

    /**
     * Draws one mark, if it is one this module knows.
     *
     * @return whether it was drawn here and should not be handed on
     */
    private boolean draw(int index, ReplayMark mark) {
        int actor = mark.actor() == null ? -1 : indexOf(mark.actor());
        NpcHandle body = actor < 0 ? null : bodies[actor];
        switch (mark.kind()) {
            case ReplayMark.SWING -> {
                if (body != null) body.swing();
                heard(actor, Ambience::swing);
                return true;
            }
            case ReplayMark.HURT -> {
                if (body != null) body.hurt();
                heard(actor, Ambience::hurt);
                return true;
            }
            case ReplayMark.EQUIP -> {
                apply(body, index);
                return true;
            }
            case ReplayMark.EXPLOSION -> {
                blast(mark);
                return true;
            }
            case ReplayMark.RESET -> {
                // Everything drawn goes back, and the arena underneath is the
                // one the next round was fought in.
                clearWorld();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** The flash and the bang, for the people watching and nobody else. */
    private void blast(ReplayMark mark) {
        Location at = WorldMarks.explosionAt(anchor, mark.data());
        if (at == null || !audible) return;
        float power = WorldMarks.explosionPower(mark.data());
        // On the server's own thread: a particle and a sound are Bukkit calls,
        // and this is a packet thread.
        scheduler.run(() -> Ambience.explosion(viewers, at, power));
    }

    /** Plays something where one actor is standing right now. */
    private void heard(int actor, java.util.function.BiConsumer<List<Player>, Location> what) {
        if (!audible || actor < 0) return;
        MotionTrack track = replay.tracks().get(actor);
        int at = tick();
        if (!track.present(at)) return;
        Location where = placed(track, at);
        scheduler.run(() -> what.accept(viewers, where));
    }

    /**
     * Puts a batch of changed blocks in front of everybody watching.
     *
     * <p>Really, when the caller owns the arena; as packets otherwise.
     */
    private void showBlocks(Map<Location, BlockData> blocks) {
        if (blocks.isEmpty()) return;
        drawn.addAll(blocks.keySet());
        if (solid) {
            Location where = blocks.keySet().iterator().next();
            scheduler.runAtLocation(where, () -> blocks.forEach((at, data) -> {
                // Without physics: a replay is a picture of what happened, and
                // letting the world work out consequences would have sand fall
                // and water spread all over again on top of the recording.
                if (at.getWorld() != null) at.getBlock().setBlockData(data, false);
            }));
            return;
        }
        scheduler.run(() -> {
            for (Player viewer : viewers) {
                if (viewer != null && viewer.isOnline()) {
                    ReplayRuntime.fakeBlocks().show(viewer, blocks);
                }
            }
        });
    }

    /**
     * Puts the arena back the way it was on this tick.
     *
     * <p>Every block change up to here, replayed in order into one map so the
     * last word on each position wins. Cleared first, because a seek backwards
     * has to take away a crater that has not happened yet &mdash; and a block
     * nothing ever touched needs nothing done to it, since the arena underneath
     * is the one the match was fought in.
     */
    private void rebuildWorld(int tick) {
        if (originals.isEmpty()) return;
        // Every position the recording ever touches, set to what it was on this
        // tick — its last change up to here, or what was there to begin with.
        // Starting from the originals rather than from nothing is what makes a
        // seek backwards put a blown-up wall back instead of leaving the hole.
        Map<Location, BlockData> state = new LinkedHashMap<>(originals);
        for (ReplayMark mark : replay.marks()) {
            if (mark.tick() > tick) break;
            if (ReplayMark.RESET.equals(mark.kind())) {
                // The arena was pasted fresh here, so nothing before it is
                // still standing. Starting over from the originals is what
                // stops a seek past a round reset stacking two rounds of
                // rubble on top of each other.
                state = new LinkedHashMap<>(originals);
                continue;
            }
            if (!ReplayMark.BLOCK.equals(mark.kind())) continue;
            Location at = WorldMarks.blockAt(anchor, mark.data());
            if (at != null) state.put(at, WorldMarks.blockData(mark.data()));
        }
        showBlocks(state);
    }

    /**
     * Puts the arena back the way it was found.
     *
     * <p>Only the positions this playback actually drew. The module's own
     * {@code clear(viewer)} takes away every fake block <em>any</em> plugin has
     * ever shown that player &mdash; the registry is keyed by viewer and nothing
     * else &mdash; so a replay ending would have wiped somebody else's cage,
     * outline or preview off the same screen.
     */
    private void clearWorld() {
        if (drawn.isEmpty()) return;
        List<Location> touched = new ArrayList<>(drawn);
        drawn.clear();
        if (solid) {
            scheduler.runAtLocation(touched.getFirst(), () -> touched.forEach(at -> {
                BlockData was = originals.get(at);
                if (was != null && at.getWorld() != null) at.getBlock().setBlockData(was, false);
            }));
            return;
        }
        scheduler.run(() -> {
            for (Player viewer : viewers) {
                if (viewer != null && viewer.isOnline()) {
                    ReplayRuntime.fakeBlocks().clear(viewer, touched);
                }
            }
        });
    }

    /** Puts everything one actor was wearing at this tick back on their body. */
    private void restore(int index, int tick) {
        NpcHandle body = bodies[index];
        if (body == null) return;
        UUID actor = replay.actors().get(index).id();
        List<ReplayMark> all = replay.marks();
        for (int mark = 0; mark < all.size(); mark++) {
            if (all.get(mark).tick() > tick) break;
            if (dressed[mark] != null && actor.equals(all.get(mark).actor())) {
                apply(body, mark);
            }
        }
    }

    private void apply(NpcHandle body, int mark) {
        Dressed worn = dressed[mark];
        if (body != null && worn != null) body.equip(worn.slot(), worn.item());
    }

    private int indexOf(UUID actor) {
        List<ReplayActor> actors = replay.actors();
        for (int index = 0; index < actors.size(); index++) {
            if (actors.get(index).id().equals(actor)) return index;
        }
        return -1;
    }
}
