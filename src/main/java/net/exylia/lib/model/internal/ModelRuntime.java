package net.exylia.lib.model.internal;

import net.exylia.lib.display.internal.DisplayRuntime;
import net.exylia.lib.display.internal.DisplaySink;
import net.exylia.lib.model.Model;
import net.exylia.lib.model.ModelInstance;
import net.exylia.lib.model.ModelOptions;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Every model being worn on the server.
 *
 * <h2>What it keeps</h2>
 * Which entity ids are carriers, so their armour and name are kept from
 * clients; which are a model's own boxes, so a hit on one reaches its carrier;
 * and what every model costs, as cubes multiplied by the players drawing them,
 * against the server's ceiling. A model over that ceiling is not refused: its
 * viewers are drawn as if from further away, fewer cubes less often, so a
 * crowded fight loses detail rather than models.
 *
 * <h2>Nothing is left behind</h2>
 * A model's cubes are packets, so nothing on the server would clean them up.
 * Each model is ticked by its carrier, and a sweep once a second takes off every
 * client any model whose carrier stopped ticking it &mdash; the carrier gone a
 * way no event told about.
 */
@ApiStatus.Internal
public final class ModelRuntime {

    /** Cubes multiplied by viewers, now. */
    private static final AtomicInteger LOAD = new AtomicInteger();

    /** Above this share of the ceiling, viewers are drawn a step further away. */
    static final double PRESSURE_STEP = 0.75;

    private static final Map<Integer, LiveModel> CARRIERS = new ConcurrentHashMap<>();
    private static final Map<Integer, LiveModel> BOXES = new ConcurrentHashMap<>();
    private static final Map<java.util.UUID, LiveModel> BY_UUID = new ConcurrentHashMap<>();

    private static volatile int ceiling = 30_000;
    private static volatile Logger logger = Logger.getLogger("ExyliaLib");
    private static Object listener;
    private static TaskHandle sweep;

    private ModelRuntime() {
    }

    /** Wires the module. Called by ExyliaLib once displays are. */
    public static synchronized void init(Plugin library) {
        logger = library.getLogger();
        if (listener == null && ModelPackets.ready()) {
            listener = ModelPackets.listen(CARRIERS::containsKey, BOXES::containsKey, ModelRuntime::clicked);
        }
        if (sweep != null) {
            sweep.cancel();
        }
        sweep = Tasks.of(library).runAsyncTimer(20L, 20L, ModelRuntime::sweep);
    }

    /**
     * The ceiling on cubes multiplied by viewers.
     *
     * @param parts the ceiling; {@code 0} or below for none
     */
    public static void ceiling(int parts) {
        ceiling = parts <= 0 ? Integer.MAX_VALUE : parts;
    }

    /** How many steps further away every viewer is drawn, from how close the server is to its ceiling. */
    static int pressure() {
        int limit = ceiling;
        double share = LOAD.get() / (double) limit;
        return share >= 1 ? 2 : share >= PRESSURE_STEP ? 1 : 0;
    }

    static void load(int delta) {
        if (delta != 0) {
            LOAD.addAndGet(delta);
        }
    }

    /** Cubes multiplied by viewers, for diagnostics. */
    public static int load() {
        return LOAD.get();
    }

    static Logger logger() {
        return logger;
    }

    /** Whether models can be drawn at all: displays can, and the packets are there. */
    public static boolean isSupported() {
        return DisplayRuntime.isSupported() && DisplayRuntime.sink() != null;
    }

    /**
     * Puts a model on a carrier, on the carrier's thread.
     *
     * @return the live model, or {@code null} when models cannot be drawn
     */
    public static @Nullable ModelInstance attach(Plugin owner, LivingEntity carrier, Model model, ModelOptions options) {
        DisplaySink sink = DisplayRuntime.sink();
        if (sink == null) {
            return null;
        }
        LiveModel old = BY_UUID.get(carrier.getUniqueId());
        if (old != null) {
            old.remove();
        }
        LiveModel live = new LiveModel(owner, carrier, model, options, sink);
        BY_UUID.put(carrier.getUniqueId(), live);
        CARRIERS.put(carrier.getEntityId(), live);
        live.start();
        return live;
    }

    /** The model a carrier wears, or {@code null}. */
    public static @Nullable ModelInstance of(Entity carrier) {
        LiveModel live = BY_UUID.get(carrier.getUniqueId());
        return live != null && live.isActive() ? live : null;
    }

    static void box(int id, LiveModel live) {
        BOXES.put(id, live);
    }

    /** Forgets everything a model was registered under; called once as it goes. */
    static void forget(LiveModel live) {
        CARRIERS.remove(live.carrierId(), live);
        BY_UUID.remove(live.carrierUuid(), live);
        for (int id : live.boxIds()) {
            BOXES.remove(id, live);
        }
    }

    /** It let go of its carrier: the carrier's armour and name are its own again. */
    static void letGo(LiveModel live) {
        CARRIERS.remove(live.carrierId(), live);
        BY_UUID.remove(live.carrierUuid(), live);
    }

    private static void clicked(Player player, int entityId, boolean attack) {
        LiveModel live = BOXES.get(entityId);
        if (live != null && attack) {
            live.hitBy(player);
        }
    }

    private static void sweep() {
        long now = System.currentTimeMillis();
        for (LiveModel live : new ArrayList<>(BY_UUID.values())) {
            if (live.stale(now)) {
                live.remove();
            }
        }
        for (LiveModel live : new ArrayList<>(LiveModel.DETACHED)) {
            if (live.stale(now)) {
                live.remove();
            }
        }
    }

    /** How many models one plugin has on, carriers or dying. */
    public static int active(String owner) {
        int count = 0;
        for (LiveModel live : BY_UUID.values()) {
            if (live.owner().equals(owner)) count++;
        }
        for (LiveModel live : LiveModel.DETACHED) {
            if (live.owner().equals(owner)) count++;
        }
        return count;
    }

    /** Takes off every model one plugin put on. Called when it is disabled. */
    public static void release(String owner) {
        List<LiveModel> all = new ArrayList<>(BY_UUID.values());
        all.addAll(LiveModel.DETACHED);
        for (LiveModel live : all) {
            if (live.owner().equals(owner)) {
                live.remove();
            }
        }
    }

    /** Takes every model off, on shutdown. */
    public static synchronized void releaseAll() {
        List<LiveModel> all = new ArrayList<>(BY_UUID.values());
        all.addAll(LiveModel.DETACHED);
        for (LiveModel live : all) {
            live.remove();
        }
        if (sweep != null) {
            sweep.cancel();
            sweep = null;
        }
        if (listener != null) {
            ModelPackets.stop(listener);
            listener = null;
        }
    }
}
