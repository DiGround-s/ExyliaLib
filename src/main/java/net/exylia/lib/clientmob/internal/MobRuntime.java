package net.exylia.lib.clientmob.internal;

import net.exylia.lib.camera.Viewpoints;
import net.exylia.lib.clientmob.ClientMob;
import net.exylia.lib.clientmob.ClientMobs;
import net.exylia.lib.interaction.Click;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
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
 * Owns every client mob on the server: who sees which, who is walking, where
 * clicks go.
 *
 * <p>One async timer checks four times a second which mobs each viewer stands
 * near enough to see. A second async timer, every 2 ticks, exists only while
 * some mob is walking. Idle mobs cost nothing.
 */
public final class MobRuntime {

    static final long PERIOD_TICKS = 5L;
    static final long MOVE_PERIOD_TICKS = 2L;
    static final double MOVE_SECONDS = MOVE_PERIOD_TICKS / 20.0;
    static final double MARGIN = 4.0;
    /** How far from the viewer's eyes a click may be, to the mob's middle. */
    static final double REACH = 8.0;

    private static final Object LOCK = new Object();
    private static final Map<String, Owner> OWNERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Viewer> VIEWERS = new ConcurrentHashMap<>();
    private static final Map<Integer, MobImpl> BY_ID = new ConcurrentHashMap<>();
    private static final Set<MobImpl> MOVING = ConcurrentHashMap.newKeySet();
    /** Real mobs by their entity id, for clicks, and every one made, spawned or not. */
    private static final Map<Integer, RealMob> REAL_BY_ID = new ConcurrentHashMap<>();
    private static final Set<RealMob> REAL = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> REAL_UUIDS = ConcurrentHashMap.newKeySet();

    private static TaskScheduler scheduler;
    private static Logger logger = Logger.getLogger("ExyliaLib");
    private static IntSupplier ids;
    private static MobSink sink;
    private static boolean available;
    private static TaskHandle driver;
    private static TaskHandle moveDriver;
    private static Object listener;
    private static RealMobGuard guard;

    private MobRuntime() {
    }

    /** One plugin's handler and range. */
    public static final class Owner {
        final String name;
        final Plugin plugin;
        final TaskScheduler tasks;
        private volatile ClientMobs.Handler handler;
        private volatile double range = 32.0;

        Owner(Plugin plugin, TaskScheduler tasks) {
            this.name = plugin.getName();
            this.plugin = plugin;
            this.tasks = tasks;
        }

        public void handler(ClientMobs.Handler handler) {
            this.handler = handler;
        }

        public void range(double blocks) {
            this.range = Math.max(1.0, blocks);
        }
    }

    private record Viewer(Player player, Set<MobImpl> mobs) {
    }

    /** Wires the runtime to ExyliaLib's scheduler and to PacketEvents, when it is there. */
    public static void init(Plugin lib) {
        synchronized (LOCK) {
            scheduler = Tasks.of(lib);
            logger = lib.getLogger();
            if (guard == null) {
                guard = new RealMobGuard();
                lib.getServer().getPluginManager().registerEvents(guard, lib);
            }
            if (!net.exylia.lib.internal.PacketEventsPresent.installed()) {
                ids = IdPool.counter();
                sink = null;
                available = false;
                return;
            }
            ids = MobPackets::newEntityId;
            sink = MobPackets.INSTANCE;
            available = MobPackets.ready();
            if (available && listener == null) {
                listener = MobPackets.listen();
            }
        }
    }

    /** Swaps the scheduler, the id source and the packets. For tests. */
    static void testHooks(TaskScheduler testScheduler, IntSupplier testIds, MobSink testSink) {
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
        return OWNERS.computeIfAbsent(plugin.getName(), name -> new Owner(plugin, Tasks.of(plugin)));
    }

    static MobSink sink() {
        MobSink current = sink;
        return current != null ? current : NoSink.INSTANCE;
    }

    public static ClientMob create(Owner owner, Player viewer, EntityType type, Location at) {
        if (at.getWorld() == null) {
            throw new IllegalArgumentException("A client mob needs a world");
        }
        if (type == EntityType.PLAYER || !type.isSpawnable()) {
            throw new IllegalArgumentException(type + " cannot be a client mob");
        }
        IntSupplier source = ids;
        if (source == null) {
            throw new IllegalStateException("Client mobs are used before ExyliaLib started");
        }
        MobImpl mob = new MobImpl(owner, viewer, type, at.clone(), source.getAsInt());
        synchronized (LOCK) {
            VIEWERS.computeIfAbsent(viewer.getUniqueId(),
                    id -> new Viewer(viewer, ConcurrentHashMap.newKeySet())).mobs().add(mob);
            BY_ID.put(mob.id, mob);
            if (driver == null && scheduler != null) {
                driver = scheduler.runAsyncTimer(PERIOD_TICKS, PERIOD_TICKS, MobRuntime::pass);
            }
        }
        return mob;
    }

