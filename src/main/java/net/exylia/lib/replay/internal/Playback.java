package net.exylia.lib.replay.internal;

import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.github.retrooper.packetevents.protocol.sound.SoundCategory;
import net.exylia.lib.replay.Replay;
import net.exylia.lib.replay.ReplayActor;
import net.exylia.lib.replay.ReplayMark;
import net.exylia.lib.replay.ReplayPlayback;
import net.exylia.lib.replay.ReplayScene;
import net.exylia.lib.task.TaskScheduler;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundGroup;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * One recording while somebody is watching it.
 *
 * <h2>It is the client that makes it look real</h2>
 * Every body is a packet entity driven with exactly the packets a real one
 * produces: a relative step each tick, a head turn, a periodic position sync,
 * metadata when the pose or the flags change. The client interpolates between
 * steps the way it does for anybody it can see, which is why a replayed player
 * walks like a player.
 *
 * <h2>Between frames</h2>
 * A recording has a frame per server tick. Played slower than real time, the
 * driver still sends a step every tick, to a position interpolated between the
 * two frames either side, so a quarter-speed replay is a smooth slow motion
 * rather than a body that moves, stops and moves again.
 *
 * <h2>One thread draws</h2>
 * Every frame, a seek included, is drawn by the runtime's driver, and a playback
 * the driver is still drawing is skipped rather than entered twice. Two threads
 * computing a relative step from a position the other one is changing is how a
 * body ends up somewhere neither of them meant.
 *
 * <h2>The arena</h2>
 * Blocks that changed are drawn for the viewer alone by default, or written
 * into a world the caller owns. A seek rebuilds what the arena looked like on
 * that tick and sends only the difference from what is on screen.
 */
@ApiStatus.Internal
public final class Playback implements ReplayPlayback {

    private static final double MIN_SPEED = 1.0 / 16.0;
    private static final double MAX_SPEED = 8.0;
    private static final int NO_SEEK = Integer.MIN_VALUE;

    /** The off hand, in the player inventory's own numbering. */
    private static final int OFF_HAND_SLOT = 40;

    /** 4096ths of a block, which is what a relative step is written in. */
    private static final double STEP_UNIT = 4096.0;

    /** Past this a body did not walk there and is put there outright. */
    private static final double MAX_STEP = 7.5;

    /** How often a moving body is told where it is outright, as the server does. */
    private static final int SYNC_EVERY = 60;

    /** How far apart two frames can be and still be interpolated between. */
    private static final double MAX_LERP = 4.0;

    /** How long a body lies there after dying before it is taken away. */
    private static final int DEATH_TICKS = 20;

    /** Distance walked per footstep, as the game measures it. */
    private static final double STEP_DISTANCE = 1.0 / 0.6;

    /** Sounds a single tick may make, so a fast-forward is not a wall of noise. */
    private static final int SOUNDS_PER_TICK = 16;

    private final String owner;
    private final Replay replay;
    private final Location[] anchors;
    private final List<Player> viewers;
    private final TaskScheduler scheduler;
    private final List<ReplayMark> marks;

    private final Body[] bodies;
    private final EntityType[] types;
    private final Appearance[] looks;
    private final boolean[] living;
    private final boolean[] carriesItem;
    private final List<Worn>[] wardrobe;
    private final List<Riding>[] rides;
    private final int[][] deaths;
    private final int[][] jumps;

    /**
     * Whether the recording kept the sounds the server sent. When it did, those
     * are played and nothing is guessed: guessing on top would play each twice.
     */
    private final boolean heard;

    /** What was at every position the recording changes, before the first change. */
    private final Map<Location, BlockData> originals;

    /** What is on screen at every position this playback has drawn. */
    private final Map<Location, BlockData> shown = new HashMap<>();

    private final AtomicBoolean busy = new AtomicBoolean();

    /** The thread drawing a frame right now, if any. */
    private volatile Thread drawing;

    /** A stop asked for from inside a frame, carried out when the frame ends. */
    private volatile boolean cleanupOwed;

    private volatile boolean solid;
    private volatile double position;
    private volatile double speed = 1.0;
    private volatile int rendered = -1;
    private volatile boolean paused;
    private volatile boolean looping;
    private volatile boolean audible = true;
    private volatile boolean revealing;
    private volatile boolean stopped;
    private volatile boolean carryViewers;
    private volatile int pendingSeek = NO_SEEK;
    private volatile Consumer<ReplayMark> onMark;
    private volatile Runnable onEnd;
    private volatile IntConsumer onScene;

    private int scene = -1;
    private int redrawIn;

    /** Whose eyes the viewers are looking out of, or -1. */
    private volatile int pov = -1;
    private int povSent = -1;

    /** Whose hand and hearts the viewers' own screens show, or -1. */
    private int handOf = -1;
    private final Map<UUID, Integer> heldShown = new HashMap<>();
    private ItemStack mainShown;
    private ItemStack offShown;
    private float healthShown = -1f;
    private int povAge;
    private int soundsThisTick;

    @SuppressWarnings("unchecked")
    Playback(String owner, Replay replay, List<Location> anchors, List<Player> viewers,
             TaskScheduler scheduler) {
        this.owner = owner;
        this.replay = replay;
        this.anchors = new Location[replay.scenes().size()];
        for (int index = 0; index < this.anchors.length; index++) {
            Location given = index < anchors.size() ? anchors.get(index) : null;
            this.anchors[index] = given != null ? given.clone() : derived(replay, anchors, index);
        }
        this.viewers = viewers;
        this.scheduler = scheduler;
        this.marks = replay.marks();
        int actors = replay.actors().size();
        this.bodies = new Body[actors];
        this.types = new EntityType[actors];
        this.looks = new Appearance[actors];
        this.living = new boolean[actors];
        this.carriesItem = new boolean[actors];
        this.wardrobe = new List[actors];
        this.rides = new List[actors];
        this.deaths = new int[actors][];
        this.jumps = new int[actors][];
        for (int index = 0; index < actors; index++) {
            ReplayActor actor = replay.actors().get(index);
            EntityType type = ReplayPackets.typeOf(actor.entityType());
            types[index] = type;
            looks[index] = Appearance.read(actor.appearance());
            living[index] = type != null && type.isInstanceOf(EntityTypes.LIVINGENTITY);
            carriesItem[index] = type != null && carriesItem(type);
            wardrobe[index] = new ArrayList<>(2);
            rides[index] = new ArrayList<>(0);
        }
        index();
        this.heard = marks.stream().anyMatch(mark -> ReplayMark.SOUND.equals(mark.kind())
                || ReplayMark.BLAST.equals(mark.kind()));
        this.originals = originals();
    }

