package net.exylia.lib.proxy.internal;

import net.exylia.lib.database.internal.DatabaseRuntime;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.proxy.ProxyReply;
import net.exylia.lib.redis.RedisSettings;
import net.exylia.lib.redis.internal.RedisClient;
import net.exylia.lib.redis.internal.RedisRuntime;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * The bridge's this-side half: one Redis channel in, one out.
 *
 * <p>Over the Redis the library's own {@code database.yml} names, not over
 * plugin messages: a plugin message travels down a player's connection, and
 * a modified client can write one, so a bridge built on them has to trust
 * that the proxy filtered the client's bytes out. Redis is on the network's
 * own side of the wall. Nothing a player sends can reach it, and it works
 * with nobody online at all.
 *
 * <p>Requests are numbered from one counter and matched back by that number;
 * everything that can go wrong on the way back — the proxy silent, Redis
 * off — completes the future with a reply that says so rather than leaving
 * it hanging.
 */
@ApiStatus.Internal
public final class ProxyRuntime {

    /** How long the proxy has to answer before the request is given up on. */
    static final long TIMEOUT_SECONDS = 5;

    /** The module the proxy answers with its own name and version. */
    static final String PING = "ping";

    /** The module the proxy answers with every connected name. */
    static final String PLAYERS = "players";

    /**
     * How often the proxy is asked something on a timer.
     *
     * <p>A ping while it is unknown, the player list once it is there. Ten
     * seconds: a tab completion is a convenience, and a name that is a few
     * seconds stale costs a "not online" line rather than anything worse.
     */
    private static final long PERIOD_TICKS = 20L * 10;

    private static final AtomicInteger IDS = new AtomicInteger();
    private static final Map<Integer, CompletableFuture<ProxyReply>> PENDING = new ConcurrentHashMap<>();

    /** What handles each module the proxy pushes unasked, by module name. */
    private static final Map<String, BiConsumer<UUID, String>> PUSHES = new ConcurrentHashMap<>();

    private static volatile @Nullable Plugin library;
    private static volatile @Nullable RedisClient redis;
    private static volatile @Nullable RedisClient.Subscription subscription;
    private static volatile @Nullable TaskHandle timer;
    private static volatile String prefix = "exylia";
    private static volatile String serverId = "server-1";
    private static volatile boolean available;
    private static volatile @Nullable String bridge;
    private static volatile boolean warned;
    private static volatile Set<String> players = Set.of();

    private ProxyRuntime() {
    }

    /**
     * Opens the channel over the library's Redis, if it has one.
     *
     * <p>Without {@code database.redis} in {@code plugins/ExyliaLib/database.yml}
     * there is no bridge, and the console says so once: a network that runs
     * a proxy plugin has a Redis, and a single server has nothing to bridge.
     */
    public static synchronized void init(@NotNull Plugin plugin) {
        if (library != null) {
            return;
        }
        library = plugin;
        Debug debug = Debug.of(plugin);
        RedisSettings settings;
        RedisClient client;
        try {
            settings = DatabaseRuntime.redis(plugin);
            client = RedisRuntime.client(plugin, settings);
        } catch (RuntimeException | LinkageError absent) {
            debug.warn("The proxy bridge could not open Redis, so player-proxy: and console-proxy:"
                    + " commands and cross-server teleports are unavailable: " + absent.getMessage());
            return;
        }
        if (client == null) {
            if (settings.enabled()) {
                // The Redis module already said where and why, just above.
                debug.warn("The proxy bridge is off because Redis could not be reached; restart"
                        + " once it answers. player-proxy: and console-proxy: commands and"
                        + " cross-server teleports are unavailable until then.");
            } else {
                debug.log("No Redis in plugins/ExyliaLib/database.yml (redis.enabled is false), so"
                        + " there is no proxy bridge: player-proxy: and console-proxy: commands and"
                        + " cross-server teleports are unavailable on this server.");
            }
            return;
        }
        prefix = settings.keyPrefix();
        serverId = settings.serverId();
        redis = client;
        try {
            subscription = client.subscribe(Frames.channelOf(prefix, serverId), ProxyRuntime::onMessage);
        } catch (RuntimeException unreachable) {
            debug.warn("Could not listen for the proxy bridge: " + unreachable.getMessage());
            redis = null;
            return;
        }
        // The first ping a second in, so a proxy that is there is announced
        // during startup rather than ten seconds later.
        timer = Tasks.of(plugin).runAsyncTimer(20L, PERIOD_TICKS, ProxyRuntime::tick);
    }

    /** Fails everything still in flight and closes the channel; on shutdown. */
    public static synchronized void shutdown() {
        library = null;
        available = false;
        bridge = null;
        players = Set.of();
        TaskHandle running = timer;
        timer = null;
        if (running != null) {
            running.cancel();
        }
        RedisClient.Subscription open = subscription;
        subscription = null;
        if (open != null) {
            try {
                open.close();
            } catch (RuntimeException ignored) {
                // Shutting down.
            }
        }
        redis = null;
        for (CompletableFuture<ProxyReply> future : PENDING.values()) {
            future.complete(new ProxyReply(ProxyReply.Status.NO_BRIDGE, "the server is shutting down"));
        }
        PENDING.clear();
        PUSHES.clear();
    }

    public static boolean isAvailable() {
        return available;
    }