    public static ClientMob createReal(Owner owner, Player viewer, EntityType type, Location at) {
        if (at.getWorld() == null) {
            throw new IllegalArgumentException("A real mob needs a world");
        }
        Class<?> cls = type.getEntityClass();
        if (cls == null || !org.bukkit.entity.Mob.class.isAssignableFrom(cls) || !type.isSpawnable()) {
            throw new IllegalArgumentException(type + " cannot be a real mob");
        }
        if (scheduler == null) {
            throw new IllegalStateException("Real mobs are used before ExyliaLib started");
        }
        RealMob mob = new RealMob(owner, scheduler, viewer, type, at.clone());
        REAL.add(mob);
        return mob;
    }

    static void registerReal(RealMob mob, int entityId, UUID uuid) {
        REAL_BY_ID.put(entityId, mob);
        REAL_UUIDS.add(uuid);
    }

    static void unregister(RealMob mob) {
        REAL.remove(mob);
        org.bukkit.entity.LivingEntity body = mob.body();
        if (body != null) {
            REAL_BY_ID.remove(body.getEntityId());
            REAL_UUIDS.remove(body.getUniqueId());
        }
    }

    static boolean isReal(UUID entity) {
        return REAL_UUIDS.contains(entity);
    }

    static void sendStatus(Player viewer, int entityId, byte status) {
        if (available) MobPackets.statusTo(viewer, entityId, status);
    }

    static void sendTeam(Player viewer, UUID entity, Integer rgb) {
        if (available) MobPackets.teamTo(viewer, entity, rgb);
    }

    static void startMoving(MobImpl mob) {
        synchronized (LOCK) {
            MOVING.add(mob);
            if (moveDriver == null && scheduler != null) {
                moveDriver = scheduler.runAsyncTimer(MOVE_PERIOD_TICKS, MOVE_PERIOD_TICKS, MobRuntime::moveTick);
            }
        }
    }

    static void moveTick() {
        for (MobImpl mob : List.copyOf(MOVING)) {
            try {
                if (mob.advance(MOVE_SECONDS)) {
                    MOVING.remove(mob);
                    // A walk started on arrival, often from the finished walk's own future, keeps it moving.
                    if (mob.walking()) startMoving(mob);
                }
            } catch (Throwable t) {
                MOVING.remove(mob);
                logger.warning("Could not move a client mob: " + t);
            }
        }
        synchronized (LOCK) {
            if (MOVING.isEmpty() && moveDriver != null) {
                moveDriver.cancel();
                moveDriver = null;
            }
        }
    }

    static void remove(MobImpl mob) {
        mob.destroy();
        unregister(mob);
    }

    private static void unregister(MobImpl mob) {
        synchronized (LOCK) {
            BY_ID.remove(mob.id);
            MOVING.remove(mob);
            Viewer viewer = VIEWERS.get(mob.viewer.getUniqueId());
            if (viewer != null && viewer.mobs().remove(mob) && viewer.mobs().isEmpty()) {
                VIEWERS.remove(mob.viewer.getUniqueId());
            }
            stopDriversIfIdle();
        }
    }

    public static int clear(Owner owner, Player player) {
        int removed = 0;
        for (RealMob mob : List.copyOf(REAL)) {
            if (mob.owner == owner && mob.viewer.getUniqueId().equals(player.getUniqueId())) {
                mob.remove();
                removed++;
            }
        }
        Viewer viewer = VIEWERS.get(player.getUniqueId());
        if (viewer == null) {
            return removed;
        }
        for (MobImpl mob : List.copyOf(viewer.mobs())) {
            if (mob.owner == owner) {
                remove(mob);
                removed++;
            }
        }
        return removed;
    }

    public static void release(String pluginName) {
        Owner owner = OWNERS.remove(pluginName);
        if (owner == null) {
            return;
        }
        for (MobImpl mob : List.copyOf(BY_ID.values())) {
            if (mob.owner == owner) {
                remove(mob);
            }
        }
        for (RealMob mob : List.copyOf(REAL)) {
            if (mob.owner == owner) {
                mob.remove();
            }
        }
    }

    /** Forgets a leaving player's mobs. No packets: the client is gone. */
    public static void forget(Player player) {
        for (RealMob mob : List.copyOf(REAL)) {
            if (mob.viewer.getUniqueId().equals(player.getUniqueId())) {
                mob.retire();
                unregister(mob);
            }
        }
        Viewer viewer = VIEWERS.remove(player.getUniqueId());
        if (viewer == null) {
            return;
        }
        synchronized (LOCK) {
            for (MobImpl mob : viewer.mobs()) {
                mob.retire();
                BY_ID.remove(mob.id);
                MOVING.remove(mob);
            }
            stopDriversIfIdle();
        }
    }