    /**
     * An anchor nobody gave: the first one, moved by however far apart the two
     * scenes were where they were recorded, when they were in the same world.
     */
    private static Location derived(Replay replay, List<Location> anchors, int index) {
        Location first = anchors.getFirst().clone();
        ReplayScene from = replay.scenes().getFirst();
        ReplayScene to = replay.scenes().get(index);
        if (Objects.equals(from.world(), to.world())) {
            first.add(to.x() - from.x(), to.y() - from.y(), to.z() - from.z());
        }
        return first;
    }

    private static boolean carriesItem(EntityType type) {
        return type == EntityTypes.ITEM || type == EntityTypes.SNOWBALL || type == EntityTypes.EGG
                || type == EntityTypes.ENDER_PEARL || type == EntityTypes.EXPERIENCE_BOTTLE
                || type == EntityTypes.POTION || type == EntityTypes.SPLASH_POTION
                || type == EntityTypes.LINGERING_POTION || type == EntityTypes.FIREWORK_ROCKET
                || type == EntityTypes.EYE_OF_ENDER;
    }

    /**
     * Reads every mark that changes how somebody is drawn into per-actor lists,
     * once, so a seek is a lookup rather than a walk through the whole match.
     */
    private void index() {
        List<List<Integer>> died = new ArrayList<>();
        List<List<Integer>> jumped = new ArrayList<>();
        for (int index = 0; index < bodies.length; index++) {
            died.add(new ArrayList<>());
            jumped.add(new ArrayList<>());
        }
        for (ReplayMark mark : marks) {
            int actor = mark.actor() == null ? -1 : indexOf(mark.actor());
            if (actor < 0) continue;
            switch (mark.kind()) {
                case ReplayMark.EQUIP -> {
                    EquipmentSlot slot = Recording.Equipment.slotOf(mark.data());
                    int at = slotIndex(slot);
                    if (at >= 0) {
                        wardrobe[actor].add(new Worn(mark.tick(), at,
                                Recording.Equipment.itemOf(mark.data())));
                    }
                }
                case ReplayMark.MOUNT -> {
                    UUID vehicle = MarkData.other(mark.data());
                    int on = vehicle == null ? -1 : indexOf(vehicle);
                    rides[actor].add(new Riding(mark.tick(), on));
                }
                case ReplayMark.DISMOUNT -> rides[actor].add(new Riding(mark.tick(), -1));
                case ReplayMark.DEATH -> died.get(actor).add(mark.tick());
                case ReplayMark.RESPAWN -> died.get(actor).add(-mark.tick() - 1);
                case ReplayMark.TELEPORT -> jumped.get(actor).add(mark.tick());
                default -> {
                }
            }
        }
        for (int index = 0; index < bodies.length; index++) {
            deaths[index] = died.get(index).stream().mapToInt(Integer::intValue).toArray();
            jumps[index] = jumped.get(index).stream().mapToInt(Integer::intValue).toArray();
        }
    }

    /** What was at each changed position before the recording touched it. */
    private Map<Location, BlockData> originals() {
        Map<Location, BlockData> first = new LinkedHashMap<>();
        for (ReplayMark mark : marks) {
            if (!ReplayMark.BLOCK.equals(mark.kind())) continue;
            Location at = WorldMarks.blockAt(anchorAt(mark.tick()), mark.data());
            if (at != null) first.putIfAbsent(at, WorldMarks.blockBefore(mark.data()));
        }
        return first;
    }

    void solid(boolean solid) {
        this.solid = solid;
    }

    /** Whether viewers are moved along with a cut to another scene. */
    void carryViewers(boolean carry) {
        this.carryViewers = carry;
    }

    String owner() {
        return owner;
    }

    boolean hasViewers() {
        for (Player viewer : viewers) {
            if (viewer != null && viewer.isOnline()) return true;
        }
        return false;
    }

    // ----------------------------------------------------------------- driver

    /** Advances it by one server tick. Called by the runtime's driver. */
    void step() {
        if (stopped || !busy.compareAndSet(false, true)) return;
        drawing = Thread.currentThread();
        try {
            // Stopped between the check and taking the lock: drawing now would
            // put bodies back on screens that were just cleaned.
            if (stopped) return;
            soundsThisTick = 0;
            int wanted = pendingSeek;
            if (wanted != NO_SEEK) {
                pendingSeek = NO_SEEK;
                jumpTo(wanted);
                return;
            }
            if (redrawIn > 0 && --redrawIn == 0) {
                redraw();
                return;
            }
            if (paused) {
                camera();
                return;
            }
            int last = Math.max(0, replay.frames() - 1);
            double next = Math.min(position + speed, last);
            int from = rendered;
            int to = (int) Math.floor(next);
            position = next;
            draw(next, false);
            if (to > from) {
                marks(from + 1, to);
                rendered = to;
            }
            if (next < last) return;
            if (looping) {
                jumpTo(0);
                return;
            }
            // Held on the last frame rather than taken away: the end of a fight
            // is the moment somebody watching wants to sit on.
            paused = true;
            Runnable end = onEnd;
            onEnd = null;
            if (end != null) scheduler.run(end);
        } finally {
            drawing = null;
            if (stopped && cleanupOwed) clean();
            busy.set(false);
        }
    }

    /** Carries out a seek: everything put where it was on that tick, at once. */
    private void jumpTo(int target) {
        int from = rendered;
        position = target;
        rendered = target;
        rebuildWorld(target);
        // A real jump draws everybody afresh rather than moving them: a body
        // moved by teleport and then by steps drifts off on some clients
        // until the next sync puts it back, and several skips in a row pile
        // that up.
        if (Math.abs(target - from) > 2) {
            for (int index = 0; index < bodies.length; index++) remove(index);
        }
        draw(target, true);
        // Stepping a frame or two on is still watching: the swing and the hit
        // on that frame are drawn.
        if (target > from && target - from <= 2) marks(from + 1, target);
    }

    /** Takes every body off the screen and draws it again, after a cut. */
    private void redraw() {
        for (int index = 0; index < bodies.length; index++) remove(index);
        draw(position, true);
    }

    // ---------------------------------------------------------------- drawing

    /**
     * Puts every actor where it was at this point of the recording.
     *
     * @param at   the position, in ticks, fractional between two frames
     * @param snap whether this is a jump rather than the next step
     */
    private void draw(double at, boolean snap) {
        int tick = (int) Math.floor(at);
        double fraction = at - tick;
        int now = replay.sceneAt(tick);
        if (now != scene) {
            int was = scene;
            scene = now;
            if (was >= 0) {
                cut(was, now);
                snap = true;
            }
        }
        for (int index = 0; index < bodies.length; index++) {
            drawActor(index, tick, fraction, snap);
        }
        mountRiders(tick);
        camera();
    }

