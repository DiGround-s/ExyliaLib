package net.exylia.lib.npc.internal;

import net.exylia.lib.npc.NpcHandle;
import net.exylia.lib.npc.NpcModel;
import net.exylia.lib.npc.NpcMotion;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Owns every NPC on the server and takes each one away when its time is up.
 *
 * <h2>Nothing can be left behind</h2>
 * An NPC is a packet, so the server has no record of it and nothing else will
 * clean it up: one whose removal is skipped stands there until that player
 * relogs, wearing somebody's name. The life is therefore held here rather than
 * by whoever created it, and the only way out of the queue is being destroyed
 * &mdash; because the life ended, because the plugin was disabled, or because
 * the server is stopping.
 *
 * <p>The driver runs once a second rather than once a tick: unlike a display,
 * an NPC has nothing to send between its first packet and its last, so a tick
 * timer would be twenty checks a second to find out that nothing has changed.
 */
@ApiStatus.Internal
public final class NpcRuntime {

    /**
     * Every tick, because a body that moves is driven from here.
     *
     * <p>A still one costs three comparisons a tick, and a moving one is what
     * makes the difference between a prop and something that happened.
     */
    private static final long DRIVER_PERIOD_TICKS = 1L;

    /** Long enough to be a moment, short enough not to be scenery. */
    public static final long DEFAULT_LIFE_MILLIS = 5000L;

    /** A ceiling, so a mistyped file cannot leave a crowd standing about. */
    private static final long MAX_LIFE_MILLIS = 120_000L;

    private static final Object LOCK = new Object();

    private static final ConcurrentLinkedQueue<LiveNpc> LIVE = new ConcurrentLinkedQueue<>();

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
     * a chance to run. The hologram module has always been written this way;
     * this one had not been.
     */
    private static EntityIds ids;
    private static NpcSink sink;
    private static TaskHandle driver;
    private static boolean available;
    private static boolean warned;

    /** Where entity ids come from, so tests do not need a server. */
    @FunctionalInterface
    public interface EntityIds {

        /** An id that cannot collide with a real entity. */
        int next();
    }

    private NpcRuntime() {
    }

    /**
     * Wires the module to the runtime. Called by ExyliaLib at startup.
     *
     * @param plugin the plugin whose scheduler drives NPCs
     */
    public static void init(Plugin plugin) {
        synchronized (LOCK) {
            scheduler = Tasks.of(plugin);
            logger = plugin.getLogger();
            ids = NpcPackets::newEntityId;
            sink = NpcPackets.INSTANCE;
            available = NpcPackets.ready();
            if (driver != null) {
                driver.cancel();
            }
            driver = scheduler.runAsyncTimer(DRIVER_PERIOD_TICKS, DRIVER_PERIOD_TICKS,
                    NpcRuntime::tick);
        }
    }

    /** Swaps the clock, the ids and the sink. For tests. */
    static void testHooks(LongSupplier testClock, EntityIds testIds, NpcSink testSink) {
        synchronized (LOCK) {
            clock = testClock == null ? System::currentTimeMillis : testClock;
            ids = testIds;
            sink = testSink;
            available = testSink != null;
        }
    }

    /** Where packets go. */
    static NpcSink sink() {
        return sink;
    }

    /**
     * Whether NPCs can be shown at all.
     *
     * <p>They are packets and nothing else, so without PacketEvents there is no
     * fallback worth pretending about.
     */
    public static boolean isSupported() {
        return available;
    }

    /**
     * Shows one NPC.
     *
     * @param owner      the name of the plugin it belongs to
     * @param model      who it looks like
     * @param motion     what it does once it is there
     * @param at         where it stands, facing the location's yaw
     * @param lifeMillis how long before it goes
     * @param viewers    who sees it; taken as given and not copied again
     * @return the handle, or {@code null} when nobody can see it or the server
     *         has no PacketEvents
     */
    public static NpcHandle show(String owner, NpcModel model, NpcMotion motion, Location at,
                                 long lifeMillis, List<Player> viewers) {
        if (!available) {
            warnOnce(owner);
            return null;
        }
        if (viewers.isEmpty() || ids == null) {
            return null;
        }
        LiveNpc npc = new LiveNpc(owner, ids.next(), model, motion, viewers, at.clone(),
                clock.getAsLong(), Math.clamp(lifeMillis, 200L, MAX_LIFE_MILLIS));
        npc.spawn(sink);
        LIVE.add(npc);
        return npc;
    }

    /** Removes one NPC now. */
    static void remove(LiveNpc npc) {
        npc.destroy(sink);
        LIVE.remove(npc);
    }

    /**
     * Takes away everything one plugin is showing.
     *
     * <p>Called when it is disabled. Its NPCs are on clients that will keep
     * drawing them for as long as those clients are connected.
     *
     * @param pluginName the plugin
     */
    public static void release(String pluginName) {
        for (Iterator<LiveNpc> live = LIVE.iterator(); live.hasNext(); ) {
            LiveNpc npc = live.next();
            if (npc.owner().equals(pluginName)) {
                npc.destroy(sink);
                live.remove();
            }
        }
    }

    /** Takes away everything, on shutdown. */
    public static void releaseAll() {
        synchronized (LOCK) {
            if (driver != null) {
                driver.cancel();
                driver = null;
            }
        }
        for (Iterator<LiveNpc> live = LIVE.iterator(); live.hasNext(); ) {
            live.next().destroy(sink);
            live.remove();
        }
    }

    /** How many NPCs are on screen, for diagnostics. */
    public static int active() {
        return LIVE.size();
    }

    /** How many NPCs one plugin is showing. */
    public static int active(String pluginName) {
        int total = 0;
        for (LiveNpc npc : LIVE) {
            if (npc.owner().equals(pluginName)) {
                total++;
            }
        }
        return total;
    }

    /** Forgets what has run out of time. */
    static void tick() {
        if (LIVE.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        NpcSink target = sink;
        if (target == null) {
            return;
        }
        for (Iterator<LiveNpc> live = LIVE.iterator(); live.hasNext(); ) {
            LiveNpc npc = live.next();
            try {
                if (npc.expired(target, now)) {
                    live.remove();
                }
            } catch (RuntimeException failure) {
                // One that throws must not keep the ones behind it standing.
                live.remove();
                logger.warning("An NPC failed while being removed: " + failure);
            }
        }
    }

    private static void warnOnce(String owner) {
        if (warned) {
            return;
        }
        warned = true;
        logger.warning(owner + " asked for an NPC, but PacketEvents is not installed;"
                + " NPCs are packet-only and will not be shown.");
    }
}
