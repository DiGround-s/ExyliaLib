package net.exylia.lib.replay.internal;

import net.exylia.lib.packet.FakeBlocks;
import net.exylia.lib.packet.Packets;
import net.exylia.lib.replay.BlackBoxSettings;
import net.exylia.lib.replay.Replay;
import net.exylia.lib.replay.ReplayBlackBox;
import net.exylia.lib.replay.ReplayPlayback;
import net.exylia.lib.replay.ReplayRecorder;
import net.exylia.lib.replay.ReplayStage;
import net.exylia.lib.replay.ReplayWorld;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Everything the replay module has running, across every plugin.
 *
 * <p>One driver ticks every playback, once per server tick, off the server's
 * thread: a playback only sends packets, and every one of those is safe from
 * anywhere. The recorders run on the server's own timers, bound to whoever they
 * read. The server's events reach both through {@link ReplayEvents}.
 */
@ApiStatus.Internal
public final class ReplayRuntime {

    private static final Object LOCK = new Object();
    private static final ConcurrentLinkedQueue<Recording> RECORDINGS = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Playback> PLAYBACKS = new ConcurrentLinkedQueue<>();

    /** Which plugin asked the black box for what. */
    private static final Map<String, BlackBoxSettings> BOX_USERS = new ConcurrentHashMap<>();

    private static TaskScheduler scheduler;
    private static FakeBlocks fakeBlocks;
    private static Logger logger = Logger.getLogger("ExyliaLib");
    private static TaskHandle driver;
    private static volatile BlackBox box;
    private static boolean warned;

    private ReplayRuntime() {
    }

    public static void init(Plugin plugin) {
        synchronized (LOCK) {
            scheduler = Tasks.of(plugin);
            logger = plugin.getLogger();
            if (driver != null) driver.cancel();
            fakeBlocks = Packets.of(plugin).fakeBlocks();
            driver = scheduler.runAsyncTimer(1L, 1L, ReplayRuntime::tick);
        }
        ReplayEvents.register(plugin);
    }

    public static boolean isSupported() {
        try {
            return ReplayPackets.ready();
        } catch (Throwable missing) {
            return false;
        }
    }

    // -------------------------------------------------------------- recording

    public static ReplayRecorder record(String owner, Location anchor) {
        Recording recording = new Recording(owner, anchor, schedulerOrFail(owner));
        RECORDINGS.add(recording);
        return recording;
    }

    /** Every recording following an actor, for a mark about them. */
    static void following(UUID actor, Consumer<Recording> action) {
        if (RECORDINGS.isEmpty()) return;
        for (Recording recording : RECORDINGS) {
            if (recording.follows(actor)) action.accept(recording);
        }
    }

    /** Every recording watching a place by itself. */
    static void watching(Location at, Consumer<Recording> action) {
        if (RECORDINGS.isEmpty()) return;
        for (Recording recording : RECORDINGS) {
            if (recording.watches(at)) action.accept(recording);
        }
    }

    // --------------------------------------------------------------- playback

    public static ReplayPlayback play(String owner, Replay replay, Location at, List<Player> viewers,
                                      ReplayWorld world) {
        return play(owner, replay, List.of(at), viewers, world, false);
    }

    public static @Nullable ReplayPlayback play(String owner, Replay replay, List<Location> anchors,
                                                List<Player> viewers, ReplayWorld world, boolean carry) {
        if (!isSupported()) {
            warnOnce(owner);
            return null;
        }
        if (viewers.isEmpty() || replay.frames() == 0 || anchors.isEmpty()) return null;
        Playback playback = new Playback(owner, replay, anchors, List.copyOf(viewers), schedulerOrFail(owner));
        playback.solid(world == ReplayWorld.SOLID);
        playback.carryViewers(carry);
        PLAYBACKS.add(playback);
        // Queued rather than drawn here: every frame is drawn by the one driver,
        // the first one included.
        playback.seek(0);
        return playback;
    }

    public static CompletableFuture<ReplayStage> stage(String owner, Replay replay) {
        return Staging.build(owner, replay, schedulerOrFail(owner));
    }

    static FakeBlocks fakeBlocks() {
        return fakeBlocks;
    }

    static void forget(Recording recording) {
        RECORDINGS.remove(recording);
    }

    static void forget(Playback playback) {
        PLAYBACKS.remove(playback);
    }

    // -------------------------------------------------------------- black box