    /**
     * Keeps the viewers' camera on the body they are following.
     *
     * <p>The client is told to look out of the body's eyes, which follows it
     * with the client's own smoothing: nothing is teleported every tick. The
     * viewer's real position is brought along now and then, because the client
     * only has the chunks around where it really is.
     */
    private void camera() {
        int wanted = pov;
        Body body = wanted < 0 ? null : bodies[wanted];
        int id = body == null ? -1 : body.id;
        if (id != povSent) {
            povSent = id;
            for (Player viewer : viewers) {
                if (viewer != null && viewer.isOnline()) {
                    ReplayPackets.camera(viewer, id < 0 ? viewer.getEntityId() : id);
                }
            }
        }
        if (body == null) {
            if (handOf >= 0) giveBack();
            return;
        }
        firstPerson(wanted, body);
        if (++povAge % 20 != 0) return;
        Location near = new Location(anchors[Math.max(0, scene)].getWorld(), body.x, body.y + 1.5, body.z);
        for (Player viewer : viewers) {
            if (viewer == null || !viewer.isOnline()) continue;
            scheduler.runAtEntity(viewer, () -> {
                Location here = viewer.getLocation();
                if (here.getWorld() != near.getWorld() || here.distanceSquared(near) > 16 * 16) {
                    Location to = near.clone();
                    to.setYaw(here.getYaw());
                    to.setPitch(here.getPitch());
                    viewer.teleportAsync(to);
                }
            });
        }
    }

    /**
     * Their eyes are not enough to be them: the viewer's own screen shows the
     * hand they held, the hearts they had, their swing, their hits taken and
     * their totem going off, as the game shows a player all of it. Painted
     * on the viewer's screen only; the hotbar under it is untouched.
     */
    private void firstPerson(int actor, Body body) {
        if (handOf != actor) {
            handOf = actor;
            heldShown.clear();
            mainShown = null;
            offShown = null;
            healthShown = -1f;
        }
        ItemStack main = body.worn[0];
        ItemStack off = body.worn[1];
        boolean mainChanged = !Objects.equals(main, mainShown);
        boolean offChanged = !Objects.equals(off, offShown) || heldShown.isEmpty();
        mainShown = main;
        offShown = off;
        for (Player viewer : viewers) {
            if (viewer == null || !viewer.isOnline()) continue;
            int held = viewer.getInventory().getHeldItemSlot();
            Integer was = heldShown.put(viewer.getUniqueId(), held);
            if (mainChanged || was == null || was != held) ReplayPackets.hand(viewer, held, main);
            if (offChanged) ReplayPackets.hand(viewer, OFF_HAND_SLOT, off);
        }
        MotionTrack track = replay.tracks().get(actor);
        int tick = Math.max(0, rendered);
        if (!track.present(tick)) return;
        float health = (float) Math.max(0.5, track.health(tick));
        if (health != healthShown) {
            healthShown = health;
            ReplayPackets.health(viewers, health);
        }
    }

    /** Puts the viewers' own hand, hotbar and hearts back. */
    private void giveBack() {
        handOf = -1;
        heldShown.clear();
        for (Player viewer : viewers) {
            if (viewer == null || !viewer.isOnline()) continue;
            scheduler.runAtEntity(viewer, () -> {
                viewer.updateInventory();
                viewer.sendHealthUpdate();
            });
        }
    }

    /** One of the followed body's own moments, played on the viewers' screens as theirs. */
    private void asViewer(int actor, java.util.function.BiConsumer<List<Player>, Integer> send) {
        if (actor < 0 || actor != handOf) return;
        for (Player viewer : viewers) {
            if (viewer != null && viewer.isOnline()) send.accept(List.of(viewer), viewer.getEntityId());
        }
    }

    private void drawActor(int index, int tick, double fraction, boolean snap) {
        MotionTrack track = replay.tracks().get(index);
        int died = diedAt(index, tick);
        Body body = bodies[index];
        if (died >= 0) {
            // The sample of the tick they died on is usually already of a dead
            // player, which is not sampled: the frame before it is where they fell.
            int fell = track.present(died) ? died : died - 1;
            // Lying where they fell, until the game itself would have taken
            // the body away.
            if (tick - died >= DEATH_TICKS || !track.present(fell)) {
                if (body != null) {
                    if (!snap) ReplayPackets.particle(viewers, ParticleTypes.POOF,
                            body.x, body.y + 0.6, body.z, 0.3f, 0.02f, 12);
                    remove(index);
                }
                return;
            }
            if (body == null || body.deadSent < 0) {
                if (body == null) body = spawn(index, fell);
                if (body == null) return;
                if (snap) place(body, track, fell, 0, true);
                ReplayPackets.dying(viewers, body.id);
                body.deadSent = died;
            }
            return;
        }
        if (!track.present(tick)) {
            if (body != null) {
                if (!snap) disappeared(index, body);
                remove(index);
            }
            return;
        }
        if (body == null) {
            body = spawn(index, tick);
            if (body == null) return;
            if (!snap && tick == track.firstTick()) appeared(index, body);
            return;
        }
        if (body.deadSent >= 0) {
            // Back from the dead: a fresh body rather than one lying down.
            remove(index);
            spawn(index, tick);
            return;
        }
        place(body, track, tick, fraction, snap || jumped(index, tick));
        state(index, body, track, tick);
        // A seek skips the equipment marks in between: what they wore on the
        // tick landed on is read again.
        if (snap && living[index]) dress(index, body, tick);
    }

