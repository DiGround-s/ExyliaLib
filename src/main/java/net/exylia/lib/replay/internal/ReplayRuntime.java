package net.exylia.lib.replay.internal;

import net.exylia.lib.npc.internal.NpcRuntime;
import net.exylia.lib.packet.FakeBlocks;
import net.exylia.lib.packet.Packets;
import net.exylia.lib.replay.Replay;
import net.exylia.lib.replay.ReplayMark;
import net.exylia.lib.replay.ReplayPlayback;
import net.exylia.lib.replay.ReplayRecorder;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

/**
 * Owns every recording being made and every one being watched.
 *
 * <h2>One driver, not one each</h2>
 * Playbacks are walked by a single timer, the way NPCs are. A playback that is
 * paused, or running slowly enough that this tick shows the same frame as the
 * last one, costs a comparison; a playback at full speed costs the packets it
 * actually sends. Recordings are not driven from here at all &mdash; each
 * followed player is sampled by a task bound to that player, because on Folia
 * that is the only thread allowed to read where they are.
 *
 * <h2>Nothing is left on a screen</h2>
 * The bodies a playback draws are packets, so nothing else will take them away.
 * Every way out is here: the recording ending, the last viewer leaving, the
 * plugin being disabled, and the server stopping.
 */
@ApiStatus.Internal
public final class ReplayRuntime {

    private static final Object LOCK = new Object();

    private static final ConcurrentLinkedQueue<Recording> RECORDINGS =
            new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Playback> PLAYBACKS =
            new ConcurrentLinkedQueue<>();

    private static TaskScheduler scheduler;
    private static FakeBlocks fakeBlocks;
    private static Logger logger = Logger.getLogger("ExyliaLib");
    private static TaskHandle driver;
    private static boolean warned;

    private ReplayRuntime() {
    }

    /**
     * Wires the module to the runtime. Called by ExyliaLib at startup.
     *
     * @param plugin the plugin whose scheduler drives playbacks
     */
    public static void init(Plugin plugin) {
        synchronized (LOCK) {
            scheduler = Tasks.of(plugin);
            logger = plugin.getLogger();
            if (driver != null) {
                driver.cancel();
            }
            fakeBlocks = Packets.of(plugin).fakeBlocks();
            driver = scheduler.runAsyncTimer(1L, 1L, ReplayRuntime::tick);
        }
        Bukkit.getPluginManager().registerEvents(new Events(), plugin);
    }

    /** Whether a recording can be played back at all. */
    public static boolean isSupported() {
        return NpcRuntime.isSupported();
    }

    /** Starts a recording against an anchor. */
    public static ReplayRecorder record(String owner, Location anchor) {
        Recording recording = new Recording(owner, anchor, schedulerOrFail(owner));
        RECORDINGS.add(recording);
        return recording;
    }

    /** Starts a playback, or returns null when nobody can see it. */
    public static ReplayPlayback play(String owner, Replay replay, Location at,
                                      List<Player> viewers) {
        if (!isSupported()) {
            warnOnce(owner);
            return null;
        }
        if (viewers.isEmpty() || replay.frames() == 0) {
            return null;
        }
        Playback playback = new Playback(owner, replay, at, viewers, schedulerOrFail(owner));
        PLAYBACKS.add(playback);
        // Drawn now rather than on the driver's next tick, so a playback that
        // is opened paused still has bodies standing in it.
        playback.seek(0);
        return playback;
    }

    /**
     * Where the arena a replay rebuilds is drawn.
     *
     * <p>The library's own rather than the watching plugin's, because these are
     * the library's blocks: it is the one that puts them there and the one that
     * takes them away again.
     */
    static FakeBlocks fakeBlocks() {
        return fakeBlocks;
    }

    /** Forgets a recording that has ended itself. */
    static void forget(Recording recording) {
        RECORDINGS.remove(recording);
    }

    /** Forgets a playback that has ended itself. */
    static void forget(Playback playback) {
        PLAYBACKS.remove(playback);
    }

    /** How many recordings are running, for diagnostics. */
    public static int recording() {
        return RECORDINGS.size();
    }

    /** How many are being watched. */
    public static int playing() {
        return PLAYBACKS.size();
    }

    /** How many recordings one plugin is making. */
    public static int recording(String pluginName) {
        int total = 0;
        for (Recording recording : RECORDINGS) {
            if (recording.owner().equals(pluginName)) {
                total++;
            }
        }
        return total;
    }

    /** How many playbacks one plugin is showing. */
    public static int playing(String pluginName) {
        int total = 0;
        for (Playback playback : PLAYBACKS) {
            if (playback.owner().equals(pluginName)) {
                total++;
            }
        }
        return total;
    }

