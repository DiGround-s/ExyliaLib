package net.exylia.lib.camera.internal;

import net.exylia.lib.camera.CameraHandle;
import net.exylia.lib.camera.CameraShot;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Owns every camera on the server and moves them all from one place.
 *
 * <h2>Nobody may be left looking through one</h2>
 * A camera is a packet, so the server has no record of it and nothing else will
 * clean it up. The failure is worse than a display left on a client: a player
 * whose view is not given back is not looking at a stray effect, they are
 * looking out of an entity that no longer exists, unable to see their own body,
 * and no part of the server will notice. The life is therefore held here rather
 * than by whoever asked for the shot, and the only ways out are the shot ending,
 * somebody stopping it, the player leaving, the plugin being disabled and the
 * server stopping. There is no sixth.
 *
 * <p>One player looks through one camera. Asking for a second while a first is
 * playing ends the first, because two shots fighting over the same eyes is the
 * one bug in this module a player cannot get themselves out of.
 *
 * <h2>One timer, not one per shot</h2>
 * Every live camera sits in one queue that a single timer walks, like the
 * display module's. Packets need no main thread and the whole path was solved
 * before the shot started, so the timer is asynchronous and never touches the
 * world.
 */
@ApiStatus.Internal
public final class CameraRuntime {

    /** Positions land on ticks, so the driver runs on ticks. */
    private static final long DRIVER_PERIOD_TICKS = 1L;

    private static final Object LOCK = new Object();

    private static final ConcurrentLinkedQueue<LiveCamera> LIVE = new ConcurrentLinkedQueue<>();

    /** Who is looking through what, so a second shot can take over from a first. */
    private static final Map<UUID, LiveCamera> FILMING = new ConcurrentHashMap<>();

    private static TaskScheduler scheduler;
    private static Logger logger = Logger.getLogger("ExyliaLib");
    private static LongSupplier clock = System::currentTimeMillis;
    /**
     * Left unset until {@code init}, and that is not tidiness.
     *
     * <p>A field initialised to a method reference on the packet class loads
     * that class when this one is first touched, which on a server with no
     * PacketEvents is a {@code NoClassDefFoundError} thrown out of a static
     * initialiser — before the check for whether PacketEvents is there has had
     * a chance to run.
     */
    private static CameraSink sink;
    private static TaskHandle driver;
    private static boolean available;
    private static boolean warned;

    private CameraRuntime() {
    }

    /**
     * Wires the module to the runtime. Called by ExyliaLib at startup.
     *
     * @param plugin the plugin whose scheduler drives cameras
     */
    public static void init(Plugin plugin) {
        synchronized (LOCK) {
            scheduler = Tasks.of(plugin);
            logger = plugin.getLogger();
            sink = CameraPackets.INSTANCE;
            available = CameraPackets.ready();
            if (driver != null) {
                driver.cancel();
            }
            driver = scheduler.runAsyncTimer(DRIVER_PERIOD_TICKS, DRIVER_PERIOD_TICKS,
                    CameraRuntime::tick);
        }
    }

    /** Swaps the clock and the sink. For tests. */
    static void testHooks(LongSupplier testClock, CameraSink testSink) {
        synchronized (LOCK) {
            clock = testClock == null ? System::currentTimeMillis : testClock;
            sink = testSink;
            available = testSink != null;
        }
    }

    /**
     * Whether a camera can be shown at all.
     *
     * <p>It is packets and nothing else: there is no fallback worth pretending
     * about, and a plugin that tried one would be moving a real player instead.
     */
    public static boolean isSupported() {
        return available;
    }

    /** How many shots are playing, for diagnostics. */
    public static int active() {
        return LIVE.size();
    }

