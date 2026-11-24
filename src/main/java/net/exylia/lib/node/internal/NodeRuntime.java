package net.exylia.lib.node.internal;

import net.exylia.lib.camera.Viewpoints;
import net.exylia.lib.interaction.Click;
import net.exylia.lib.node.ClientNode;
import net.exylia.lib.node.ClientNodes;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.logging.Logger;

/**
 * Owns every client node on the server: who sees which, and where clicks go.
 *
 * <p>One async timer checks, four times a second, which nodes each viewer
 * stands near enough to see, and sends packets only to those who crossed the
 * edge. Nodes never tick: what changes them is the plugin calling them.
 */
public final class NodeRuntime {

    /** How often visibility is re-checked. */
    static final long PERIOD_TICKS = 5L;

    /** How much further than the range a node stays sent, so standing on the edge does not flicker it. */
    static final double MARGIN = 4.0;

    /**
     * How far from a viewer's eyes a click may be, to the node's centre. Past
     * the client's own reach (3 blocks, 5 in creative) with room for lag; a
     * click from further is a forged packet.
     */
    static final double REACH = 7.5;

    private static final Object LOCK = new Object();
    private static final Map<String, Owner> OWNERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Viewer> VIEWERS = new ConcurrentHashMap<>();
    /** Each node under both of its entity ids, which is how a click finds it. */
    private static final Map<Integer, NodeImpl> BY_ID = new ConcurrentHashMap<>();
    /** When each player last pressed one of their nodes, read by swings that should not count twice. */
    private static final Map<UUID, Long> PRESSED = new ConcurrentHashMap<>();

    private static TaskScheduler scheduler;
    private static Logger logger = Logger.getLogger("ExyliaLib");
    private static IntSupplier ids;
    private static NodeSink sink;
    private static boolean available;
    private static TaskHandle driver;
    private static Object listener;

    private NodeRuntime() {
    }

    /** One plugin's handler and range. */
    public static final class Owner {
        final String name;
        final TaskScheduler tasks;
        private volatile ClientNodes.Handler handler;
        private volatile double range = 32.0;

        Owner(String name, TaskScheduler tasks) {
            this.name = name;
            this.tasks = tasks;
        }

        public void handler(ClientNodes.Handler handler) {
            this.handler = handler;
        }

        public void range(double blocks) {
            this.range = Math.max(1.0, blocks);
        }
    }

    /** One viewer's nodes, kept with the player so the pass needs no lookup. */
    private record Viewer(Player player, Set<NodeImpl> nodes) {
    }

    /** Wires the runtime to ExyliaLib's scheduler and to PacketEvents, when it is there. */
    public static void init(Plugin lib) {
        synchronized (LOCK) {
            scheduler = Tasks.of(lib);
            logger = lib.getLogger();
            if (!net.exylia.lib.internal.PacketEventsPresent.installed()) {
                java.util.concurrent.atomic.AtomicInteger counter =
                        new java.util.concurrent.atomic.AtomicInteger(Integer.MAX_VALUE / 2 + 1_000_000);
                ids = counter::incrementAndGet;
                sink = null;
                available = false;
                return;
            }
            ids = NodePackets::newEntityId;
            sink = NodePackets.INSTANCE;
            available = NodePackets.ready();
            if (available && listener == null) {
                listener = NodePackets.listen();
            }
        }
    }

