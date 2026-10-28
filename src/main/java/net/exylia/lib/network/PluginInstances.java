package net.exylia.lib.network;

import net.exylia.lib.redis.Channel;
import net.exylia.lib.redis.Channels;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.teleport.TeleportResult;
import net.exylia.lib.util.teleport.Teleports;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * One plugin's pool of instances in one group; see {@link Instances}.
 *
 * <p>Heartbeats go to every server of the group on a channel; each server
 * keeps the last one of every other instance in memory, stamped with its own
 * clock when it arrived, so reading the pool ({@link #instances()},
 * {@link #pick}) is a map read, safe from any thread and every second from a
 * menu. An instance that stops publishing is gone once its last heartbeat is
 * three periods old; one that disables says so at once.
 *
 * @since 1.383.0
 */
public final class PluginInstances {

    /** How often a heartbeat goes out by default. */
    public static final Duration DEFAULT_PERIOD = Duration.ofSeconds(2);
    /** How long a player handed over may take to turn up here. */
    static final Duration ARRIVAL_WAIT = Duration.ofSeconds(30);
    private static final String GONE = "gone" + '\t';

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final PluginNetwork network;
    private final Channel beats;
    private final String arrivals;
    private final Map<String, Instance> others = new ConcurrentHashMap<>();
    /** Notes for players handed over here, by player, until they arrive or the wait ends. */
    private final Map<UUID, String> expected = new ConcurrentHashMap<>();
    private volatile Supplier<Instances.Status> status;
    private volatile Duration period = DEFAULT_PERIOD;
    private volatile TaskHandle timer;
    private volatile BiConsumer<Player, String> arrival;

    PluginInstances(@NotNull Plugin plugin, @NotNull String group) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
        this.network = Network.of(plugin);
        this.beats = Channels.of(plugin).channel("instances:" + group);
        this.arrivals = "instances-arrive:" + group;
        beats.subscribe(message -> {
            if (message.local()) return;
            String payload = message.payload();
            if (payload.startsWith(GONE)) {
                others.remove(payload.substring(GONE.length()).toLowerCase(java.util.Locale.ROOT));
                return;
            }
            Instance instance = Instances.decode(payload, System.currentTimeMillis());
            if (instance != null && !network.isThisServer(instance.server())) {
                others.put(instance.server().toLowerCase(java.util.Locale.ROOT), instance);
            }
        });
        network.on(arrivals, message -> {
            String payload = message.payload();
            int split = payload.indexOf('|');
            if (split <= 0) return;
            UUID player;
            try {
                player = UUID.fromString(payload.substring(0, split));
            } catch (IllegalArgumentException unreadable) {
                return;
            }
            expect(player, payload.substring(split + 1));
        });
    }

    // ------------------------------------------------------------------ publishing

    /**
     * Publishes this server's status on every heartbeat, from now until the
     * plugin disables or {@link #stop()}. A second call replaces the first.
     *
     * <p>The supplier runs off the main thread every period and again on every
     * {@link #instances()} for this server's own entry: read memory (a counter,
     * a map), never a database or the world.
     *
     * @param status what this server is now
     * @return this pool
     */
    public @NotNull PluginInstances publish(@NotNull Supplier<Instances.Status> status) {
        this.status = status;
        restart();
        return this;
    }

    /**
     * How often the heartbeat goes out; {@link #DEFAULT_PERIOD} until set. An
     * instance is forgotten three periods after its last one, so every server
     * of a group should use the same period.
     *
     * @param period at least one second
     * @return this pool
     */
    public @NotNull PluginInstances period(@NotNull Duration period) {
        this.period = period.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : period;
        if (status != null) restart();
        return this;
    }

    /**
     * Stops publishing and tells the group this server is gone, so nobody is
     * sent here while it shuts down. Call from {@code onDisable}; one round
     * trip on the calling thread.
     */
    public void stop() {
        TaskHandle running = timer;
        timer = null;
        if (running != null) running.cancel();
        if (status == null) return;
        status = null;
        beats.publish(GONE + network.serverId());
    }

    private void restart() {
        TaskHandle running = timer;
        if (running != null) running.cancel();
        long ticks = Math.max(20, period.toMillis() / 50);
        timer = tasks.runAsyncTimer(0, ticks, () -> {
            Supplier<Instances.Status> current = status;
            if (current == null) return;
            try {
                beats.publish(Instances.encode(network.serverId(), current.get(), tps()));
            } catch (RuntimeException failure) {
                plugin.getLogger().warning("Could not publish this server's status: " + failure);
            }
        });
    }

    // ------------------------------------------------------------------ reading

    /**
     * Every instance of the group heard from within three periods, this
     * server's own first when it publishes (read from its supplier now, never
     * from a heartbeat). Memory only.
     *
     * @return the live instances
     */
    public @NotNull List<Instance> instances() {
        long now = System.currentTimeMillis();
        long maxAge = maxAge();
        List<Instance> live = new ArrayList<>();
        self(now).ifPresent(live::add);
        others.values().forEach(instance -> {
            if (Instances.isFresh(instance, now, maxAge)) live.add(instance);
        });
        return live;
    }

    /**
     * The best instance for one more player, this server included and
     * preferred on a tie; see {@link Instances#pick}.
     *
     * @param mode how to choose
     * @param part the part they want (an arena), or {@code null} for any
     * @return where to send them, or empty when no instance takes them
     */
    public @NotNull Optional<Instance> pick(@NotNull Instances.Mode mode, @Nullable String part) {
        return Instances.pick(instances(), mode, part, network.serverId(), System.currentTimeMillis(), maxAge());
    }

    /** Players in a part across the group, or in total for {@code null}. Memory only. */
    public int players(@Nullable String part) {
        return instances().stream().mapToInt(instance -> instance.players(part)).sum();
    }

    /** Whether a server name is this server; see {@link PluginNetwork#isThisServer}. */
    public boolean isThisServer(@Nullable String server) {
        return network.isThisServer(server);
    }

    private Optional<Instance> self(long now) {
        Supplier<Instances.Status> current = status;
        if (current == null) return Optional.empty();
        Instances.Status here = current.get();
        return Optional.of(new Instance(network.serverId(), here.accepting(), tps(), here.total(), here.parts(), now));
    }

    private long maxAge() {
        return period.toMillis() * 3;
    }

    private static double tps() {
        try {
            return Math.min(20, Bukkit.getTPS()[0]);
        } catch (RuntimeException | LinkageError unavailable) {
            return 20;
        }
    }

    // ------------------------------------------------------------------ handing over

    /**
     * What runs once a player handed over by {@link #handover} is online here:
     * on their thread, with the note the sending server wrote. Runs after
     * their join, never during it; a plugin that restores players on join
     * waits for its own restore before placing them.
     *
     * @param handler the player and the note
     * @return this pool
     */
    public @NotNull PluginInstances onArrival(@NotNull BiConsumer<Player, String> handler) {
        this.arrival = handler;
        return this;
    }

    /**
     * Whether this player is on their way here through a handover, and with
     * what note: for a join listener that would otherwise place them itself.
     *
     * @param player who
     * @return the note, or empty
     */
    public @NotNull Optional<String> expected(@NotNull UUID player) {
        return Optional.ofNullable(expected.get(player));
    }

    /**
     * Sends a player to another instance with a note its
     * {@link #onArrival} reads: the note first, then the move, so it does not
     * matter which lands first. A handover to this server runs the arrival
     * here at once.
     *
     * @param player who to send
     * @param server the instance, as {@link Instance#server()} names it
     * @param note   anything, for the arrival handler there (an arena id)
     * @return the move's result; {@link TeleportResult#CROSS_SERVER_UNAVAILABLE}
     *         when no Redis carries the note
     */
    public @NotNull CompletableFuture<TeleportResult> handover(@NotNull Player player, @NotNull String server,
                                                               @NotNull String note) {
        if (network.isThisServer(server)) {
            expect(player.getUniqueId(), note);
            return CompletableFuture.completedFuture(TeleportResult.SUCCESS);
        }
        CompletableFuture<TeleportResult> result = new CompletableFuture<>();
        tasks.runAsync(() -> {
            try {
                Delivery delivery = network.send(server, arrivals, player.getUniqueId() + "|" + note);
                if (delivery != Delivery.SENT) {
                    result.complete(TeleportResult.CROSS_SERVER_UNAVAILABLE);
                    return;
                }
                Teleports.of(plugin).toServer(player, server).whenComplete((moved, failure) ->
                        result.complete(failure != null ? TeleportResult.CROSS_SERVER_UNAVAILABLE : moved));
            } catch (RuntimeException failure) {
                result.complete(TeleportResult.CROSS_SERVER_UNAVAILABLE);
            }
        });
        return result;
    }

    private void expect(UUID player, String note) {
        expected.put(player, note);
        network.awaitArrival(List.of(player), ARRIVAL_WAIT).thenAccept(arrived -> {
            // Only the latest note: a second handover meanwhile has its own wait.
            if (!expected.remove(player, note) || !arrived.complete()) return;
            Player online = Bukkit.getPlayer(player);
            BiConsumer<Player, String> handler = arrival;
            if (online == null || handler == null) return;
            tasks.runAtEntity(online, () -> {
                try {
                    handler.accept(online, note);
                } catch (RuntimeException failure) {
                    plugin.getLogger().warning("An arrival handler failed for " + online.getName() + ": " + failure);
                }
            });
        });
    }
}
