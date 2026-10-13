package net.exylia.lib.npc.internal;

import net.exylia.lib.npc.NpcSkin;
import net.exylia.lib.npc.NpcSpec;
import net.exylia.lib.npc.PlacedNpc;
import net.exylia.lib.skull.internal.MojangApi;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Owns every placed NPC on the server and decides who sees which.
 *
 * <p>The hologram runtime's shape: one async pass four times a second, a squared
 * distance per player per NPC, packets only when somebody crosses the edge, and nothing
 * left on any client once the owning plugin is disabled.
 */
@ApiStatus.Internal
public final class PlacedRuntime {

    /** Four times a second, past what a walking player notices. */
    private static final long PASS_PERIOD_TICKS = 5L;

    /** How long a skin Mojang would not give is left alone before it is asked again. */
    private static final long SKIN_RETRY_MILLIS = 60_000L;

    private static final Object LOCK = new Object();

    /** Keyed by owning plugin, then by id, so ids never clash between plugins. */
    private static final Map<String, Map<String, PlacedNpcImpl>> BY_OWNER = new ConcurrentHashMap<>();

    /** Every placed NPC by its entity id, which is how a click finds it. */
    private static final Map<Integer, PlacedNpcImpl> BY_ENTITY = new ConcurrentHashMap<>();

    /** Account skins already resolved, by lower-case name, as {@code {value, signature}}. */
    private static final Map<String, String[]> SKINS = new ConcurrentHashMap<>();

    /** Account skins being looked up, or not to be asked again before the value. */
    private static final Map<String, Long> ASKED = new ConcurrentHashMap<>();

    private static TaskScheduler scheduler;
    private static Logger logger = Logger.getLogger("ExyliaLib");
    private static TaskHandle driver;
    private static LongSupplier clock = System::currentTimeMillis;
    private static NpcRuntime.EntityIds ids;
    private static PlacedSink sink;
    /** The skin a connected player wears, from their connection. */
    private static Function<Player, String[]> connectionSkin = player -> null;
    /** The skin an account wears, from Mojang. Blocks; only ever called async. */
    private static Function<String, String[]> accountSkin = name -> null;
    private static boolean available;
    private static boolean warned;
    /** The PacketEvents listener clicks arrive through, while one is registered. */
    private static Object clicks;

    private PlacedRuntime() {
    }

    /**
     * Wires the module to the runtime. Called by ExyliaLib at startup.
     *
     * @param plugin the plugin whose scheduler drives placed NPCs
     */
    public static void init(Plugin plugin) {
        synchronized (LOCK) {
            scheduler = Tasks.of(plugin);
            logger = plugin.getLogger();
            available = NpcPackets.ready();
            if (!available) {
                return;
            }
            ids = NpcPackets::newEntityId;
            sink = NpcPackets.INSTANCE;
            connectionSkin = NpcPackets::textureOf;
            MojangApi mojang = new MojangApi(Duration.ofSeconds(5), 60_000L, 30_000L, logger);
            accountSkin = name -> {
                UUID id = mojang.idOf(name);
                return id == null ? null : mojang.signedTextureOf(id);
            };
            if (clicks == null) {
                clicks = NpcPackets.listenPlaced(PlacedRuntime::isPlaced, PlacedRuntime::clicked);
            }
        }
    }

    /** Swaps everything that would touch a server or the network. For tests. */
    static void testHooks(TaskScheduler testScheduler, LongSupplier testClock,
                          NpcRuntime.EntityIds testIds, PlacedSink testSink,
                          Function<Player, String[]> testConnectionSkin,
                          Function<String, String[]> testAccountSkin) {
        synchronized (LOCK) {
            scheduler = testScheduler;
            clock = testClock;
            ids = testIds;
            sink = testSink;
            connectionSkin = testConnectionSkin;
            accountSkin = testAccountSkin;
            available = true;
            SKINS.clear();
            ASKED.clear();
        }
    }

    static PlacedSink sink() {
        return sink;
    }

    /** Whether an entity id is a placed NPC's body. Asked on the network thread. */
    static boolean isPlaced(int entityId) {
        return BY_ENTITY.containsKey(entityId);
    }

    /** A player clicked a placed NPC's body. */
    static void clicked(Player player, int entityId, boolean attack) {
        PlacedNpcImpl npc = BY_ENTITY.get(entityId);
        if (npc != null) {
            npc.clicked(player, attack, clock.getAsLong());
        }
    }

    /**
     * Places an NPC.
     *
     * @return the NPC; an inert, already-removed one when the server has no PacketEvents
     */
    public static PlacedNpc place(Plugin owner, String id, NpcSpec spec, Location at) {
        if (owner == null || id == null || spec == null || at == null || at.getWorld() == null) {
            throw new IllegalArgumentException("owner, id, spec and a location with a world are required");
        }
        if (!available) {
            warnOnce(owner.getName());
            PlacedNpcImpl inert = new PlacedNpcImpl(id, owner, -1, spec, at);
            inert.markRemoved();
            return inert;
        }
        PlacedNpcImpl npc = new PlacedNpcImpl(id, owner, ids.next(), spec, at);
        synchronized (LOCK) {
            PlacedNpcImpl previous = BY_OWNER
                    .computeIfAbsent(owner.getName(), key -> new ConcurrentHashMap<>())
                    .put(id, npc);
            if (previous != null) {
                retire(previous);
            }
            BY_ENTITY.put(npc.entityId(), npc);
            if (driver == null && scheduler != null) {
                driver = scheduler.runAsyncTimer(PASS_PERIOD_TICKS, PASS_PERIOD_TICKS,
                        PlacedRuntime::tick);
            }
        }
        return npc;
    }