    /** Removes every mob. ExyliaLib calls this as it shuts down. */
    public static void removeEverything() {
        for (MobImpl mob : List.copyOf(BY_ID.values())) {
            remove(mob);
        }
        for (RealMob mob : List.copyOf(REAL)) {
            mob.retire();
            unregister(mob);
        }
        OWNERS.clear();
        synchronized (LOCK) {
            if (listener != null) {
                MobPackets.stopListening(listener);
                listener = null;
            }
        }
    }

    static boolean owns(int entityId) {
        return BY_ID.containsKey(entityId) || REAL_BY_ID.containsKey(entityId);
    }

    /** A player clicked a mob. Only its viewer counts, from within reach; the handler runs on the viewer's thread. */
    static void clicked(Player player, int entityId, Click click) {
        RealMob real = REAL_BY_ID.get(entityId);
        if (real != null) {
            clickedReal(player, real, click);
            return;
        }
        MobImpl mob = BY_ID.get(entityId);
        if (mob == null || !mob.viewer.getUniqueId().equals(player.getUniqueId())) {
            return;
        }
        if (mob.clickThrough) {
            net.exylia.lib.node.internal.NodeRuntime.clickedInSight(player, click);
            return;
        }
        if (!inReach(player, mob)) {
            return;
        }
        ClientMobs.Handler handler = mob.owner.handler;
        if (handler == null) {
            return;
        }
        mob.owner.tasks.runAtEntity(player, () -> {
            if (mob.isRemoved()) {
                return;
            }
            try {
                handler.clicked(player, mob, click);
            } catch (Throwable t) {
                logger.warning(mob.owner.name + " failed on a mob click: " + t);
            }
        });
    }

    private static void clickedReal(Player player, RealMob mob, Click click) {
        if (!mob.viewer.getUniqueId().equals(player.getUniqueId())) {
            return;
        }
        if (mob.clickThrough) {
            net.exylia.lib.node.internal.NodeRuntime.clickedInSight(player, click);
            return;
        }
        Location at = mob.location();
        Location eye = player.getEyeLocation();
        if (eye.getWorld() != at.getWorld() || eye.distanceSquared(at.add(0, 1, 0)) > REACH * REACH) {
            return;
        }
        ClientMobs.Handler handler = mob.owner.handler;
        if (handler == null) {
            return;
        }
        mob.owner.tasks.runAtEntity(player, () -> {
            if (mob.isRemoved()) {
                return;
            }
            try {
                handler.clicked(player, mob, click);
            } catch (Throwable t) {
                logger.warning(mob.owner.name + " failed on a mob click: " + t);
            }
        });
    }

    static boolean inReach(Player player, MobImpl mob) {
        Location eye = player.getEyeLocation();
        if (eye.getWorld() != mob.world()) {
            return false;
        }
        double dx = eye.getX() - mob.x();
        double dy = eye.getY() - (mob.y() + 1.0);
        double dz = eye.getZ() - mob.z();
        return dx * dx + dy * dy + dz * dz <= REACH * REACH;
    }

    /** The viewer's client dropped every entity (respawn, world change): resend on the next pass. */
    static void respawned(Player player) {
        Viewer viewer = VIEWERS.get(player.getUniqueId());
        if (viewer != null) {
            viewer.mobs().forEach(MobImpl::dropped);
        }
    }

    private static void stopDriversIfIdle() {
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
                logger.warning("Could not update client mobs for " + viewer.player().getName() + ": " + t);
            }
        }
    }

    private static void show(Viewer viewer) {
        Location from = Viewpoints.of(viewer.player());
        World world = from.getWorld();
        for (MobImpl mob : new ArrayList<>(viewer.mobs())) {
            double range = mob.owner.range;
            boolean shown = mob.shown();
            double edge = shown ? range + MARGIN : range;
            double dx = mob.x() - from.getX();
            double dy = mob.y() - from.getY();
            double dz = mob.z() - from.getZ();
            boolean near = mob.world() == world && dx * dx + dy * dy + dz * dz <= edge * edge;
            if (near != shown) {
                mob.show(near);
            }
        }
    }

    private static final class NoSink implements MobSink {
        static final NoSink INSTANCE = new NoSink();

        @Override public void spawn(MobImpl mob) { }
        @Override public void despawn(MobImpl mob) { }
        @Override public void move(MobImpl mob, double dx, double dy, double dz) { }
        @Override public void teleport(MobImpl mob) { }
        @Override public void rotate(MobImpl mob) { }
        @Override public void swing(MobImpl mob) { }
        @Override public void status(MobImpl mob, byte status) { }
        @Override public void state(MobImpl mob) { }
        @Override public void glow(MobImpl mob) { }
    }
}
