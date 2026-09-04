package net.exylia.lib.proxy.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.proxy.ProxyReply;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.task.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The bridge's this-side half: one channel, one map of what is in flight.
 *
 * <p>Registered once against the library, like the teleport channel, because
 * a plugin message is sent <em>by</em> a plugin and only one needs to own the
 * channel. Requests are numbered from one counter, answers are matched by
 * that number, and everything that can go wrong on the way back — the proxy
 * silent, the carrier gone — completes the future with a reply that says so
 * rather than leaving it hanging.
 */
@ApiStatus.Internal
public final class ProxyRuntime implements PluginMessageListener {

    /** How long the proxy has to answer before the request is given up on. */
    static final long TIMEOUT_SECONDS = 5;

    /** The module the proxy answers with its own name and version. */
    static final String PING = "ping";

    /** The module the proxy answers with every connected name. */
    static final String PLAYERS = "players";

    /**
     * How often the network's player list is refreshed.
     *
     * <p>Ten seconds: a tab completion is a convenience, and a name that is
     * a few seconds stale costs a "not online" line rather than anything
     * worse. One plugin message per period, through whoever is online.
     */
    private static final long PLAYERS_TICKS = 20L * 10;

    /**
     * How long after a join the ping goes out.
     *
     * <p>A second, so the client is past the configuration phase and the
     * proxy has a server to answer to.
     */
    private static final long PING_DELAY_TICKS = 20L;

    private static final AtomicInteger IDS = new AtomicInteger();
    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();
    private static volatile @Nullable Plugin library;
    private static volatile boolean available;
    private static volatile @Nullable String bridge;
    private static volatile boolean warned;
    private static volatile Set<String> players = Set.of();
    private static volatile @Nullable TaskHandle refresh;

    private record Pending(UUID carrier, CompletableFuture<ProxyReply> future) {
    }

    private ProxyRuntime() {
    }

