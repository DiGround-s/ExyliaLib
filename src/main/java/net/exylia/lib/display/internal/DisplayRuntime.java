package net.exylia.lib.display.internal;

import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Owns every display on the server and moves them all from one place.
 *
 * <h2>One timer, not one per display</h2>
 * A kill effect can put forty displays on screen at once, and a busy arena can
 * have ten of those running together. Scheduling a task per display would be
 * four hundred scheduler entries for work that is, per display per tick, a
 * comparison of two longs. Instead every live display sits in one queue that a
 * single timer walks.
 *
 * <h2>Nothing can be left behind</h2>
 * A display is a packet, so the server has no record of it and nothing will
 * clean it up: a display whose removal is skipped stays on the client until it
 * relogs. The life is therefore held by the runtime rather than by whoever
 * created it, and the only way out of the queue is being destroyed &mdash;
 * whether that is because the motion ended, the plugin was disabled or the
 * server is stopping.
 *
 * <p>Packets need no main thread, so the timer is asynchronous, like the
 * hologram module's.
 */
@ApiStatus.Internal
public final class DisplayRuntime {

    /** Poses land on ticks, so the driver runs on ticks. */
    private static final long DRIVER_PERIOD_TICKS = 1L;

    private static final Object LOCK = new Object();

    private static final ConcurrentLinkedQueue<LiveDisplay> LIVE = new ConcurrentLinkedQueue<>();

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
    private static DisplaySink sink;
    private static TaskHandle driver;
    private static boolean available;
    private static boolean warned;

    /** Where entity ids come from, so tests do not need a server. */
    @FunctionalInterface
    public interface EntityIds {

        /** An id that cannot collide with a real entity. */
        int next();
    }

    private DisplayRuntime() {
    }

    /**
     * Wires the module to the runtime. Called by ExyliaLib at startup.
     *
     * @param plugin the plugin whose scheduler drives displays
     */
    public static void init(Plugin plugin) {
        synchronized (LOCK) {
            scheduler = Tasks.of(plugin);
            logger = plugin.getLogger();
            ids = DisplayPackets::newEntityId;
            sink = DisplayPackets.INSTANCE;
            available = DisplayPackets.ready();
            if (driver != null) {
                driver.cancel();
            }
            driver = scheduler.runAsyncTimer(DRIVER_PERIOD_TICKS, DRIVER_PERIOD_TICKS,
                    DisplayRuntime::tick);
        }
    }

    /** Swaps the clock, the ids and the sink. For tests. */
    static void testHooks(LongSupplier testClock, EntityIds testIds, DisplaySink testSink) {
        synchronized (LOCK) {
            clock = testClock == null ? System::currentTimeMillis : testClock;
            ids = testIds;
            sink = testSink;
            available = testSink != null;
        }
    }

    /**
     * Whether displays can be shown at all.
     *
     * <p>They are packets and nothing else, so without PacketEvents there is no
     * fallback worth pretending about.
     */
    public static boolean isSupported() {
        return available;
    }

    /**
     * Shows one display.
     *
     * @param owner   the name of the plugin it belongs to
     * @param model   what it draws
     * @param motion  how it moves
     * @param at      where the client puts it before the motion offsets it
     * @param viewers who sees it; taken as given and not copied again
     * @return the handle, or {@code null} when displays are unsupported
     */
    public static DisplayHandle show(String owner, DisplayModel model, DisplayMotion motion,
                                     Location at, List<Player> viewers) {
        if (!available) {
            warnOnce(owner);
            return null;
        }
        if (viewers.isEmpty() || ids == null) {
            return null;
        }
        LiveDisplay display = new LiveDisplay(owner, ids.next(), model, motion,
                viewers, clock.getAsLong());
        display.spawn(sink, at);
        LIVE.add(display);
        return display;
    }

    /** Removes one display now. */
    static void remove(LiveDisplay display) {
        display.destroy(sink);
        LIVE.remove(display);
    }

    /**
     * Destroys everything one plugin is showing.
     *
     * <p>Called when it is disabled. Its displays are on clients that will keep
     * drawing them for as long as those clients are connected, so this is not
     * housekeeping that can wait for the next restart.
     *
     * @param pluginName the plugin
     */
    public static void release(String pluginName) {
        for (Iterator<LiveDisplay> live = LIVE.iterator(); live.hasNext(); ) {
            LiveDisplay display = live.next();
            if (display.owner().equals(pluginName)) {
                display.destroy(sink);
                live.remove();
            }
        }
    }

    /** Destroys everything, on shutdown. */
    public static void releaseAll() {
        synchronized (LOCK) {
            if (driver != null) {
                driver.cancel();
                driver = null;
            }
        }
        for (Iterator<LiveDisplay> live = LIVE.iterator(); live.hasNext(); ) {
            live.next().destroy(sink);
            live.remove();
        }
    }

    /** How many displays are on screen, for diagnostics. */
    public static int active() {
        return LIVE.size();
    }

    /** How many displays one plugin is showing. */
    public static int active(String pluginName) {
        int total = 0;
        for (LiveDisplay display : LIVE) {
            if (display.owner().equals(pluginName)) {
                total++;
            }
        }
        return total;
    }

    /**
     * Sends what is due and forgets what is finished.
     *
     * <p>Walks the whole queue every tick. That is one comparison per display
     * for the ones with nothing to do, which is nearly all of them nearly all
     * of the time, and it is what makes a display's removal impossible to lose:
     * there is no separate bookkeeping to fall out of step with.
     */
    static void tick() {
        if (LIVE.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        DisplaySink target = sink;
        if (target == null) {
            return;
        }
        List<LiveDisplay> broken = null;
        for (Iterator<LiveDisplay> live = LIVE.iterator(); live.hasNext(); ) {
            LiveDisplay display = live.next();
            try {
                if (display.advance(target, now)) {
                    live.remove();
                }
            } catch (RuntimeException failure) {
                // One display that throws must not stop the others from being
                // moved, and must never stop the ones behind it from being
                // removed: that is how a client ends up with a permanent sword
                // in the air.
                if (broken == null) {
                    broken = new ArrayList<>(1);
                }
                broken.add(display);
                live.remove();
            }
        }
        if (broken != null) {
            for (LiveDisplay display : broken) {
                try {
                    display.destroy(target);
                } catch (RuntimeException ignored) {
                    // Already unreachable; there is nothing further to try.
                }
            }
            logger.warning("A display effect failed while being drawn and was removed.");
        }
    }

    private static void warnOnce(String owner) {
        if (warned) {
            return;
        }
        warned = true;
        logger.warning(owner + " asked for a display effect, but PacketEvents is not installed;"
                + " display effects are packet-only and will not be shown.");
    }
}