    /** Draws one body for the first time, as it was on this tick. */
    private Body spawn(int index, int tick) {
        EntityType type = types[index];
        if (type == null) return null;
        MotionTrack track = replay.tracks().get(index);
        if (!track.present(tick)) return null;
        ReplayActor actor = replay.actors().get(index);
        Location at = place(track, tick);
        Body body = new Body(ReplayPackets.newEntityId());
        boolean player = actor.isPlayer();
        if (player) {
            // Its own identity, never the recorded player's: announcing a second
            // entry under a real player's id takes that player's skin off their
            // own body until they relog.
            body.profile = UUID.randomUUID();
            ReplayPackets.announce(viewers, body.profile, actor.name(), actor.texture(),
                    actor.signature());
        }
        ReplayPackets.spawn(viewers, body.id, player ? body.profile : UUID.randomUUID(), type,
                at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(), track.headYaw(tick),
                ReplayPackets.spawnData(type, looks[index]));
        if (player) ReplayPackets.skinLayers(viewers, body.id);
        else ReplayPackets.appearance(viewers, body.id, looks[index], carriesItem[index]);
        if (!living[index]) ReplayPackets.still(viewers, body.id, type);
        body.x = at.getX();
        body.y = at.getY();
        body.z = at.getZ();
        body.yaw = MotionTrack.angle(at.getYaw());
        body.pitch = MotionTrack.angle(at.getPitch());
        body.head = MotionTrack.angle(track.headYaw(tick));
        bodies[index] = body;
        // A vehicle drawn again is a new entity id: whoever rides it is put
        // back on by the next pass rather than believed to be there already.
        for (Body other : bodies) {
            if (other != null && other.vehicle == index) other.vehicle = -1;
        }
        state(index, body, track, tick);
        if (living[index]) dress(index, body, tick);
        return body;
    }

    /** Moves a body to where it is now, the way the server moves an entity. */
    private void place(Body body, MotionTrack track, int tick, double fraction, boolean snap) {
        Location at = place(track, tick);
        double x = at.getX();
        double y = at.getY();
        double z = at.getZ();
        float yaw = at.getYaw();
        float pitch = at.getPitch();
        float head = track.headYaw(tick);
        if (fraction > 0 && track.present(tick + 1) && replay.sceneAt(tick + 1) == replay.sceneAt(tick)) {
            Location next = place(track, tick + 1);
            if (next.distanceSquared(at) <= MAX_LERP * MAX_LERP) {
                x += (next.getX() - x) * fraction;
                y += (next.getY() - y) * fraction;
                z += (next.getZ() - z) * fraction;
                yaw = lerpAngle(yaw, next.getYaw(), fraction);
                pitch += (float) ((next.getPitch() - pitch) * fraction);
                head = lerpAngle(head, track.headYaw(tick + 1), fraction);
            }
        }
        boolean onGround = track.onGround(tick);
        byte yawByte = MotionTrack.angle(yaw);
        byte pitchByte = MotionTrack.angle(pitch);
        byte headByte = MotionTrack.angle(head);
        double dx = x - body.x;
        double dy = y - body.y;
        double dz = z - body.z;
        if (snap) {
            ReplayPackets.teleport(viewers, body.id, x, y, z, yaw, pitch, onGround);
            body.moveTo(x, y, z);
        } else if (Math.abs(dx) > MAX_STEP || Math.abs(dy) > MAX_STEP || Math.abs(dz) > MAX_STEP) {
            // Nobody walks eight blocks in a tick: a pearl, a teleport.
            ReplayPackets.teleport(viewers, body.id, x, y, z, yaw, pitch, onGround);
            body.moveTo(x, y, z);
        } else if (++body.steps >= SYNC_EVERY) {
            ReplayPackets.sync(viewers, body.id, x, y, z, yaw, pitch, onGround);
            body.moveTo(x, y, z);
        } else {
            // Rounded to what the packet can carry and then believed to be
            // exactly that. Remembering the step wanted instead of the step
            // sent is how a body drifts into the floor over a minute.
            double qx = Math.round(dx * STEP_UNIT) / STEP_UNIT;
            double qy = Math.round(dy * STEP_UNIT) / STEP_UNIT;
            double qz = Math.round(dz * STEP_UNIT) / STEP_UNIT;
            if (qx != 0 || qy != 0 || qz != 0 || yawByte != body.yaw || pitchByte != body.pitch
                    || onGround != body.onGround) {
                ReplayPackets.step(viewers, body.id, qx, qy, qz, yaw, pitch, onGround);
                body.x += qx;
                body.y += qy;
                body.z += qz;
            }
            footsteps(body, track, tick, qx, qz, onGround);
        }
        body.yaw = yawByte;
        body.pitch = pitchByte;
        body.onGround = onGround;
        if (headByte != body.head || snap) {
            ReplayPackets.head(viewers, body.id, head);
            body.head = headByte;
        }
    }

    /** Sends the flags, the pose and the raised hand when any of them changed. */
    private void state(int index, Body body, MotionTrack track, int tick) {
        int flags = track.flags(tick);
        String pose = track.poseName(tick);
        byte base = 0;
        if ((flags & MotionTrack.ON_FIRE) != 0) base |= ReplayPackets.FLAG_ON_FIRE;
        if (pose.equals("CROUCHING")) base |= ReplayPackets.FLAG_CROUCHING;
        if ((flags & MotionTrack.SPRINTING) != 0) base |= ReplayPackets.FLAG_SPRINTING;
        if ((flags & MotionTrack.INVISIBLE) != 0) {
            // Staff reviewing a fight want to see who was there; anybody else
            // sees what the players saw.
            base |= revealing ? ReplayPackets.FLAG_GLOWING : ReplayPackets.FLAG_INVISIBLE;
        }
        if ((flags & MotionTrack.GLOWING) != 0) base |= ReplayPackets.FLAG_GLOWING;
        if (pose.equals("FALL_FLYING")) base |= ReplayPackets.FLAG_GLIDING;
        int hands = 0;
        if ((flags & MotionTrack.USING) != 0) hands = 0x01;
        if ((flags & MotionTrack.USING_OFF_HAND) != 0) hands = 0x03;
        int key = (base & 0xFF) | (flags & MotionTrack.POSE_MASK) << 8 | hands << 16;
        if (key == body.state) return;
        body.state = key;
        ReplayPackets.state(viewers, body.id, base, pose, living[index], hands);
    }

    /** Puts on everything somebody was wearing on this tick. */
    private void dress(int index, Body body, int tick) {
        ItemStack[] wanted = new ItemStack[Sampler.SLOTS.length];
        for (Worn worn : wardrobe[index]) {
            if (worn.tick > tick) break;
            wanted[worn.slot] = worn.item;
        }
        for (int slot = 0; slot < wanted.length; slot++) {
            if (body.drawn && Objects.equals(wanted[slot], body.worn[slot])) continue;
            if (!body.drawn && wanted[slot] == null) continue;
            body.worn[slot] = wanted[slot];
            ReplayPackets.equip(viewers, body.id, Sampler.SLOTS[slot], wanted[slot]);
        }
        body.drawn = true;
    }