    public static @NotNull Optional<String> bridge() {
        return Optional.ofNullable(bridge);
    }

    /** This server's name on the network, which is also its name on the proxy. */
    public static @NotNull String serverId() {
        return serverId;
    }

    /**
     * Handles what the proxy sends unasked on a module.
     *
     * <p>A push is an answer frame with id 0. The handler runs on the Redis
     * subscriber thread with the player it is about, who may not have joined
     * this server yet; a later registration for the same module replaces the
     * earlier one.
     *
     * @param module  the module name, as the proxy pushes it
     * @param handler what to do with the player's id and the payload
     */
    public static void listen(@NotNull String module, @NotNull BiConsumer<UUID, String> handler) {
        PUSHES.put(module, handler);
    }

    /** Every name on the network as of the last refresh; empty until the bridge answers. */
    public static @NotNull Set<String> players() {
        return players;
    }

    /** Nothing to do on a quit any more; kept so the call site reads the same. */
    public static void forget(@NotNull UUID player) {
    }

    private static void tick() {
        if (!available) {
            request((UUID) null, PING, "").thenAccept(reply -> {
                if (reply.reachedProxy() || warned) {
                    return;
                }
                warned = true;
                Plugin plugin = library;
                if (plugin != null) {
                    Debug.of(plugin).warn("No proxy bridge answered on Redis channel \""
                            + Frames.channelOf(prefix, Frames.PROXY) + "\". Install ExyliaProxyUtils"
                            + " on the proxy with the same Redis and key-prefix, or player-proxy: and"
                            + " console-proxy: commands and cross-server teleports will not run.");
                }
            });
            return;
        }
        request((UUID) null, PLAYERS, "").thenAccept(reply -> {
            if (!reply.isOk()) {
                return;
            }
            Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (String name : reply.detail().split(",")) {
                if (!name.isBlank()) {
                    names.add(name.trim());
                }
            }
            players = Set.copyOf(names);
        });
    }

    public static @NotNull CompletableFuture<ProxyReply> request(@Nullable Player carrier,
                                                                 @NotNull String module,
                                                                 @NotNull String payload) {
        return request(carrier == null ? null : carrier.getUniqueId(), module, payload);
    }

    public static @NotNull CompletableFuture<ProxyReply> request(@Nullable UUID about,
                                                                 @NotNull String module,
                                                                 @NotNull String payload) {
        Plugin plugin = library;
        RedisClient client = redis;
        if (plugin == null || client == null) {
            return CompletableFuture.completedFuture(new ProxyReply(ProxyReply.Status.NO_BRIDGE,
                    "no Redis in plugins/ExyliaLib/database.yml, so there is no proxy bridge"));
        }
        if (module.isBlank() || module.indexOf('|') >= 0) {
            throw new IllegalArgumentException("A proxy request needs a module name, without pipes.");
        }
        int id = IDS.incrementAndGet();
        CompletableFuture<ProxyReply> future = new CompletableFuture<>();
        PENDING.put(id, future);
        future.completeOnTimeout(new ProxyReply(ProxyReply.Status.TIMEOUT,
                        "the proxy did not answer in " + TIMEOUT_SECONDS + "s; is ExyliaProxyUtils"
                                + " installed on it, on the same Redis?"), TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((reply, error) -> {
                    PENDING.remove(id);
                    if (reply != null && reply.status() == ProxyReply.Status.TIMEOUT) {
                        available = false;
                    }
                });
        String frame = Frames.request(serverId, about, module, id, payload);
        Tasks.of(plugin).runAsync(() -> {
            try {
                client.publish(Frames.channelOf(prefix, Frames.PROXY), frame);
            } catch (RuntimeException unreachable) {
                future.complete(new ProxyReply(ProxyReply.Status.NO_BRIDGE,
                        "Redis could not carry the request: " + unreachable.getMessage()));
            }
        });
        return future;
    }

    /** On the subscriber thread: every answer and push for this server. */
    private static void onMessage(String raw) {
        Frames.Answer answer;
        try {
            answer = Frames.decode(raw);
        } catch (RuntimeException unreadable) {
            Plugin plugin = library;
            if (plugin != null) {
                Debug.of(plugin).warn("Dropped an unreadable message from the proxy: "
                        + unreadable.getMessage());
            }
            return;
        }
        receive(answer);
    }

    /**
     * Completes the request an answer names, or hands a push to its handler.
     *
     * <p>An unknown id is a late answer, dropped: the request already ended
     * as a timeout.
     */
    static void receive(@NotNull Frames.Answer answer) {
        ProxyReply reply = ProxyReply.ofWire(answer.status(), answer.detail());
        if (PING.equals(answer.module()) && reply.isOk() && !reply.detail().equals(bridge)) {
            bridge = reply.detail();
            Plugin plugin = library;
            if (plugin != null) {
                Debug.of(plugin).log("Proxy bridge: " + reply.detail() + ".");
            }
        }
        available = true;
        if (answer.id() == 0) {
            BiConsumer<UUID, String> handler = PUSHES.get(answer.module());
            if (handler != null && answer.carrier() != null) {
                handler.accept(answer.carrier(), answer.detail());
            }
            return;
        }
        CompletableFuture<ProxyReply> pending = PENDING.remove(answer.id());
        if (pending != null) {
            pending.complete(reply);
        }
    }
}