    /**
     * Ends everything one plugin owns.
     *
     * <p>Recordings are cancelled rather than stopped: whatever would have been
     * done with the result belongs to a classloader that is going away, so
     * building one would be work for nobody.
     *
     * @param pluginName the plugin
     */
    public static void release(String pluginName) {
        for (Iterator<Playback> playbacks = PLAYBACKS.iterator(); playbacks.hasNext(); ) {
            Playback playback = playbacks.next();
            if (playback.owner().equals(pluginName)) {
                playbacks.remove();
                playback.stop();
            }
        }
        for (Iterator<Recording> recordings = RECORDINGS.iterator(); recordings.hasNext(); ) {
            Recording recording = recordings.next();
            if (recording.owner().equals(pluginName)) {
                recordings.remove();
                recording.cancel();
            }
        }
    }

    /** Ends everything, on shutdown. */
    public static void releaseAll() {
        synchronized (LOCK) {
            if (driver != null) {
                driver.cancel();
                driver = null;
            }
        }
        for (Iterator<Playback> playbacks = PLAYBACKS.iterator(); playbacks.hasNext(); ) {
            Playback playback = playbacks.next();
            playbacks.remove();
            playback.stop();
        }
        for (Iterator<Recording> recordings = RECORDINGS.iterator(); recordings.hasNext(); ) {
            Recording recording = recordings.next();
            recordings.remove();
            recording.cancel();
        }
    }

    /** Says once that a recorder was left running until it stopped itself. */
    static void overran(String owner) {
        logger.warning(owner + " left a replay recorder running for half an hour and it has"
                + " stopped itself. A recorder must be stopped or cancelled on every path out"
                + " of whatever it was recording.");
    }

    /** Advances every playback, and ends the ones nobody is watching. */
    private static void tick() {
        if (PLAYBACKS.isEmpty()) {
            return;
        }
        for (Iterator<Playback> playbacks = PLAYBACKS.iterator(); playbacks.hasNext(); ) {
            Playback playback = playbacks.next();
            try {
                if (!playback.hasViewers()) {
                    playbacks.remove();
                    playback.stop();
                    continue;
                }
                playback.step();
            } catch (RuntimeException failure) {
                // One that throws must not freeze the ones behind it, and must
                // not leave its own bodies standing either.
                playbacks.remove();
                playback.stop();
                logger.warning("A replay stopped because it failed: " + failure);
            }
        }
    }

    private static TaskScheduler schedulerOrFail(String owner) {
        TaskScheduler running = scheduler;
        if (running == null) {
            throw new IllegalStateException(owner + " asked for a replay before ExyliaLib"
                    + " finished starting");
        }
        return running;
    }

    private static void warnOnce(String owner) {
        if (warned) {
            return;
        }
        warned = true;
        logger.warning(owner + " asked to play a replay, but PacketEvents is not installed;"
                + " the bodies a replay is made of are packets and cannot be shown.");
    }

    /**
     * The three things a recording would otherwise need its own listener for.
     *
     * <p>A swing and a hit are what make a fight readable, and a death is where
     * one ends. Every plugin that records a fight wants all three, so they are
     * taken here rather than left as a listener each of them has to remember to
     * write.
     *
     * <p>Registered once for the whole server and cheap when nothing is being
     * recorded, which is almost always: the first line of each handler is a
     * check on an empty queue.
     */
    private static final class Events implements Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onSwing(PlayerAnimationEvent event) {
            if (event.getAnimationType() == PlayerAnimationType.ARM_SWING) {
                write(ReplayMark.SWING, event.getPlayer());
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onHurt(EntityDamageEvent event) {
            if (event.getEntity() instanceof Player hurt) {
                write(ReplayMark.HURT, hurt);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onDeath(PlayerDeathEvent event) {
            write(ReplayMark.DEATH, event.getEntity());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onRespawn(PlayerRespawnEvent event) {
            write(ReplayMark.RESPAWN, event.getPlayer());
        }

        private static void write(String kind, Player player) {
            if (RECORDINGS.isEmpty()) {
                return;
            }
            List<Recording> interested = null;
            for (Recording recording : RECORDINGS) {
                if (recording.follows(player.getUniqueId())) {
                    if (interested == null) {
                        interested = new ArrayList<>(1);
                    }
                    interested.add(recording);
                }
            }
            if (interested != null) {
                interested.forEach(recording -> recording.mark(kind, player, (byte[]) null));
            }
        }
    }
}