    /** The black box, if anybody asked for it. */
    static @Nullable BlackBox box() {
        return box;
    }

    public static ReplayBlackBox openBox(String owner, BlackBoxSettings settings) {
        synchronized (LOCK) {
            BOX_USERS.put(owner, settings);
            BlackBoxSettings merged = merged();
            if (box == null) {
                box = new BlackBox(schedulerOrFail(owner), merged);
                box.start();
            } else {
                box.settings(merged);
            }
        }
        return new BoxHandle(owner);
    }

    static void closeBox(String owner) {
        synchronized (LOCK) {
            if (BOX_USERS.remove(owner) == null) return;
            if (BOX_USERS.isEmpty()) {
                if (box != null) box.stop();
                box = null;
            } else if (box != null) {
                box.settings(merged());
            }
        }
    }

    private static BlackBoxSettings merged() {
        BlackBoxSettings merged = null;
        for (BlackBoxSettings settings : BOX_USERS.values()) {
            merged = merged == null ? settings : merged.merge(settings);
        }
        return merged;
    }

    // ------------------------------------------------------------ bookkeeping

    public static int recording() {
        return RECORDINGS.size();
    }

    public static int playing() {
        return PLAYBACKS.size();
    }

    public static int recording(String pluginName) {
        int total = 0;
        for (Recording recording : RECORDINGS) {
            if (recording.owner().equals(pluginName)) total++;
        }
        return total;
    }

    public static int playing(String pluginName) {
        int total = 0;
        for (Playback playback : PLAYBACKS) {
            if (playback.owner().equals(pluginName)) total++;
        }
        return total;
    }

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
        closeBox(pluginName);
    }

    public static void releaseAll() {
        synchronized (LOCK) {
            if (driver != null) {
                driver.cancel();
                driver = null;
            }
            BOX_USERS.clear();
            if (box != null) box.stop();
            box = null;
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

    static void overran(String owner) {
        logger.warning(owner + " left a replay recorder running for half an hour and it has"
                + " stopped itself. A recorder must be stopped or cancelled on every path out"
                + " of whatever it was recording.");
    }

    private static void tick() {
        if (PLAYBACKS.isEmpty()) return;
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
            throw new IllegalStateException(owner + " asked for a replay before ExyliaLib finished starting");
        }
        return running;
    }

    private static void warnOnce(String owner) {
        if (warned) return;
        warned = true;
        logger.warning(owner + " asked to play a replay, but PacketEvents is not installed;"
                + " the bodies a replay is made of are packets and cannot be shown.");
    }

    /** One plugin's hold on the shared black box. */
    private record BoxHandle(String owner) implements ReplayBlackBox {

        @Override
        public @NotNull CompletableFuture<Replay> capture(@NotNull Player focus) {
            BlackBox running = box;
            if (running == null) return closed();
            return running.capture(focus.getUniqueId(), null, running.settings().seconds());
        }

        @Override
        public @NotNull CompletableFuture<Replay> capture(@NotNull UUID focus, int seconds) {
            BlackBox running = box;
            return running == null ? closed() : running.capture(focus, null, seconds);
        }

        @Override
        public @NotNull CompletableFuture<Replay> capture(@NotNull UUID focus, int seconds, int afterSeconds) {
            BlackBox running = box;
            return running == null ? closed() : running.capture(focus, null, seconds, afterSeconds);
        }

        @Override
        public @NotNull CompletableFuture<Replay> capture(@NotNull Location center, int seconds) {
            BlackBox running = box;
            return running == null ? closed() : running.capture(null, center, seconds);
        }

        @Override
        public void mark(@NotNull String kind, @NotNull Player actor, @Nullable String text) {
            BlackBox running = box;
            if (running != null && running.knows(actor.getUniqueId())) {
                running.mark(kind, actor.getUniqueId(),
                        text == null ? null : text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }

        @Override
        public int seconds() {
            BlackBoxSettings mine = BOX_USERS.get(owner);
            return mine == null ? 0 : mine.seconds();
        }

        @Override
        public boolean isRunning() {
            BlackBox running = box;
            return running != null && running.isRunning() && BOX_USERS.containsKey(owner);
        }

        @Override
        public void close() {
            closeBox(owner);
        }

        private static <T> CompletableFuture<T> closed() {
            return CompletableFuture.failedFuture(new IllegalStateException("The black box is not running"));
        }
    }
}
