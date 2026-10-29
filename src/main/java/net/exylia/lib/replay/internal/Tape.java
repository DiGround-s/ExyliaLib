package net.exylia.lib.replay.internal;

import net.exylia.lib.replay.ReplayActor;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The last stretch of one entity's movement, kept by the black box.
 *
 * <p>A rolling buffer rather than a growing one: anything older than the window
 * is dropped as new frames come in. A frame is only written when something
 * changed, plus a heartbeat every {@link #HEARTBEAT} ticks so a stretch with no
 * frame at all can be told apart from one where nothing moved: a cow standing
 * in a field costs two frames a second, not twenty.
 *
 * <p>Written by the one region that owns the entity, read by whoever cuts a
 * recording out of it. Both go through the tape's own lock, which nobody else
 * ever contends for.
 */
@ApiStatus.Internal
final class Tape {

    /** A frame is written at least this often while the entity is sampled. */
    static final int HEARTBEAT = 10;

    final UUID id;
    final ReplayActor actor;
    final boolean player;
    final boolean living;

    /** The last tick anybody sampled it on, written or not: a frame is read once a tick. */
    volatile int seen = Integer.MIN_VALUE;

    /**
     * Positions and rotations as the file keeps them rather than as Bukkit
     * hands them over: a thousandth of a block, the same quantum the recording
     * is written in, so nothing a capture writes out is lost; and the world as
     * an index into {@link #PLACES} rather than a reference per frame. A frame
     * is twenty-seven bytes instead of forty-eight, and the black box holds a
     * minute of everybody.
     */
    private int[] ticks = new int[32];
    private char[] places = new char[32];
    private int[] x = new int[32];
    private int[] y = new int[32];
    private int[] z = new int[32];
    private short[] yaw = new short[32];
    private short[] pitch = new short[32];
    private int[] flags = new int[32];
    /** Half points, as the recording keeps them, up to the 127.5 it can show. */
    private byte[] health = new byte[32];
    private int head;
    private int size;
    private long sampledAt;
    /** The place of the last frame, so a body that stays put never looks it up. */
    private int lastPlace = -1;

    /**
     * The slots that changed since they were last read, one bit each, set by
     * the server's equipment event; and the tick everything was last read on.
     */
    volatile int dirty = (1 << Sampler.SLOTS.length) - 1;
    int wornAt = Integer.MIN_VALUE;

    private final List<Worn> worn = new ArrayList<>();
    private final ItemStack[] wearing = new ItemStack[Sampler.SLOTS.length];

    Tape(UUID id, ReplayActor actor, boolean player, boolean living) {
        this.id = id;
        this.actor = actor;
        this.player = player;
        this.living = living;
    }

    /**
     * Writes one tick, if it is new and something changed or a heartbeat is due.
     *
     * @return whether this tick had already been written, by another sampler
     */
    synchronized boolean put(int tick, UUID world, double px, double py, double pz,
                             float aYaw, float aPitch, int aFlags, float hp) {
        // Only Folia's wall clock needs it, and it is a system call per entity per tick.
        long now = ReplayClock.isWall() ? System.nanoTime() : 0L;
        int place = place(world, px, pz);
        int qx = offset(px);
        int qy = (int) Math.round(py * MotionTrack.SCALE);
        int qz = offset(pz);
        short qYaw = angle(aYaw);
        short qPitch = angle(aPitch);
        byte qHealth = (byte) Math.clamp(Math.round(hp * 2.0), 0, 255);
        if (size > 0) {
            int last = index(size - 1);
            if (ticks[last] >= tick) {
                // The same stamp a whole tick later: on Folia a region can read
                // the global counter twice in one of its phases. It is the next
                // tick, not this one again.
                if (ReplayClock.isWall() && ticks[last] == tick && now - sampledAt > Sampler.SAME_TICK_NANOS
                        && flags[last] != 0) {
                    tick = tick + 1;
                } else {
                    return true;
                }
            }
            if (tick - ticks[last] < HEARTBEAT && places[last] == place && x[last] == qx
                    && y[last] == qy && z[last] == qz && yaw[last] == qYaw && pitch[last] == qPitch
                    && flags[last] == aFlags && health[last] == qHealth) {
                return false;
            }
        }
        sampledAt = now;
        append(tick, (char) place, qx, qy, qz, qYaw, qPitch, aFlags, qHealth);
        return false;
    }

    /**
     * Writes that it is gone: removed, picked up, landed, logged out. Without
     * this the last frame would hold for a heartbeat, an arrow hanging in the
     * air for half a second after it hit.
     */
    synchronized void gone(int tick) {
        if (size == 0) return;
        int last = index(size - 1);
        if (flags[last] == 0 || ticks[last] > tick) return;
        if (ticks[last] == tick) {
            flags[last] = 0;
            return;
        }
        append(tick, places[last], x[last], y[last], z[last], yaw[last], pitch[last], 0, (byte) 0);
    }

    private void append(int tick, char place, int px, int py, int pz,
                        short aYaw, short aPitch, int aFlags, byte hp) {
        if (size == ticks.length) grow();
        int at = index(size);
        ticks[at] = tick;
        places[at] = place;
        x[at] = px;
        y[at] = py;
        z[at] = pz;
        yaw[at] = aYaw;
        pitch[at] = aPitch;
        flags[at] = aFlags;
        health[at] = hp;
        size++;
    }

    /** Writes a slot that changed since the last look. */
    synchronized void wear(int tick, int slot, ItemStack item) {
        wear(tick, slot, item, tick % Sampler.FULL_LOOK_EVERY == 0);
    }

    /**
     * Same, comparing the whole item when {@code full}: when the server says
     * the slot changed, or on the read once a second that catches what it
     * did not say.
     */
    synchronized void wear(int tick, int slot, ItemStack item, boolean full) {
        if (Sampler.sameLook(item, wearing[slot], full)) return;
        wearing[slot] = item == null ? null : item.clone();
        worn.add(new Worn(tick, slot, wearing[slot]));
    }

    /** Drops everything older than the window, keeping one frame before it. */
    synchronized void prune(int oldest) {
        while (size > 1 && ticks[index(1)] <= oldest) {
            head = (head + 1) & (ticks.length - 1);
            size--;
        }
        // Equipment: the newest change per slot before the window is what they
        // were wearing when it starts; anything it replaces goes.
        worn.removeIf(change -> change.tick <= oldest && isShadowed(change, oldest));
    }

    /** Whether a later change to the same slot, still before the window, replaces it. */
    private boolean isShadowed(Worn change, int oldest) {
        for (Worn later : worn) {
            if (later != change && later.slot == change.slot && later.tick > change.tick
                    && later.tick <= oldest) {
                return true;
            }
        }
        return false;
    }

    /** The tick of the newest frame, or {@code Integer.MIN_VALUE} when empty. */
    synchronized int lastTick() {
        return size == 0 ? Integer.MIN_VALUE : ticks[index(size - 1)];
    }

    /** A copy of every frame from one tick on, in order. */
    synchronized List<Frame> from(int tick) {
        List<Frame> frames = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            int at = index(index);
            boolean last = index == size - 1;
            // The frame before the window is kept: it is the state the window
            // starts in.
            if (!last && ticks[index(index + 1)] <= tick) continue;
            Place place = PLACES[places[at]];
            frames.add(new Frame(ticks[at], place.world, place.x + x[at] / MotionTrack.SCALE,
                    y[at] / MotionTrack.SCALE, place.z + z[at] / MotionTrack.SCALE,
                    yaw[at] * 360f / 65536f, pitch[at] * 360f / 65536f, flags[at],
                    (health[at] & 0xFF) / 2f));
        }
        return frames;
    }

    /** Every equipment change kept, in order. */
    synchronized List<Worn> worn() {
        return new ArrayList<>(worn);
    }

    /** The capacity is always a power of two: a mask rather than a division, several times a frame. */
    private int index(int offset) {
        return (head + offset) & (ticks.length - 1);
    }

    private void grow() {
        int capacity = ticks.length * 2;
        ticks = unwrap(ticks, capacity);
        places = unwrap(places, capacity);
        x = unwrap(x, capacity);
        y = unwrap(y, capacity);
        z = unwrap(z, capacity);
        yaw = unwrap(yaw, capacity);
        pitch = unwrap(pitch, capacity);
        flags = unwrap(flags, capacity);
        health = unwrap(health, capacity);
        head = 0;
    }

    /** One column laid out from its oldest frame, in a larger array. */
    private <A> A unwrap(A from, int capacity) {
        int old = java.lang.reflect.Array.getLength(from);
        @SuppressWarnings("unchecked")
        A to = (A) java.lang.reflect.Array.newInstance(from.getClass().getComponentType(), capacity);
        int first = Math.min(size, old - head);
        System.arraycopy(from, head, to, 0, first);
        System.arraycopy(from, 0, to, first, size - first);
        return to;
    }

    /** How many frames it holds. */
    synchronized int frames() {
        return size;
    }

    /** Roughly what it takes on the heap: the columns at their capacity, and the items it remembers. */
    synchronized long bytes() {
        return 160L + ticks.length * 27L + worn.size() * 96L;
    }

    // ---------------------------------------------------------------- places

    /** Side of the square a place covers, in blocks: a thousandth of it still fits an int. */
    static final int SQUARE = 1 << 21;

    /** A world and one square of it, shared by every tape: there are a handful on any server. */
    record Place(UUID world, int squareX, int squareZ, double x, double z) {
    }

    private static final java.util.Map<Place, Integer> PLACE_INDEX = new java.util.concurrent.ConcurrentHashMap<>();
    // ponytail: never shrinks and holds 65536 places; a server with more worlds
    // times two-million-block squares than that would need an int column.
    private static volatile Place[] PLACES = new Place[0];

    /** The place a position is in, remembered per tape: a lookup only when it changes square or world. */
    private int place(UUID world, double px, double pz) {
        int squareX = (int) Math.floor(px / SQUARE);
        int squareZ = (int) Math.floor(pz / SQUARE);
        int known = lastPlace;
        if (known >= 0) {
            Place place = PLACES[known];
            if (place.squareX == squareX && place.squareZ == squareZ && place.world.equals(world)) return known;
        }
        Place wanted = new Place(world, squareX, squareZ, (double) squareX * SQUARE, (double) squareZ * SQUARE);
        Integer index = PLACE_INDEX.get(wanted);
        if (index == null) index = register(wanted);
        lastPlace = index;
        return index;
    }

    private static synchronized int register(Place place) {
        Integer known = PLACE_INDEX.get(place);
        if (known != null) return known;
        Place[] grown = java.util.Arrays.copyOf(PLACES, PLACES.length + 1);
        int index = PLACES.length;
        if (index > Character.MAX_VALUE) throw new IllegalStateException("Too many places for the black box");
        grown[index] = place;
        PLACES = grown;
        PLACE_INDEX.put(place, index);
        return index;
    }

    /** A coordinate in thousandths of a block from the edge of its square. */
    private static int offset(double coordinate) {
        return (int) Math.round((coordinate - Math.floor(coordinate / SQUARE) * SQUARE) * MotionTrack.SCALE);
    }

    /** An angle in 65536 steps a turn: finer than the 256 the recording writes. */
    static short angle(float degrees) {
        return (short) Math.round(degrees * 65536f / 360f);
    }

    /** One frame, copied out. */
    record Frame(int tick, UUID world, double x, double y, double z, float yaw, float pitch,
                 int flags, float health) {
    }

    /** One equipment change. */
    record Worn(int tick, int slot, ItemStack item) {
    }
}