    /** Who is riding what on this tick, sent only when it changed. */
    private void mountRiders(int tick) {
        Set<Integer> changed = null;
        for (int rider = 0; rider < bodies.length; rider++) {
            if (rides[rider].isEmpty()) continue;
            int vehicle = -1;
            for (Riding riding : rides[rider]) {
                if (riding.tick > tick) break;
                vehicle = riding.vehicle;
            }
            Body body = bodies[rider];
            if (body == null || vehicle < 0 || bodies[vehicle] == null) vehicle = -1;
            int shownOn = body == null ? -1 : body.vehicle;
            if (shownOn == vehicle) continue;
            if (changed == null) changed = new HashSet<>();
            if (shownOn >= 0) changed.add(shownOn);
            if (vehicle >= 0) changed.add(vehicle);
            if (body != null) body.vehicle = vehicle;
        }
        if (changed == null) return;
        for (int vehicle : changed) {
            Body carrier = bodies[vehicle];
            if (carrier == null) continue;
            List<Integer> riders = new ArrayList<>(1);
            for (int rider = 0; rider < bodies.length; rider++) {
                if (bodies[rider] != null && bodies[rider].vehicle == vehicle) {
                    riders.add(bodies[rider].id);
                }
            }
            ReplayPackets.passengers(viewers, carrier.id,
                    riders.stream().mapToInt(Integer::intValue).toArray());
        }
    }

    /** Takes one actor away, if it is there. */
    private void remove(int index) {
        Body body = bodies[index];
        bodies[index] = null;
        if (body == null) return;
        ReplayPackets.destroy(viewers, body.id, body.profile);
    }

    /** The tick somebody died on, if they are dead on this one; otherwise -1. */
    private int diedAt(int index, int tick) {
        int died = -1;
        for (int event : deaths[index]) {
            if (event >= 0) {
                if (event > tick) break;
                died = event;
            } else {
                int respawned = -event - 1;
                if (respawned > tick) break;
                died = -1;
            }
        }
        return died;
    }

    private Location place(MotionTrack track, int tick) {
        Location at = anchorAt(tick).clone();
        at.add(track.x(tick), track.y(tick), track.z(tick));
        at.setYaw(track.yaw(tick));
        at.setPitch(track.pitch(tick));
        return at;
    }

    private Location anchorAt(int tick) {
        return anchors[replay.sceneAt(tick)];
    }

    private static float lerpAngle(float from, float to, double fraction) {
        float delta = ((to - from) % 360f + 540f) % 360f - 180f;
        return (float) (from + delta * fraction);
    }

    // ------------------------------------------------------------ scene cuts

    private void cut(int from, int to) {
        IntConsumer listener = onScene;
        if (listener != null) scheduler.run(() -> listener.accept(to));
        if (!carryViewers) return;
        Location before = anchors[from];
        Location after = anchors[to];
        for (Player viewer : viewers) {
            if (viewer == null || !viewer.isOnline()) continue;
            scheduler.runAtEntity(viewer, () -> {
                Location here = viewer.getLocation();
                Location there = after.clone().add(here.getX() - before.getX(),
                        here.getY() - before.getY(), here.getZ() - before.getZ());
                there.setYaw(here.getYaw());
                there.setPitch(here.getPitch());
                viewer.teleportAsync(there);
            });
        }
        // The client forgets nothing on a teleport inside one world, but it does
        // not draw entities in chunks it has not loaded yet: drawn again once the
        // viewer has arrived.
        redrawIn = 10;
    }

    // ------------------------------------------------------------------ marks

    /** Walks the marks of the ticks just passed, drawing the module's own. */
    private void marks(int from, int to) {
        if (from > to) return;
        Consumer<ReplayMark> listener = onMark;
        List<ReplayMark> passed = null;
        Map<Location, BlockData> changed = null;
        List<Location> places = null;
        List<BlockData> became = null;
        List<BlockData> were = null;
        for (int index = firstMark(from); index < marks.size(); index++) {
            ReplayMark mark = marks.get(index);
            if (mark.tick() > to) break;
            if (ReplayMark.BLOCK.equals(mark.kind())) {
                Location at = WorldMarks.blockAt(anchorAt(mark.tick()), mark.data());
                if (at != null) {
                    if (changed == null) {
                        changed = new LinkedHashMap<>();
                        places = new ArrayList<>();
                        became = new ArrayList<>();
                        were = new ArrayList<>();
                    }
                    BlockData now = WorldMarks.blockData(mark.data());
                    changed.put(at, now);
                    places.add(at);
                    became.add(now);
                    were.add(WorldMarks.blockBefore(mark.data()));
                }
                continue;
            }
            boolean drawn = draw(mark);
            if (listener != null && (!drawn || handedOn(mark.kind()))) {
                if (passed == null) passed = new ArrayList<>(2);
                passed.add(mark);
            }
        }
        if (changed != null) {
            showBlocks(changed);
            if (audible) blockEffects(places, became, were);
        }
        if (passed == null) return;
        // On the server's thread: whatever a plugin does with one of these is
        // almost always something Bukkit will not let it do from here.
        List<ReplayMark> batch = passed;
        scheduler.run(() -> batch.forEach(listener));
    }

    /** Marks the module draws and still tells the plugin about. */
    private static boolean handedOn(String kind) {
        return switch (kind) {
            case ReplayMark.DEATH, ReplayMark.RESPAWN, ReplayMark.TOTEM, ReplayMark.TELEPORT,
                 ReplayMark.CHAT, ReplayMark.QUIT -> true;
            default -> false;
        };
    }