    /** Registers the channel in both directions, against the library. */
    public static synchronized void init(@NotNull Plugin plugin) {
        if (library != null) {
            return;
        }
        library = plugin;
        try {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, Wire.CHANNEL);
            plugin.getServer().getMessenger()
                    .registerIncomingPluginChannel(plugin, Wire.CHANNEL, new ProxyRuntime());
            refresh = Tasks.of(plugin).runTimer(PLAYERS_TICKS, PLAYERS_TICKS, ProxyRuntime::refreshPlayers);
        } catch (RuntimeException refused) {
            // A server with no messenger. Never fatal: the proxy is the only
            // thing out of reach, and every request will say so.
            library = null;
            Debug.of(plugin).warn("Could not register the proxy channel; proxy commands"
                    + " are unavailable: " + refused.getMessage());
        }
    }

    /** Fails everything still in flight and forgets the channel; on shutdown. */
    public static synchronized void shutdown() {
        Plugin plugin = library;
        library = null;
        available = false;
        bridge = null;
        players = Set.of();
        TaskHandle running = refresh;
        refresh = null;
        if (running != null) {
            running.cancel();
        }
        for (Pending pending : PENDING.values()) {
            pending.future().complete(new ProxyReply(ProxyReply.Status.NO_BRIDGE,
                    "the server is shutting down"));
        }
        PENDING.clear();
        if (plugin != null) {
            try {
                plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, Wire.CHANNEL);
                plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, Wire.CHANNEL);
            } catch (RuntimeException ignored) {
                // Shutting down: a messenger that will not let go is not worth a line.
            }
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    public static @NotNull Optional<String> bridge() {
        return Optional.ofNullable(bridge);
    }

    /** Every name on the network as of the last refresh; empty until the bridge answers. */
    public static @NotNull Set<String> players() {
        return players;
    }

    /**
     * Asks the proxy who is connected, through whoever is here.
     *
     * <p>On the global thread, where the online list is read. Nothing is
     * asked while the bridge is unknown or the server is empty: without a
     * player there is no connection to ask through, and without a bridge
     * the question would only time out every ten seconds.
     */
    private static void refreshPlayers() {
        if (!available) {
            players = Set.of();
            return;
        }
        Player carrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (carrier == null) {
            players = Set.of();
            return;
        }
        request(carrier, PLAYERS, "").thenAccept(reply -> {
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

    /**
     * Asks the proxy who is there, through a player who just joined.
     *
     * <p>Only while the bridge is not known to be listening: once it has
     * answered, every later join costs nothing. A request that times out
     * later marks it unknown again, so the next join asks again.
     */
    public static void pingOnJoin(@NotNull Player player) {
        Plugin plugin = library;
        if (plugin == null || available) {
            return;
        }
        Tasks.of(plugin).runAtEntityLater(player, PING_DELAY_TICKS, () -> {
            if (!available) {
                request(player, PING, "").thenAccept(reply -> {
                    if (reply.reachedProxy()) {
                        return;
                    }
                    if (!warned) {
                        warned = true;
                        Debug.of(plugin).warn("No proxy bridge answered on \"" + Wire.CHANNEL
                                + "\". Install ExyliaProxyUtils on the proxy, or player-proxy:"
                                + " and console-proxy: commands will not run.");
                    }
                });
            }
        });
    }

    /** Fails what a player who just left was carrying; nothing will answer it now. */
    public static void forget(@NotNull UUID player) {
        if (PENDING.isEmpty()) {
            return;
        }
        PENDING.entrySet().removeIf(entry -> {
            if (!entry.getValue().carrier().equals(player)) {
                return false;
            }
            entry.getValue().future().complete(new ProxyReply(ProxyReply.Status.NO_PLAYER,
                    "the player carrying the request left"));
            return true;
        });
    }

    public static @NotNull CompletableFuture<ProxyReply> request(@NotNull Player carrier,
                                                                 @NotNull String module,
                                                                 @NotNull String payload) {
        Plugin plugin = library;
        if (plugin == null) {
            return CompletableFuture.completedFuture(new ProxyReply(ProxyReply.Status.NO_BRIDGE,
                    "the proxy channel is not registered on this server"));
        }
        if (module.isBlank()) {
            throw new IllegalArgumentException("A proxy request needs a module name.");
        }
        int id = IDS.incrementAndGet();
        CompletableFuture<ProxyReply> future = new CompletableFuture<>();
        PENDING.put(id, new Pending(carrier.getUniqueId(), future));
        future.completeOnTimeout(new ProxyReply(ProxyReply.Status.TIMEOUT,
                        "the proxy did not answer in " + TIMEOUT_SECONDS + "s; is ExyliaProxyUtils"
                                + " installed on it?"), TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((reply, error) -> {
                    PENDING.remove(id);
                    if (reply != null && reply.status() == ProxyReply.Status.TIMEOUT) {
                        available = false;
                    }
                });
        byte[] bytes = Wire.encode(new Wire.Request(module, id, payload));
        Runnable send = () -> {
            try {
                if (!carrier.isOnline()) {
                    forget(carrier.getUniqueId());
                    return;
                }
                carrier.sendPluginMessage(plugin, Wire.CHANNEL, bytes);
            } catch (RuntimeException refused) {
                future.complete(new ProxyReply(ProxyReply.Status.FAILED,
                        "could not send through " + carrier.getName() + ": " + refused.getMessage()));
            }
        };
        var tasks = Tasks.of(plugin);
        if (tasks.isOwnedBy(carrier)) {
            send.run();
        } else {
            tasks.runAtEntity(carrier, send, () -> forget(carrier.getUniqueId()));
        }
        return future;
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player,
                                        byte @NotNull [] message) {
        if (!Wire.CHANNEL.equals(channel)) {
            return;
        }
        Wire.Answer answer;
        try {
            answer = Wire.decode(message);
        } catch (IOException | RuntimeException unreadable) {
            Plugin plugin = library;
            if (plugin != null) {
                Debug.of(plugin).warn("Dropped an unreadable message from the proxy through "
                        + player.getName() + ": " + unreadable.getMessage());
            }
            return;
        }
        receive(answer);
    }

    /** Completes the request an answer names; an unknown id is a late answer, dropped. */
    static void receive(@NotNull Wire.Answer answer) {
        ProxyReply reply = ProxyReply.ofWire(answer.status(), answer.detail());
        if (PING.equals(answer.module()) && reply.isOk()) {
            String introduced = reply.detail();
            if (!available || !introduced.equals(bridge)) {
                bridge = introduced;
                Plugin plugin = library;
                if (plugin != null) {
                    Debug.of(plugin).log("Proxy bridge: " + introduced + ".");
                }
            }
        }
        available = true;
        Pending pending = PENDING.remove(answer.id());
        if (pending != null) {
            pending.future().complete(reply);
        }
    }
}