    /** Swaps the scheduler, the id source and the packets. For tests. */
    static void testHooks(TaskScheduler testScheduler, IntSupplier testIds, NodeSink testSink) {
        synchronized (LOCK) {
            scheduler = testScheduler;
            ids = testIds;
            sink = testSink;
            available = true;
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    public static Owner owner(Plugin plugin) {
        return OWNERS.computeIfAbsent(plugin.getName(), name -> new Owner(name, Tasks.of(plugin)));
    }

    static NodeSink sink() {
        NodeSink current = sink;
        return current != null ? current : NoSink.INSTANCE;
    }

    static void later(long ticks, Runnable task) {
        TaskScheduler current = scheduler;
        if (current != null) {
            current.runAsyncLater(ticks, task);
        }
    }

    public static ClientNode create(Owner owner, Player viewer, Location at, Object look) {
        if (at.getWorld() == null) {
            throw new IllegalArgumentException("A node needs a world");
        }
        IntSupplier source = ids;
        if (source == null) {
            throw new IllegalStateException("Client nodes are used before ExyliaLib started");
        }
        NodeImpl node = new NodeImpl(owner, viewer, at, look, source.getAsInt(), source.getAsInt());
        synchronized (LOCK) {
            VIEWERS.computeIfAbsent(viewer.getUniqueId(),
                    id -> new Viewer(viewer, ConcurrentHashMap.newKeySet())).nodes().add(node);
            BY_ID.put(node.displayId, node);
            BY_ID.put(node.hitboxId, node);
            ensureDriver();
        }
        return node;
    }

    static void remove(NodeImpl node) {
        node.destroy();
        unregister(node);
    }

    private static void unregister(NodeImpl node) {
        synchronized (LOCK) {
            BY_ID.remove(node.displayId);
            BY_ID.remove(node.hitboxId);
            Viewer viewer = VIEWERS.get(node.viewer.getUniqueId());
            if (viewer != null && viewer.nodes().remove(node) && viewer.nodes().isEmpty()) {
                VIEWERS.remove(node.viewer.getUniqueId());
            }
            stopDriverIfIdle();
        }
    }

    public static int clear(Owner owner, Player player) {
        Viewer viewer = VIEWERS.get(player.getUniqueId());
        if (viewer == null) {
            return 0;
        }
        int removed = 0;
        for (NodeImpl node : List.copyOf(viewer.nodes())) {
            if (node.owner == owner) {
                remove(node);
                removed++;
            }
        }
        return removed;
    }

    /** Removes a plugin's nodes from every screen. */
    public static void release(String pluginName) {
        Owner owner = OWNERS.remove(pluginName);
        if (owner == null) {
            return;
        }
        for (NodeImpl node : List.copyOf(BY_ID.values())) {
            if (node.owner == owner) {
                remove(node);
            }
        }
    }

    /** Forgets a leaving player's nodes. No packets: the client is gone. */
    public static void forget(Player player) {
        PRESSED.remove(player.getUniqueId());
        Viewer viewer = VIEWERS.remove(player.getUniqueId());
        if (viewer == null) {
            return;
        }
        synchronized (LOCK) {
            for (NodeImpl node : viewer.nodes()) {
                node.retire();
                BY_ID.remove(node.displayId);
                BY_ID.remove(node.hitboxId);
            }
            stopDriverIfIdle();
        }
    }

    /** Removes every node. ExyliaLib calls this as it shuts down. */
    public static void removeEverything() {
        for (NodeImpl node : List.copyOf(BY_ID.values())) {
            remove(node);
        }
        OWNERS.clear();
        synchronized (LOCK) {
            if (listener != null) {
                NodePackets.stopListening(listener);
                listener = null;
            }
        }
    }

    /** Whether an entity id is one of a node's. */
    static void pressed(Player player) {
        PRESSED.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public static boolean pressedWithin(Player player, long millis) {
        Long at = PRESSED.get(player.getUniqueId());
        return at != null && System.currentTimeMillis() - at <= millis;
    }

    static boolean owns(int entityId) {
        return BY_ID.containsKey(entityId);
    }

    /**
     * A click that went through something in front (a click-through mob): it
     * lands on the nearest of the player's nodes along their line of sight,
     * within reach. Nothing happens when the line meets no node.
     */
    public static void clickedInSight(Player player, Click click) {
        Viewer viewer = VIEWERS.get(player.getUniqueId());
        if (viewer == null) {
            return;
        }
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector dir = eye.getDirection();
        NodeImpl best = null;
        double bestT = REACH;
        for (NodeImpl node : viewer.nodes()) {
            if (node.isRemoved() || !node.shown() || eye.getWorld() != node.at.getWorld()) {
                continue;
            }
            double t = hit(eye, dir, node);
            if (t >= 0 && t < bestT) {
                bestT = t;
                best = node;
            }
        }
        if (best != null) {
            pressed(player);
            clicked(player, best.hitboxId, click);
        }
    }

    /** How far along the ray the node's click box starts, or -1 when the ray misses it. */
    static double hit(Location eye, org.bukkit.util.Vector dir, NodeImpl node) {
        float[] box = NodeBox.of(node);
        double half = box[0] / 2.0;
        double cx = node.isBlock() ? node.at.getX() + 0.5 : node.at.getX();
        double cz = node.isBlock() ? node.at.getZ() + 0.5 : node.at.getZ();
        double minY = node.isBlock() ? node.at.getY() : node.at.getY() - box[1] / 2.0;
        double[] lo = {cx - half, minY, cz - half};
        double[] hi = {cx + half, minY + box[1], cz + half};
        double[] o = {eye.getX(), eye.getY(), eye.getZ()};
        double[] d = {dir.getX(), dir.getY(), dir.getZ()};
        double near = 0, far = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-9) {
                if (o[i] < lo[i] || o[i] > hi[i]) return -1;
                continue;
            }
            double a = (lo[i] - o[i]) / d[i], b = (hi[i] - o[i]) / d[i];
            near = Math.max(near, Math.min(a, b));
            far = Math.min(far, Math.max(a, b));
            if (near > far) return -1;
        }
        return near;
    }

    /**
     * A player clicked one of a node's entities. Only its viewer counts, and
     * only from within reach; the handler runs on the viewer's thread.
     */
    static void clicked(Player player, int entityId, Click click) {
        NodeImpl node = BY_ID.get(entityId);
        if (node == null || !node.viewer.getUniqueId().equals(player.getUniqueId()) || !inReach(player, node)) {
            return;
        }
        ClientNodes.Handler handler = node.owner.handler;
        if (handler == null) {
            return;
        }
        node.owner.tasks.runAtEntity(player, () -> {
            if (node.isRemoved()) {
                return;
            }
            try {
                handler.clicked(player, node, click);
            } catch (Throwable t) {
                logger.warning(node.owner.name + " failed on a node click: " + t);
            }
        });
    }

    static boolean inReach(Player player, NodeImpl node) {
        Location eye = player.getEyeLocation();
        if (eye.getWorld() != node.at.getWorld()) {
            return false;
        }
        double dx = eye.getX() - node.centerX();
        double dy = eye.getY() - node.centerY();
        double dz = eye.getZ() - node.centerZ();
        return dx * dx + dy * dy + dz * dz <= REACH * REACH;
    }

    /** The viewer's client dropped every entity (respawn, world change): resend on the next pass. */
    static void respawned(Player player) {
        Viewer viewer = VIEWERS.get(player.getUniqueId());
        if (viewer != null) {
            viewer.nodes().forEach(NodeImpl::dropped);
        }
    }

    // ------------------------------------------------------------------
    // The visibility pass
    // ------------------------------------------------------------------

    private static void ensureDriver() {
        if (driver == null && scheduler != null) {
            driver = scheduler.runAsyncTimer(PERIOD_TICKS, PERIOD_TICKS, NodeRuntime::pass);
        }
    }

    private static void stopDriverIfIdle() {
        if (BY_ID.isEmpty() && driver != null) {
            driver.cancel();
            driver = null;
        }
    }

    static void pass() {
        for (Viewer viewer : VIEWERS.values()) {
            try {
                show(viewer);
            } catch (Throwable t) {
                logger.warning("Could not update client nodes for " + viewer.player().getName() + ": " + t);
            }
        }
    }

    private static void show(Viewer viewer) {
        Location from = Viewpoints.of(viewer.player());
        World world = from.getWorld();
        double x = from.getX();
        double y = from.getY();
        double z = from.getZ();
        List<NodeImpl> nodes = new ArrayList<>(viewer.nodes());
        for (NodeImpl node : nodes) {
            double range = node.owner.range;
            boolean shown = node.shown();
            double edge = shown ? range + MARGIN : range;
            boolean near = node.at.getWorld() == world
                    && square(node.centerX() - x) + square(node.centerY() - y) + square(node.centerZ() - z)
                    <= edge * edge;
            if (near != shown) {
                node.show(near);
            }
        }
    }

    private static double square(double value) {
        return value * value;
    }

    /** What nodes send to while PacketEvents is missing: nothing. */
    private static final class NoSink implements NodeSink {
        static final NoSink INSTANCE = new NoSink();

        @Override public void spawn(NodeImpl node) { }
        @Override public void despawn(NodeImpl node) { }
        @Override public void look(NodeImpl node) { }
        @Override public void glow(NodeImpl node) { }
        @Override public void scale(NodeImpl node, float size, int ticks) { }
    }
}