    /** The first mark on or after a tick. */
    private int firstMark(int tick) {
        int low = 0;
        int high = marks.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (marks.get(middle).tick() < tick) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    /**
     * Draws one mark, if it is one the module knows.
     *
     * @return whether it was drawn here
     */
    private boolean draw(ReplayMark mark) {
        int actor = mark.actor() == null ? -1 : indexOf(mark.actor());
        Body body = actor < 0 ? null : bodies[actor];
        switch (mark.kind()) {
            case ReplayMark.SWING -> {
                boolean off = mark.data() != null && mark.data().length > 0 && mark.data()[0] == 1;
                if (body != null) ReplayPackets.swing(viewers, body.id, off);
                asViewer(actor, (to, id) -> ReplayPackets.swing(to, id, off));
                return true;
            }
            case ReplayMark.HURT -> {
                asViewer(actor, (to, id) -> ReplayPackets.hurt(to, id, MarkData.hurtDirection(mark.data())));
                if (body != null) {
                    ReplayPackets.hurt(viewers, body.id, MarkData.hurtDirection(mark.data()));
                    if (audible(1)) {
                        ReplayPackets.entitySound(viewers, hurtSound(actor), category(actor),
                                body.id, 1f, pitch());
                    }
                }
                return true;
            }
            case ReplayMark.ATTACK -> {
                attack(body, mark);
                sweep(body, mark);
                return true;
            }
            case ReplayMark.EQUIP -> {
                if (body != null && actor >= 0 && living[actor]) dress(actor, body, mark.tick());
                return true;
            }
            case ReplayMark.SOUND -> {
                Location at = WorldMarks.placeAt(anchorAt(mark.tick()), mark.data());
                String[] parts = text(mark, 4);
                if (at != null && parts != null && audible(1)) {
                    try {
                        ReplayPackets.sound(viewers, parts[0], SoundCategory.valueOf(parts[1]),
                                at.getX(), at.getY(), at.getZ(), Float.parseFloat(parts[2]),
                                Float.parseFloat(parts[3]));
                    } catch (IllegalArgumentException unreadable) {
                        // A category or a number from a version that wrote them differently.
                    }
                }
                return true;
            }
            case ReplayMark.BLAST -> {
                Location at = WorldMarks.placeAt(anchorAt(mark.tick()), mark.data());
                String[] parts = text(mark, 2);
                if (at != null && parts != null && audible(1)) {
                    ReplayPackets.blast(viewers, at.getX(), at.getY(), at.getZ(), parts[0], parts[1]);
                }
                return true;
            }
            case ReplayMark.EXPLOSION -> {
                Location at = WorldMarks.explosionAt(anchorAt(mark.tick()), mark.data());
                // Recorded as the server sent it: drawn from its BLAST instead.
                if (at != null && !heard && audible(1)) {
                    float power = WorldMarks.explosionPower(mark.data());
                    ReplayPackets.particle(viewers, power >= 2f ? ParticleTypes.EXPLOSION_EMITTER
                            : ParticleTypes.EXPLOSION, at.getX(), at.getY(), at.getZ(), 0f, 0f, 1);
                    ReplayPackets.sound(viewers, "minecraft:entity.generic.explode", SoundCategory.BLOCK,
                            at.getX(), at.getY(), at.getZ(), 4f, 0.7f + (float) Math.random() * 0.2f);
                }
                return true;
            }
            case ReplayMark.RESET -> {
                rebuildWorld(mark.tick());
                return true;
            }
            case ReplayMark.DEATH -> {
                // Drawn by the body itself, which knows when it died; the
                // client plays the death sound from the same entity event.
                return true;
            }
            case ReplayMark.TOTEM -> {
                if (body != null) ReplayPackets.status(viewers, body.id, ReplayPackets.STATUS_TOTEM);
                asViewer(actor, (to, id) -> ReplayPackets.status(to, id, ReplayPackets.STATUS_TOTEM));
                return true;
            }
            case ReplayMark.SHIELD_DISABLED -> {
                if (body != null) ReplayPackets.status(viewers, body.id, ReplayPackets.STATUS_SHIELD_BREAK);
                return true;
            }
            case ReplayMark.PICKUP -> {
                UUID item = MarkData.other(mark.data());
                int taken = item == null ? -1 : indexOf(item);
                if (body != null && taken >= 0 && bodies[taken] != null) {
                    ReplayPackets.collect(viewers, bodies[taken].id, body.id, MarkData.amount(mark.data()));
                    // The client takes the item away itself as it flies in.
                    bodies[taken] = null;
                }
                return true;
            }
            case ReplayMark.BREAKING -> {
                Location at = WorldMarks.placeAt(anchorAt(mark.tick()), mark.data());
                String stage = WorldMarks.placeText(mark.data());
                if (at != null && stage != null) {
                    int id = body != null ? body.id : (mark.actor() == null ? 0 : mark.actor().hashCode());
                    ReplayPackets.cracks(viewers, id, at.getBlockX(), at.getBlockY(), at.getBlockZ(),
                            parse(stage));
                }
                return true;
            }
            case ReplayMark.MOUNT, ReplayMark.DISMOUNT, ReplayMark.RESPAWN, ReplayMark.QUIT,
                 ReplayMark.PING, ReplayMark.SERVER -> {
                // Drawn from the per-actor lists as the frames pass.
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** The sounds and particles of one hit, as the game makes them. */
    private void attack(@Nullable Body attacker, ReplayMark mark) {
        int how = MarkData.attackHow(mark.data());
        UUID victimId = MarkData.attackVictim(mark.data());
        int victim = victimId == null ? -1 : indexOf(victimId);
        Body target = victim < 0 ? null : bodies[victim];
        if ((how & MarkData.CRIT) != 0 && target != null) {
            ReplayPackets.critical(viewers, target.id, false);
        }
        if ((how & MarkData.BLOCKED) != 0 && target != null) {
            ReplayPackets.status(viewers, target.id, ReplayPackets.STATUS_SHIELD_BLOCK);
        }
        if (attacker == null || heard || !audible(1)) return;
        String sound;
        if ((how & MarkData.NO_DAMAGE) != 0) sound = "minecraft:entity.player.attack.nodamage";
        else if ((how & MarkData.CRIT) != 0) sound = "minecraft:entity.player.attack.crit";
        else if ((how & MarkData.SWEEP) != 0) sound = "minecraft:entity.player.attack.sweep";
        else if ((how & MarkData.KNOCKBACK) != 0) sound = "minecraft:entity.player.attack.knockback";
        else if ((how & MarkData.STRONG) != 0) sound = "minecraft:entity.player.attack.strong";
        else sound = "minecraft:entity.player.attack.weak";
        ReplayPackets.sound(viewers, sound, SoundCategory.PLAYER, attacker.x, attacker.y, attacker.z, 1f, 1f);
    }

    /** The sweep the server shows as a particle, which is not one of the sounds kept. */
    private void sweep(@Nullable Body attacker, ReplayMark mark) {
        if (attacker != null && (MarkData.attackHow(mark.data()) & MarkData.SWEEP) != 0) {
            double yaw = Math.toRadians(attacker.yaw * 360f / 256f);
            ReplayPackets.particle(viewers, ParticleTypes.SWEEP_ATTACK,
                    attacker.x - Math.sin(yaw), attacker.y + 0.9, attacker.z + Math.cos(yaw), 0f, 0f, 1);
        }
    }

    /** The {@code |}-separated line beside a place mark, if it has this many parts. */
    private static String @Nullable [] text(ReplayMark mark, int parts) {
        String line = WorldMarks.placeText(mark.data());
        if (line == null) return null;
        String[] split = line.split("\\|", parts);
        return split.length == parts ? split : null;
    }

    /**
     * Whether somebody was teleported going into or out of this tick: a pearl
     * landing is a jump, not a slide across the arena.
     */
    private boolean jumped(int index, int tick) {
        for (int at : jumps[index]) {
            if (at > tick + 1) break;
            if (at >= tick - 1) return true;
        }
        return false;
    }

    private String hurtSound(int actor) {
        String type = replay.actors().get(actor).entityType();
        return type == null ? "minecraft:entity.player.hurt"
                : "minecraft:entity." + type.toLowerCase(Locale.ROOT) + ".hurt";
    }

    private SoundCategory category(int actor) {
        return replay.actors().get(actor).isPlayer() ? SoundCategory.PLAYER : SoundCategory.NEUTRAL;
    }

    private static float pitch() {
        return (float) ((Math.random() - Math.random()) * 0.2 + 1.0);
    }

    private static int parse(String stage) {
        try {
            return Integer.parseInt(stage);
        } catch (NumberFormatException broken) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- effects

    /** Whether there is room for this many more sounds this tick. */
    private boolean audible(int sounds) {
        if (!audible || soundsThisTick + sounds > SOUNDS_PER_TICK) return false;
        soundsThisTick += sounds;
        return true;
    }

    /** A projectile leaving a bow, a pearl leaving a hand, a fuse being lit. */
    private void appeared(int index, Body body) {
        String sound = switch (String.valueOf(replay.actors().get(index).entityType())) {
            case "ARROW", "SPECTRAL_ARROW" -> "minecraft:entity.arrow.shoot";
            case "ENDER_PEARL" -> "minecraft:entity.ender_pearl.throw";
            case "SPLASH_POTION", "LINGERING_POTION", "POTION" -> "minecraft:entity.splash_potion.throw";
            case "SNOWBALL" -> "minecraft:entity.snowball.throw";
            case "EGG" -> "minecraft:entity.egg.throw";
            case "TRIDENT" -> "minecraft:item.trident.throw";
            case "FIREBALL", "SMALL_FIREBALL" -> "minecraft:entity.blaze.shoot";
            case "TNT" -> "minecraft:entity.tnt.primed";
            case "FIREWORK_ROCKET" -> "minecraft:entity.firework_rocket.launch";
            case "WIND_CHARGE" -> "minecraft:entity.wind_charge.throw";
            default -> null;
        };
        if (sound != null && !heard && audible(1)) {
            ReplayPackets.sound(viewers, sound, SoundCategory.NEUTRAL, body.x, body.y, body.z, 0.8f, pitch());
        }
    }

    /** An arrow landing; everything else leaves quietly. */
    private void disappeared(int index, Body body) {
        String type = replay.actors().get(index).entityType();
        if (("ARROW".equals(type) || "SPECTRAL_ARROW".equals(type)) && !heard && audible(1)) {
            ReplayPackets.sound(viewers, "minecraft:entity.arrow.hit", SoundCategory.NEUTRAL,
                    body.x, body.y, body.z, 0.6f, pitch());
        }
    }

    /**
     * A footstep every step's worth of distance, in the sound of whatever is
     * underfoot.
     *
     * <p>The game makes these on the server for real players, so a replay
     * without them is a fight in socks. The block underfoot is read on the
     * region that owns it, which is the only place it may be read.
     */
    private void footsteps(Body body, MotionTrack track, int tick, double dx, double dz, boolean onGround) {
        if (!audible || heard || !onGround || speed > 2.0) {
            body.walked = 0;
            return;
        }
        String pose = track.poseName(tick);
        if (pose.equals("SWIMMING") || pose.equals("FALL_FLYING")) return;
        body.walked += Math.sqrt(dx * dx + dz * dz);
        if (body.walked < STEP_DISTANCE) return;
        body.walked = 0;
        if (!audible(1)) return;
        Location feet = new Location(anchorAt(tick).getWorld(), body.x, body.y - 0.2, body.z);
        if (feet.getWorld() == null) return;
        // Sneaking makes no sound, which is the point of it.
        if (pose.equals("CROUCHING")) return;
        scheduler.runAtLocation(feet, () -> {
            BlockData under = feet.getBlock().getBlockData();
            if (under.getMaterial() == Material.AIR) return;
            SoundGroup group = under.getSoundGroup();
            ReplayPackets.sound(viewers, keyOf(group.getStepSound()), SoundCategory.PLAYER,
                    feet.getX(), feet.getY(), feet.getZ(), group.getVolume() * 0.15f, group.getPitch());
        });
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static String keyOf(org.bukkit.Sound sound) {
        return sound.getKey().toString();
    }

    /** The sound and the pieces of a batch of block changes, a few of them. */
    private void blockEffects(List<Location> places, List<BlockData> now, List<BlockData> before) {
        for (int index = 0; index < places.size(); index++) {
            if (!audible(1)) return;
            BlockData became = now.get(index);
            BlockData was = before.get(index);
            Location at = places.get(index);
            boolean broken = became == null || became.getMaterial().isAir();
            if (broken) {
                if (was != null && !was.getMaterial().isAir()) {
                    ReplayPackets.broken(viewers, at.getBlockX(), at.getBlockY(), at.getBlockZ(), was);
                }
            } else if (!heard) {
                SoundGroup group = became.getSoundGroup();
                ReplayPackets.sound(viewers, keyOf(group.getPlaceSound()), SoundCategory.BLOCK,
                        at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5,
                        (group.getVolume() + 1f) / 2f, group.getPitch() * 0.8f);
            }
        }
    }

    // ------------------------------------------------------------------ world

    /**
     * Puts a batch of blocks in front of everybody watching: into the world
     * when the caller owns it, as packets otherwise. Only what differs from
     * what is already on screen.
     */
    private void showBlocks(Map<Location, BlockData> blocks) {
        Map<Location, BlockData> differ = new LinkedHashMap<>();
        for (Map.Entry<Location, BlockData> entry : blocks.entrySet()) {
            if (Objects.equals(shown.get(entry.getKey()), entry.getValue())) continue;
            shown.put(entry.getKey(), entry.getValue());
            differ.put(entry.getKey(), entry.getValue());
        }
        if (differ.isEmpty()) return;
        if (solid) {
            write(differ);
            return;
        }
        scheduler.run(() -> {
            for (Player viewer : viewers) {
                if (viewer != null && viewer.isOnline()) ReplayRuntime.fakeBlocks().show(viewer, differ);
            }
        });
    }

    /**
     * Writes blocks into the world, each chunk on the region that owns it, and
     * without physics: a replay is a picture of what happened, and letting the
     * world work out consequences would have sand fall all over again.
     */
    private void write(Map<Location, BlockData> blocks) {
        Map<Long, Map<Location, BlockData>> byChunk = new HashMap<>();
        blocks.forEach((at, data) -> byChunk.computeIfAbsent(
                ((long) (at.getBlockX() >> 4) << 32) ^ (at.getBlockZ() >> 4 & 0xFFFFFFFFL),
                key -> new LinkedHashMap<>()).put(at, data));
        for (Map<Location, BlockData> chunk : byChunk.values()) {
            Location where = chunk.keySet().iterator().next();
            scheduler.runAtLocation(where, () -> chunk.forEach((at, data) -> {
                if (at.getWorld() != null) at.getBlock().setBlockData(data, false);
            }));
        }
    }

    /**
     * Puts the arena back the way it was on this tick: every position the
     * recording touches, at its last change up to here, or as it was to begin
     * with. Starting from the originals is what puts a blown-up wall back on a
     * seek backwards.
     */
    private void rebuildWorld(int tick) {
        if (originals.isEmpty()) return;
        Map<Location, BlockData> state = new LinkedHashMap<>(originals);
        for (ReplayMark mark : marks) {
            if (mark.tick() > tick) break;
            if (ReplayMark.RESET.equals(mark.kind())) {
                state = new LinkedHashMap<>(originals);
                continue;
            }
            if (!ReplayMark.BLOCK.equals(mark.kind())) continue;
            Location at = WorldMarks.blockAt(anchorAt(mark.tick()), mark.data());
            if (at != null) state.put(at, WorldMarks.blockData(mark.data()));
        }
        showBlocks(state);
    }

    /** Puts back what this playback drew, and only that. */
    private void clearWorld() {
        if (shown.isEmpty()) return;
        List<Location> touched = new ArrayList<>(shown.keySet());
        shown.clear();
        if (solid) {
            Map<Location, BlockData> back = new LinkedHashMap<>();
            for (Location at : touched) {
                BlockData was = originals.get(at);
                if (was != null) back.put(at, was);
            }
            write(back);
            return;
        }
        // Not the module's clear(viewer), which takes away every fake block any
        // plugin has shown that player: somebody else's outline would go too.
        scheduler.run(() -> {
            for (Player viewer : viewers) {
                if (viewer != null && viewer.isOnline()) ReplayRuntime.fakeBlocks().clear(viewer, touched);
            }
        });
    }

    // -------------------------------------------------------------------- api

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
        pendingSeek = Math.clamp(tick, 0, Math.max(0, replay.frames() - 1));
    }

    @Override
    public void step(int ticks) {
        if (stopped) return;
        paused = true;
        seek(tick() + ticks);
    }

    @Override
    public int tick() {
        // A seek asked for and not drawn yet is where it is about to be, so two
        // skips in a row add up instead of both starting from the same frame.
        int wanted = pendingSeek;
        return wanted != NO_SEEK ? wanted : Math.max(0, rendered);
    }

    @Override
    public int frames() {
        return replay.frames();
    }

    @Override
    public @NotNull Replay replay() {
        return replay;
    }

    @Override
    public int scene() {
        return replay.sceneAt(tick());
    }

    @Override
    public @NotNull Location anchor(int scene) {
        return anchors[Math.clamp(scene, 0, anchors.length - 1)].clone();
    }

    @Override
    public void onScene(@NotNull IntConsumer listener) {
        this.onScene = listener;
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
    public void reveal(boolean reveal) {
        this.revealing = reveal;
        // Every body's state is sent again on the next frame.
        for (Body body : bodies) {
            if (body != null) body.state = -1;
        }
    }

    @Override
    public void follow(@Nullable UUID actor) {
        pov = actor == null ? -1 : indexOf(actor);
    }

    @Override
    public @Nullable UUID following() {
        int index = pov;
        return index < 0 ? null : replay.actors().get(index).id();
    }

    @Override
    public @Nullable Location locationOf(@NotNull UUID actor) {
        int index = indexOf(actor);
        if (index < 0 || stopped) return null;
        MotionTrack track = replay.tracks().get(index);
        int at = tick();
        return track.present(at) ? place(track, at) : null;
    }

    @Override
    public @Nullable Location placeOf(@NotNull ReplayMark mark) {
        return mark.data() == null ? null : WorldMarks.placeAt(anchorAt(mark.tick()), mark.data());
    }

    @Override
    public @Nullable String textOf(@NotNull ReplayMark mark) {
        return mark.data() == null ? null : WorldMarks.placeText(mark.data());
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
        if (Thread.currentThread() == drawing) {
            // Asked from inside a frame, by a listener run inline: the frame
            // cleans up as it ends rather than this waiting for itself.
            cleanupOwed = true;
            return;
        }
        // Waits for a frame being drawn rather than racing it: the bodies it is
        // about to move are the ones being taken away.
        while (!busy.compareAndSet(false, true)) Thread.onSpinWait();
        try {
            clean();
        } finally {
            busy.set(false);
        }
    }

    /** Takes everything this playback drew away again. Holds the frame lock. */
    private void clean() {
        cleanupOwed = false;
        for (int index = 0; index < bodies.length; index++) remove(index);
        if (povSent >= 0) {
            for (Player viewer : viewers) {
                if (viewer != null && viewer.isOnline()) ReplayPackets.camera(viewer, viewer.getEntityId());
            }
        }
        if (handOf >= 0) giveBack();
        clearWorld();
        ReplayRuntime.forget(this);
    }

    @Override
    public boolean isPlaying() {
        return !stopped;
    }

    private int indexOf(UUID actor) {
        List<ReplayActor> actors = replay.actors();
        for (int index = 0; index < actors.size(); index++) {
            if (actors.get(index).id().equals(actor)) return index;
        }
        return -1;
    }

    private static int slotIndex(@Nullable EquipmentSlot slot) {
        if (slot == null) return -1;
        for (int index = 0; index < Sampler.SLOTS.length; index++) {
            if (Sampler.SLOTS[index] == slot) return index;
        }
        return -1;
    }

    /** One equipment change, already read back into an item. */
    private record Worn(int tick, int slot, ItemStack item) {
    }

    /** Getting on something, or off it when the vehicle is -1. */
    private record Riding(int tick, int vehicle) {
    }

    /** What one body is on the viewers' screens right now. */
    private static final class Body {

        final int id;
        UUID profile;
        double x;
        double y;
        double z;
        byte yaw;
        byte pitch;
        byte head;
        boolean onGround;
        int steps;
        int state = -1;
        int vehicle = -1;
        int deadSent = -1;
        boolean drawn;
        double walked;
        final ItemStack[] worn = new ItemStack[Sampler.SLOTS.length];

        Body(int id) {
            this.id = id;
        }

        void moveTo(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.steps = 0;
        }
    }
}