    /** One plugin's NPC by id. */
    public static Optional<PlacedNpc> get(String owner, String id) {
        Map<String, PlacedNpcImpl> owned = BY_OWNER.get(owner);
        return Optional.ofNullable(owned == null ? null : owned.get(id));
    }

    /** Every NPC one plugin placed. */
    public static List<PlacedNpc> all(String owner) {
        Map<String, PlacedNpcImpl> owned = BY_OWNER.get(owner);
        return owned == null ? List.of() : List.copyOf(owned.values());
    }

    /** Removes one NPC. */
    static void remove(PlacedNpcImpl npc) {
        synchronized (LOCK) {
            if (npc.removed()) {
                return;
            }
            Map<String, PlacedNpcImpl> owned = BY_OWNER.get(npc.owner().getName());
            if (owned != null) {
                owned.remove(npc.id(), npc);
            }
            retire(npc);
            stopIfIdle();
        }
    }

    /**
     * Removes every NPC one plugin placed, from every screen.
     *
     * @param pluginName the plugin
     * @return how many were removed
     */
    public static int release(String pluginName) {
        synchronized (LOCK) {
            Map<String, PlacedNpcImpl> owned = BY_OWNER.remove(pluginName);
            if (owned == null) {
                return 0;
            }
            owned.values().forEach(PlacedRuntime::retire);
            stopIfIdle();
            return owned.size();
        }
    }

    /** Removes everything, on shutdown. */
    public static void releaseAll() {
        synchronized (LOCK) {
            for (Map<String, PlacedNpcImpl> owned : BY_OWNER.values()) {
                owned.values().forEach(PlacedRuntime::retire);
            }
            BY_OWNER.clear();
            BY_ENTITY.clear();
            stopIfIdle();
            if (clicks != null) {
                NpcPackets.stopListening(clicks);
                clicks = null;
            }
        }
    }

    /** Forgets a player who left. Nothing is sent: the client is gone. */
    public static void forget(Player player) {
        UUID id = player.getUniqueId();
        forEach(npc -> npc.forget(id));
    }

    /**
     * Takes every NPC off a respawning player's screen.
     *
     * <p>A respawn clears every entity on the client without a world change the pass
     * could notice; dropped here, each one is sent again by the next pass.
     */
    public static void respawned(Player player) {
        forEach(npc -> npc.despawn(player));
    }

    /** How many NPCs are placed, across every plugin. */
    public static int count() {
        int total = 0;
        for (Map<String, PlacedNpcImpl> owned : BY_OWNER.values()) {
            total += owned.size();
        }
        return total;
    }

    /**
     * What one viewer sees an NPC wearing, as {@code {value, signature}}, or {@code null}
     * for the default skin. Never blocks: an account skin not looked up yet is asked for
     * in the background and the NPC is redrawn when it arrives.
     */
    static String[] skinFor(NpcSkin skin, Player viewer) {
        if (skin.texture() != null) {
            return new String[] {skin.texture(), skin.signature()};
        }
        if (skin.isMirror()) {
            return connectionSkin.apply(viewer);
        }
        String account = skin.playerName();
        if (account == null) {
            return null;
        }
        String key = account.toLowerCase(Locale.ROOT);
        String[] known = SKINS.get(key);
        if (known == null) {
            lookUp(account, key);
        }
        return known;
    }

    private static void lookUp(String account, String key) {
        long now = clock.getAsLong();
        // In flight is "not before the end of time"; a failure is "not before a minute".
        boolean[] claimed = {false};
        ASKED.compute(key, (name, notBefore) -> {
            if (notBefore != null && now < notBefore) {
                return notBefore;
            }
            claimed[0] = true;
            return Long.MAX_VALUE;
        });
        if (!claimed[0]) {
            return;
        }
        scheduler.runAsync(() -> {
            String[] found = null;
            try {
                Player online = Bukkit.getPlayerExact(account);
                found = online != null ? connectionSkin.apply(online) : null;
                if (found == null) {
                    found = accountSkin.apply(account);
                }
            } catch (RuntimeException failure) {
                logger.warning("Could not look up the skin of " + account + ": " + failure);
            }
            if (found == null) {
                ASKED.put(key, clock.getAsLong() + SKIN_RETRY_MILLIS);
                return;
            }
            SKINS.put(key, found);
            ASKED.remove(key);
            forEach(npc -> npc.reskin(account));
        });
    }

    private static void retire(PlacedNpcImpl npc) {
        synchronized (npc) {
            npc.markRemoved();
            npc.despawnAll();
        }
        BY_ENTITY.remove(npc.entityId(), npc);
    }

    private static void stopIfIdle() {
        if (count() == 0 && driver != null) {
            driver.cancel();
            driver = null;
        }
    }

    private static void forEach(java.util.function.Consumer<PlacedNpcImpl> action) {
        for (Map<String, PlacedNpcImpl> owned : BY_OWNER.values()) {
            for (PlacedNpcImpl npc : owned.values()) {
                action.accept(npc);
            }
        }
    }

    /** One visibility pass over every placed NPC. */
    static void tick() {
        if (BY_OWNER.isEmpty()) {
            return;
        }
        List<? extends Player> online = List.copyOf(Bukkit.getOnlinePlayers());
        List<PlacedNpcImpl> npcs = new ArrayList<>();
        forEach(npcs::add);
        for (PlacedNpcImpl npc : npcs) {
            try {
                npc.pass(online);
            } catch (RuntimeException failure) {
                logger.warning("Could not update the NPC '" + npc.id() + "': " + failure);
            }
        }
    }

    private static void warnOnce(String owner) {
        if (warned) {
            return;
        }
        warned = true;
        logger.warning(owner + " placed an NPC, but PacketEvents is not installed;"
                + " NPCs are packet-only and will not be shown.");
    }
}
