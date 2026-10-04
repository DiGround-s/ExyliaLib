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

    private int[] ticks = new int[32];
    private UUID[] worlds = new UUID[32];
    private double[] x = new double[32];
    private double[] y = new double[32];
    private double[] z = new double[32];
    private float[] yaw = new float[32];
    private float[] pitch = new float[32];
    private int[] flags = new int[32];
    private float[] health = new float[32];
    private int head;
    private int size;
    private long sampledAt;

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
        long now = System.nanoTime();
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
            if (tick - ticks[last] < HEARTBEAT && world.equals(worlds[last]) && x[last] == px
                    && y[last] == py && z[last] == pz && yaw[last] == aYaw && pitch[last] == aPitch
                    && flags[last] == aFlags && health[last] == hp) {
                return false;
            }
        }
        sampledAt = now;
        append(tick, world, px, py, pz, aYaw, aPitch, aFlags, hp);
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
        append(tick, worlds[last], x[last], y[last], z[last], yaw[last], pitch[last], 0, 0f);
    }

    private void append(int tick, UUID world, double px, double py, double pz,
                        float aYaw, float aPitch, int aFlags, float hp) {
        if (size == ticks.length) grow();
        int at = index(size);
        ticks[at] = tick;
        worlds[at] = world;
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
        if (Objects.equals(item, wearing[slot])) return;
        wearing[slot] = item == null ? null : item.clone();
        worn.add(new Worn(tick, slot, wearing[slot]));
    }

    /** Drops everything older than the window, keeping one frame before it. */
    synchronized void prune(int oldest) {
        while (size > 1 && ticks[index(1)] <= oldest) {
            head = (head + 1) % ticks.length;
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
            frames.add(new Frame(ticks[at], worlds[at], x[at], y[at], z[at], yaw[at], pitch[at],
                    flags[at], health[at]));
        }
        return frames;
    }

    /** Every equipment change kept, in order. */
    synchronized List<Worn> worn() {
        return new ArrayList<>(worn);
    }

    private int index(int offset) {
        return (head + offset) % ticks.length;
    }

    private void grow() {
        int old = ticks.length;
        int capacity = old * 2;
        int[] order = new int[size];
        for (int index = 0; index < size; index++) order[index] = (head + index) % old;
        int[] newTicks = new int[capacity];
        UUID[] newWorlds = new UUID[capacity];
        double[] newX = new double[capacity];
        double[] newY = new double[capacity];
        double[] newZ = new double[capacity];
        float[] newYaw = new float[capacity];
        float[] newPitch = new float[capacity];
        int[] newFlags = new int[capacity];
        float[] newHealth = new float[capacity];
        for (int index = 0; index < size; index++) {
            int from = order[index];
            newTicks[index] = ticks[from];
            newWorlds[index] = worlds[from];
            newX[index] = x[from];
            newY[index] = y[from];
            newZ[index] = z[from];
            newYaw[index] = yaw[from];
            newPitch[index] = pitch[from];
            newFlags[index] = flags[from];
            newHealth[index] = health[from];
        }
        ticks = newTicks;
        worlds = newWorlds;
        x = newX;
        y = newY;
        z = newZ;
        yaw = newYaw;
        pitch = newPitch;
        flags = newFlags;
        health = newHealth;
        head = 0;
    }

    /** One frame, copied out. */
    record Frame(int tick, UUID world, double x, double y, double z, float yaw, float pitch,
                 int flags, float health) {
    }

    /** One equipment change. */
    record Worn(int tick, int slot, ItemStack item) {
    }
}
