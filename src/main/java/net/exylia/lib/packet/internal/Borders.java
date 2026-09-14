package net.exylia.lib.packet.internal;

import net.exylia.lib.packet.VirtualBorder;
import net.exylia.lib.packet.WorldBorders;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * One plugin's world borders.
 *
 * <p>Who sees which border is shared by every plugin in {@link #SEEN}: a client
 * holds a single border, so the record of it is single too.
 */
final class Borders implements WorldBorders {

    /** viewer -> the border they see, whichever plugin made it. */
    static final Map<UUID, Border> SEEN = new ConcurrentHashMap<>();

    // Vanilla's defaults, so a border nobody tuned warns and hurts like the real one.
    static final int WARNING_BLOCKS = 5;
    static final int WARNING_SECONDS = 15;
    static final double DAMAGE_PER_BLOCK = 0.2;
    static final double DAMAGE_BUFFER = 5;
    /** The widest border the client accepts. */
    static final double MAX_SIZE = 59_999_968;
    /** Ticks between damage checks: a player's hurt cooldown, so no hit is lost to it. */
    private static final long CHECK_TICKS = 10;

    private final Plugin plugin;
    private final Set<Border> created = ConcurrentHashMap.newKeySet();

    Borders(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull VirtualBorder create(@NotNull Location center, double size) {
        World world = center.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("The centre of a border needs a world.");
        }
        checkSize(size);
        Border border = new Border(world, new Shape(center.getX(), center.getZ(), size, size, 0, 0,
                WARNING_BLOCKS, WARNING_SECONDS, DAMAGE_PER_BLOCK, DAMAGE_BUFFER));
        created.add(border);
        return border;
    }

    @Override
    public @Nullable VirtualBorder seenBy(@NotNull Player viewer) {
        return SEEN.get(viewer.getUniqueId());
    }

    /** Removes every border this plugin made. */
    void release() {
        for (Border border : new ArrayList<>(created)) {
            border.remove();
        }
    }

    // ------------------------------------------------------------------
    // For the runtime and the packet listener
    // ------------------------------------------------------------------

    /** Whether anybody sees a border of ours, asked before a packet is looked at. */
    static boolean drawsAny() {
        return !SEEN.isEmpty();
    }

    /**
     * Whether the world's own border must be kept from this viewer.
     *
     * <p>Only while they stand in the world of the border they see: in any
     * other world, that world's border is the one they should get.
     */
    static boolean replaces(@Nullable Player viewer) {
        if (viewer == null) {
            return false;
        }
        Border border = SEEN.get(viewer.getUniqueId());
        return border != null && border.world.equals(viewer.getWorld());
    }

    /** Forgets a player who left. */
    static void forget(Player player) {
        Border border = SEEN.remove(player.getUniqueId());
        if (border != null) {
            border.drop(player.getUniqueId());
        }
    }

    /**
     * Tells a player which border is theirs again, a tick after a respawn or a
     * world change.
     *
     * <p>Both reset the client's border, and the listener may have kept the
     * world's own from them while it still read the old world.
     */
    static void resend(Player player) {
        Border border = SEEN.get(player.getUniqueId());
        if (border != null) {
            Tasks.of(border.plugin()).runAtEntityLater(player, 1L, () -> {
                if (SEEN.get(player.getUniqueId()) == border) {
                    border.sendTo(player);
                }
            });
        }
    }

    static void shutdown() {
        SEEN.clear();
    }

    private static void checkSize(double size) {
        if (!(size >= 1 && size <= MAX_SIZE)) {
            throw new IllegalArgumentException(
                    "A border is 1 to " + (long) MAX_SIZE + " blocks wide, not " + size + ".");
        }
    }

    private static void checkNotNegative(double value, String what) {
        if (!(value >= 0)) {
            throw new IllegalArgumentException(what + " cannot be negative: " + value);
        }
    }

    /** Sends the world's own border, on the player's thread. */
    private static void sendReal(Player viewer) {
        PacketSink out = PacketRuntime.sink();
        if (out == null || !viewer.isOnline()) {
            return;
        }
        WorldBorder real = viewer.getWorld().getWorldBorder();
        Location center = real.getCenter();
        // A resize the world is in the middle of is sent as where it stands:
        // the API tells where a border is, not where it is going.
        out.border(viewer, center.getX(), center.getZ(), real.getSize(), real.getSize(), 0,
                real.getWarningDistance(), real.getWarningTime());
    }

    // ------------------------------------------------------------------

    /**
     * Where a border is and where it is going, as one immutable value.
     *
     * <p>A resize is kept as its endpoints and its clock rather than advanced
     * by a task: the width at any moment is worked out when asked, the same
     * way each client works it out on its own screen.
     */
    record Shape(double x, double z, double from, double to, long start, long millis,
                 int warningBlocks, int warningSeconds, double perBlock, double buffer) {

        double sizeAt(long now) {
            long elapsed = now - start;
            if (millis <= 0 || elapsed >= millis) {
                return to;
            }
            if (elapsed <= 0) {
                return from;
            }
            return from + (to - from) * elapsed / millis;
        }

        long remaining(long now) {
            return millis <= 0 ? 0 : Math.max(0, start + millis - now);
        }

        /** How far outside the edge a position is; negative inside. */
        double beyond(double px, double pz, long now) {
            return Math.max(Math.abs(px - x), Math.abs(pz - z)) - sizeAt(now) / 2;
        }

        /** The damage vanilla deals that far outside, or 0 for none. */
        int damage(double beyond) {
            double past = beyond - buffer;
            if (past <= 0 || perBlock <= 0) {
                return 0;
            }
            return Math.max(1, (int) Math.floor(past * perBlock));
        }

        Shape movedTo(double newX, double newZ) {
            return new Shape(newX, newZ, from, to, start, millis, warningBlocks, warningSeconds, perBlock, buffer);
        }

        Shape resizedTo(double size, long over, long now) {
            return new Shape(x, z, sizeAt(now), size, now, over, warningBlocks, warningSeconds, perBlock, buffer);
        }

        Shape warning(int blocks, int seconds) {
            return new Shape(x, z, from, to, start, millis, blocks, seconds, perBlock, buffer);
        }

        Shape damage(double newPerBlock, double newBuffer) {
            return new Shape(x, z, from, to, start, millis, warningBlocks, warningSeconds, newPerBlock, newBuffer);
        }
    }

    // ------------------------------------------------------------------

    /** One border. */
    final class Border implements VirtualBorder {

        final World world;
        private volatile Shape shape;
        private volatile boolean removed;
        /** Who sees it, and the damage check running for each. */
        private final Map<UUID, TaskHandle> viewers = new ConcurrentHashMap<>();

        Border(World world, Shape shape) {
            this.world = world;
            this.shape = shape;
        }

        Plugin plugin() {
            return plugin;
        }

        Shape shape() {
            return shape;
        }

        @Override
        public @NotNull World world() {
            return world;
        }

        @Override
        public @NotNull Location center() {
            Shape current = shape;
            return new Location(world, current.x(), 0, current.z());
        }

        @Override
        public void center(double x, double z) {
            update(current -> current.movedTo(x, z), true);
        }

        @Override
        public double size() {
            return shape.sizeAt(System.currentTimeMillis());
        }

        @Override
        public void size(double size) {
            size(size, Duration.ZERO);
        }

        @Override
        public void size(double size, @NotNull Duration over) {
            checkSize(size);
            if (over.isNegative()) {
                throw new IllegalArgumentException("A resize cannot take a negative time: " + over);
            }
            long now = System.currentTimeMillis();
            update(current -> current.resizedTo(size, over.toMillis(), now), true);
        }

        @Override
        public void warning(int blocks, int seconds) {
            checkNotNegative(blocks, "Warning blocks");
            checkNotNegative(seconds, "Warning seconds");
            update(current -> current.warning(blocks, seconds), true);
        }

        @Override
        public void damage(double perBlock, double buffer) {
            checkNotNegative(perBlock, "Damage per block");
            checkNotNegative(buffer, "Damage buffer");
            // Nothing to send: the client is never told how the server hurts.
            update(current -> current.damage(perBlock, buffer), false);
        }

        @Override
        public boolean contains(@NotNull Location at) {
            return world.equals(at.getWorld())
                    && shape.beyond(at.getX(), at.getZ(), System.currentTimeMillis()) <= 0;
        }

        @Override
        public void show(@NotNull Player viewer) {
            if (removed || PacketRuntime.sink() == null) {
                return;
            }
            UUID id = viewer.getUniqueId();
            Border previous = SEEN.put(id, this);
            if (previous == this) {
                return;
            }
            if (previous != null) {
                previous.drop(id);
            }
            viewers.put(id, Tasks.of(plugin).runAtEntityTimer(viewer, CHECK_TICKS, CHECK_TICKS,
                    handle -> hurtIfOutside(viewer, handle)));
            Tasks.of(plugin).runAtEntity(viewer, () -> sendTo(viewer));
        }

        @Override
        public void hide(@NotNull Player viewer) {
            UUID id = viewer.getUniqueId();
            if (!SEEN.remove(id, this)) {
                return;
            }
            drop(id);
            Tasks.of(plugin).runAtEntity(viewer, () -> sendReal(viewer));
        }

        @Override
        public @NotNull Collection<Player> viewers() {
            List<Player> online = new ArrayList<>();
            for (UUID id : viewers.keySet()) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    online.add(player);
                }
            }
            return online;
        }

        @Override
        public void remove() {
            removed = true;
            created.remove(this);
            for (UUID id : new ArrayList<>(viewers.keySet())) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    hide(player);
                } else {
                    SEEN.remove(id, this);
                    drop(id);
                }
            }
        }

        /** Stops this border's bookkeeping for a viewer, without sending anything. */
        void drop(UUID id) {
            TaskHandle check = viewers.remove(id);
            if (check != null) {
                check.cancel();
            }
        }

        /** Sends a viewer this border, or their world's own when they are elsewhere. On their thread. */
        void sendTo(Player viewer) {
            if (world.equals(viewer.getWorld())) {
                send(viewer, shape, System.currentTimeMillis());
            } else {
                sendReal(viewer);
            }
        }

        private void send(Player viewer, Shape current, long now) {
            PacketSink out = PacketRuntime.sink();
            if (out != null) {
                out.border(viewer, current.x(), current.z(), current.sizeAt(now), current.to(),
                        current.remaining(now), current.warningBlocks(), current.warningSeconds());
            }
        }

        /**
         * Applies a change and, when the client draws it, tells every viewer.
         *
         * <p>Synchronised so two changes at once cannot each start from the
         * shape before the other; readers only ever see a whole shape.
         */
        private synchronized void update(UnaryOperator<Shape> change, boolean visible) {
            Shape next = change.apply(shape);
            shape = next;
            if (!visible) {
                return;
            }
            long now = System.currentTimeMillis();
            for (UUID id : viewers.keySet()) {
                Player viewer = Bukkit.getPlayer(id);
                // The world is read off the player's thread, which is a field
                // read; a viewer caught mid world change is resent afterwards.
                if (viewer != null && world.equals(viewer.getWorld())) {
                    send(viewer, next, now);
                }
            }
        }

        private void hurtIfOutside(Player viewer, TaskHandle handle) {
            if (SEEN.get(viewer.getUniqueId()) != this) {
                handle.cancel();
                return;
            }
            if (viewer.isDead() || !world.equals(viewer.getWorld())) {
                return;
            }
            Shape current = shape;
            Location at = viewer.getLocation();
            int hurt = current.damage(current.beyond(at.getX(), at.getZ(), System.currentTimeMillis()));
            if (hurt > 0) {
                viewer.damage(hurt, DamageSource.builder(DamageType.OUTSIDE_BORDER).build());
            }
        }
    }
}
