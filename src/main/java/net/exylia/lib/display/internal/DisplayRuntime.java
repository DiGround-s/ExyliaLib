package net.exylia.lib.display.internal;

import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.DisplaySettings;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
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

    /**
     * How often a tracked display re-reads who is near it, in driver ticks.
     *
     * <p>Half a second: a player walking at sprint speed covers under three
     * blocks in that time, which is well inside any radius worth tracking, and
     * the scan is a walk over the world's players.
     */
    private static final int TRACK_EVERY_TICKS = 10;

    private static int trackCountdown;

    /**
     * Who a tracked display is for: everyone within a radius of where it
     * stands, narrowed by a test, re-read as players move.
     *
     * @param radius how far away it can be seen, in blocks
     * @param filter who may see it, or {@code null} for anybody in range
     */
    public record Audience(double radius, java.util.function.Predicate<Player> filter) {

        /**
         * Who should see a display at that place, right now.
         *
         * @param where the place
         * @return the players in range who pass the filter
         */
        public List<Player> at(Location where) {
            org.bukkit.World world = where.getWorld();
            if (world == null) {
                return List.of();
            }
            double limit = radius * radius;
            List<Player> present = new ArrayList<>();
            for (Player online : world.getPlayers()) {
                // The world first: distanceSquared throws across worlds, and the
                // list can hold a player who has just changed world.
                if (!online.isOnline() || !world.equals(online.getWorld())
                        || online.getLocation().distanceSquared(where) > limit) {
                    continue;
                }
                if (filter != null && !filter.test(online)) {
                    continue;
                }
                present.add(online);
            }
            return present;
        }
    }

    private static final Object LOCK = new Object();

    private static final ConcurrentLinkedQueue<LiveDisplay> LIVE = new ConcurrentLinkedQueue<>();

    /**
     * Displays multiplied by the players who can see them.
     *
     * <p>The number that reaches the network, and therefore the one worth
     * capping. Counting displays alone would let thirty players in an arena
     * cost thirty times what the same effect costs in an empty world, which is
     * exactly the case the cap exists for.
     */
    private static final AtomicInteger VIEWER_LOAD = new AtomicInteger();

    private static volatile DisplaySettings limits = new DisplaySettings();

    /** Logged once a minute at most: a flood is one message, not ten thousand. */
    private static final long DROP_REPORT_MS = 60_000L;

    private static final AtomicInteger DROPPED = new AtomicInteger();
    private static volatile long lastReportedAt;

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

    /**
     * Applies the server's ceiling on display effects.
     *
     * <p>Called by ExyliaLib at startup and on every reload of
     * {@code displays.yml}.
     */
    public static void apply(DisplaySettings settings) {
        limits = settings == null ? new DisplaySettings() : settings;
    }

    /**
     * How many points of one shape may be drawn.
     *
     * <p>Read by the sequence compiler, which is where a shape's size is
     * decided once rather than on every play.
     */
    public static int maxPerEffect() {
        int max = limits.maxPerEffect();
        return max > 0 ? max : Integer.MAX_VALUE;
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
        return show(owner, model, motion, at, viewers, 0);
    }

    /**
     * Shows one display, optionally seated on something that moves.
     *
     * <p>A seated display is carried by the client along with its vehicle, so
     * an effect that follows a player costs the same as one that does not: one
     * packet when it is seated, and nothing at all while the player runs.
     * Reading the player's position every tick to move it would be both an
     * order of magnitude more packets and, on Folia, a read of an entity this
     * thread does not own.
     *
     * @param vehicleId the entity it rides, or {@code 0} to stand still
     */
    public static DisplayHandle show(String owner, DisplayModel model, DisplayMotion motion,
                                     Location at, List<Player> viewers, int vehicleId) {
        if (!available) {
            warnOnce(owner);
            return null;
        }
        if (viewers.isEmpty() || ids == null) {
            return null;
        }
        // Refused before the packets are built, not after: the point of the
        // ceiling is the bytes that never leave. What a player sees when it is
        // reached is an effect missing its tail, which is the right thing to
        // lose when the alternative is everyone's tick rate.
        int cost = viewers.size();
        int max = limits.maxViewerDisplays();
        if (max > 0 && VIEWER_LOAD.get() + cost > max) {
            reportDrop();
            return null;
        }
        VIEWER_LOAD.addAndGet(cost);
        LiveDisplay display = new LiveDisplay(owner, ids.next(), model, motion,
                viewers, clock.getAsLong(), vehicleId);
        display.spawn(sink, at);
        LIVE.add(display);
        if (vehicleId != 0) {
            seated(vehicleId, display, viewers);
        }
        return display;
    }

    /**
     * Shows one display to whoever is near it, re-read as players move.
     *
     * <p>Unlike a fixed list, nobody being in range now is not a reason to
     * draw nothing: the display keeps its clock and is shown, in the pose it
     * has reached, to whoever walks up.
     */
    public static DisplayHandle showTracked(String owner, DisplayModel model, DisplayMotion motion,
                                            Location at, Audience audience) {
        if (!available) {
            warnOnce(owner);
            return null;
        }
        if (ids == null) {
            return null;
        }
        List<Player> viewers = audience.at(at);
        int cost = viewers.size();
        int max = limits.maxViewerDisplays();
        if (max > 0 && VIEWER_LOAD.get() + cost > max) {
            reportDrop();
            return null;
        }
        VIEWER_LOAD.addAndGet(cost);
        LiveDisplay display = new LiveDisplay(owner, ids.next(), model, motion,
                List.copyOf(viewers), clock.getAsLong(), 0);
        display.audience(audience);
        display.spawn(sink, at);
        LIVE.add(display);
        return display;
    }

    /** Re-reads who should see one tracked display and tells the clients that changed. */
    static void retrack(LiveDisplay display, List<Player> present) {
        int max = limits.maxViewerDisplays();
        int room = max > 0 ? Math.max(0, max - VIEWER_LOAD.get()) : Integer.MAX_VALUE;
        int delta = display.track(sink, clock.getAsLong(), present, room);
        if (delta != 0) {
            VIEWER_LOAD.addAndGet(delta);
        }
    }

    /** Asks every tracked display's place who is near it, on the thread that owns that place. */
    private static void scanAudiences() {
        for (LiveDisplay display : LIVE) {
            Audience audience = display.audience();
            if (audience == null || !display.isShowing()) {
                continue;
            }
            Location where = display.location();
            if (where == null) {
                continue;
            }
            Runnable scan = () -> {
                try {
                    retrack(display, audience.at(where));
                } catch (RuntimeException ignored) {
                    // A world unloading under the scan: the next one is half a
                    // second away and the display's own life still ends it.
                }
            };
            TaskScheduler tasks = scheduler;
            if (tasks == null) {
                scan.run();
            } else {
                tasks.runAtLocation(where, scan);
            }
        }
    }

    /**
     * Sets off a firework for these viewers only, as packets.
     *
     * <p>Not part of the budget: nothing stays on any client past this call.
     *
     * @return whether it was sent; {@code false} without PacketEvents
     */
    public static boolean firework(java.util.List<Player> viewers, Location at,
                                   java.util.List<org.bukkit.FireworkEffect> effects) {
        DisplaySink target = sink;
        if (!available || target == null || ids == null) {
            return false;
        }
        if (!viewers.isEmpty() && !effects.isEmpty()) {
            target.firework(viewers, ids.next(), at, effects);
        }
        return true;
    }

    /** Draws another model on a display that is already up. */
    static void restyle(LiveDisplay display, DisplayModel model) {
        DisplaySink target = sink;
        if (target != null && model != null) {
            display.restyle(target, model);
        }
    }

    /** Where a display is on screen right now. */
    static DisplayKeyframe poseOf(LiveDisplay display) {
        return display.poseAt(clock.getAsLong());
    }

    /** Plays another motion on a display from where it is now. */
    static void retarget(LiveDisplay display, DisplayMotion motion, boolean keepLife) {
        DisplaySink target = sink;
        if (target != null && motion != null) {
            display.retarget(target, clock.getAsLong(), motion, keepLife);
        }
    }

    /** Gives a finished display's share of the budget back. */
    private static void released(LiveDisplay display) {
        VIEWER_LOAD.addAndGet(-display.viewerCost());
        if (display.vehicleId() != 0) {
            unseated(display.vehicleId(), display);
        }
    }

    // ------------------------------------------------------------------
    // Riding
    // ------------------------------------------------------------------

    /**
     * Who is riding what.
     *
     * <p>Kept because the packet that seats a passenger carries the vehicle's
     * <em>whole</em> list: two effects riding one player have to be sent
     * together or the second takes the first off them.
     */
    private static final Map<Integer, Seats> SEATS = new ConcurrentHashMap<>();

    /** The displays riding one entity, and who has been told about them. */
    private static final class Seats {
        private final Set<LiveDisplay> riders =
                java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
        private volatile List<Player> viewers = List.of();
        private volatile boolean changed;
    }

    private static void seated(int vehicleId, LiveDisplay display, List<Player> viewers) {
        Seats seats = SEATS.computeIfAbsent(vehicleId, id -> new Seats());
        seats.riders.add(display);
        seats.viewers = viewers;
        seats.changed = true;
    }

    private static void unseated(int vehicleId, LiveDisplay display) {
        Seats seats = SEATS.get(vehicleId);
        if (seats == null) {
            return;
        }
        seats.riders.remove(display);
        seats.changed = true;
    }

    /**
     * Sends the seat lists that changed this tick.
     *
     * <p>Once per tick rather than once per display: a ring of twelve blades
     * riding one player is one packet, not twelve of growing length.
     */
    private static void flushSeats(DisplaySink target) {
        if (SEATS.isEmpty()) {
            return;
        }
        for (Iterator<Map.Entry<Integer, Seats>> entries = SEATS.entrySet().iterator();
             entries.hasNext(); ) {
            Map.Entry<Integer, Seats> entry = entries.next();
            Seats seats = entry.getValue();
            if (!seats.changed) {
                continue;
            }
            // Cleared before the list is read, never after: an effect seated
            // while this runs sets it again and is sent on the next tick. The
            // other order loses that change and leaves a piece unmounted.
            seats.changed = false;
            // Collected rather than counted into a sized array: another thread
            // seats an effect while this iterates, and a size read a moment
            // before the walk is one index short of the walk itself.
            List<LiveDisplay> riding = new ArrayList<>(seats.riders);
            int[] passengers = new int[riding.size()];
            for (int at = 0; at < passengers.length; at++) {
                passengers[at] = riding.get(at).entityId();
            }
            target.mount(seats.viewers, entry.getKey(), passengers);
            if (passengers.length == 0 && !seats.changed && seats.riders.isEmpty()) {
                // Told once that it carries nobody, then forgotten: an entry
                // per player who ever wore an effect is a leak. Checked again
                // after the send, because a seat taken in between belongs to an
                // entry that must not be dropped.
                entries.remove();
            }
        }
    }

    /**
     * Says that effects are being dropped, at most once a minute.
     *
     * <p>The condition that causes one drop causes thousands, so the count is
     * carried in the message rather than the message repeated.
     */
    private static void reportDrop() {
        int dropped = DROPPED.incrementAndGet();
        long now = clock.getAsLong();
        if (now - lastReportedAt < DROP_REPORT_MS) {
            return;
        }
        lastReportedAt = now;
        DROPPED.set(0);
        logger.warning(dropped + " display effect(s) were dropped because the server reached"
                + " max-viewer-displays (" + limits.maxViewerDisplays() + ")."
                + " Raise it in plugins/ExyliaLib/displays.yml, or use smaller effects.");
    }

    /** Displays multiplied by their viewers, right now. For diagnostics. */
    public static int viewerLoad() {
        return VIEWER_LOAD.get();
    }

    /** Removes one display now. */
    static void remove(LiveDisplay display) {
        display.destroy(sink);
        if (LIVE.remove(display)) {
            released(display);
        }
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
                released(display);
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
            LiveDisplay display = live.next();
            display.destroy(sink);
            live.remove();
            released(display);
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
                    released(display);
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
                released(display);
            }
        }
        flushSeats(target);
        if (++trackCountdown >= TRACK_EVERY_TICKS) {
            trackCountdown = 0;
            scanAudiences();
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