    /**
     * Plays one shot.
     *
     * <p>Called from the thread that owns the subject: the whole path is solved
     * here, and solving it reads the world.
     *
     * @param plugin  whose shot it is
     * @param shot    the choreography
     * @param subject where it films, and which way that faces
     * @param viewers who looks through it
     * @return the handle, or {@code null} when there is nothing to play
     */
    public static CameraHandle play(Plugin plugin, CameraShot shot, Location subject,
                                    List<Player> viewers) {
        if (!available) {
            warnOnce(plugin.getName());
            return null;
        }
        if (viewers.isEmpty() || shot.isEmpty() || subject.getWorld() == null) {
            return null;
        }
        List<Player> watching = new ArrayList<>(viewers.size());
        for (Player viewer : viewers) {
            if (viewer != null && viewer.isOnline()) {
                // Whatever they were looking through, they are not any more:
                // ended before this shot takes the same eyes, so the one being
                // replaced still finds them to give back.
                stop(viewer);
                watching.add(viewer);
            }
        }
        if (watching.isEmpty()) {
            return null;
        }
        LiveCamera camera = new LiveCamera(plugin, List.copyOf(watching), sink.newEntityId(),
                CameraPath.of(shot, subject), shot.loops(), clock.getAsLong());
        for (Player viewer : watching) {
            FILMING.put(viewer.getUniqueId(), camera);
        }
        camera.start(sink);
        LIVE.add(camera);
        return camera;
    }

    /** Ends whatever one player is looking through, if anything. */
    public static void stop(Player player) {
        LiveCamera camera = FILMING.get(player.getUniqueId());
        if (camera != null) {
            stop(camera);
        }
    }

    /** Whether this player is looking through a camera right now. */
    public static boolean isFilming(Player player) {
        return FILMING.containsKey(player.getUniqueId());
    }

    /** Ends one shot for everybody watching it. */
    static void stop(LiveCamera camera) {
        camera.end(sink);
        forget(camera);
        LIVE.remove(camera);
    }

    /** Ends every shot one plugin is playing. Called when it is disabled. */
    public static void release(String pluginName) {
        for (Iterator<LiveCamera> cameras = LIVE.iterator(); cameras.hasNext(); ) {
            LiveCamera camera = cameras.next();
            if (camera.owner().equals(pluginName)) {
                camera.end(sink);
                forget(camera);
                cameras.remove();
            }
        }
    }

    /** Ends every shot on the server, on shutdown. */
    public static void releaseAll() {
        synchronized (LOCK) {
            if (driver != null) {
                driver.cancel();
                driver = null;
            }
        }
        for (LiveCamera camera : LIVE) {
            camera.end(sink);
        }
        LIVE.clear();
        FILMING.clear();
    }

    private static void forget(LiveCamera camera) {
        for (Player viewer : camera.viewers()) {
            FILMING.remove(viewer.getUniqueId(), camera);
        }
    }

    /**
     * Moves every camera that has somewhere to be.
     *
     * <p>A viewer who has gone offline is not waited for: their client has
     * nothing of ours left on it, but the shot they were the only viewer of has
     * to end anyway, or their entry sits in the map until the server stops and
     * the next player to ask for a shot is told they are already filming.
     */
    private static void tick() {
        if (LIVE.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        for (Iterator<LiveCamera> cameras = LIVE.iterator(); cameras.hasNext(); ) {
            LiveCamera camera = cameras.next();
            try {
                if (camera.advance(sink, now)) {
                    forget(camera);
                    cameras.remove();
                }
            } catch (Throwable failed) {
                // A shot that throws still has somebody's eyes. Ending it is the
                // only safe thing left to do with it, and it must not take the
                // rest of the queue down with it.
                logger.warning("A camera from " + camera.owner()
                        + " could not be moved and was ended: " + failed);
                try {
                    camera.end(sink);
                } catch (Throwable alsoFailed) {
                    logger.warning("It could not be ended either: " + alsoFailed);
                }
                forget(camera);
                cameras.remove();
            }
        }
    }

    /** Says once, per plugin, that this server cannot show a camera at all. */
    private static void warnOnce(String owner) {
        if (warned) {
            return;
        }
        warned = true;
        logger.warning(owner + " asked for a camera, but this server has no PacketEvents."
                + " Cameras are packets and nothing else, so nothing was shown.");
    }
}
