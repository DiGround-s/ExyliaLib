package net.exylia.lib.util.teleport.internal;

import net.exylia.lib.database.internal.DatabaseRuntime;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.effect.Ticks;
import net.exylia.lib.proxy.Proxy;
import net.exylia.lib.proxy.ProxyReply;
import net.exylia.lib.redis.RedisSettings;
import net.exylia.lib.redis.internal.RedisClient;
import net.exylia.lib.redis.internal.RedisRuntime;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.teleport.ExyliaLocation;
import net.exylia.lib.util.teleport.TeleportCause;
import net.exylia.lib.util.teleport.TeleportResult;
import net.exylia.lib.util.teleport.TeleportSettings;
import net.exylia.lib.task.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Sending a player to a place that is not on this server.
 *
 * <h2>The order is the whole contract</h2>
 * The destination is written to Redis <em>first</em>, and only a write that
 * came back without throwing is followed by the {@code Connect} message that
 * moves the player. That is the same rule the cache module lives by — store,
 * then announce — and here it is even less forgiving: a {@code Connect} sent
 * before the write is a player standing on the destination server while the
 * key that says where to put them either does not exist yet or never will. They
 * are not teleported, nothing is logged, and the server they left has already
 * forgotten them.
 *
 * <p>A write that throws therefore sends nothing at all. The player stays where
 * they are and is told the handover is unavailable, which is a message they can
 * act on; being silently dumped in another lobby is not.
 *
 * <h2>Why the arrival waits</h2>
 * A player who has just joined is still loading the world they logged into, and
 * moving them in the same tick is how a client ends up in grey void until
 * something nudges it. The wait is {@code cross-server-settle-seconds} and it
 * exists <em>for the client</em> — it is not a race we are betting on. Nothing
 * about correctness depends on its length: the key is read and deleted before
 * the wait begins, so the destination is already ours no matter how long the
 * server takes to get around to the move. ExyliaCommons hardcoded 150ms and
 * relied on it, which is a race with a constant in front of it, and it showed
 * up as players who arrived in the wrong place on a server under load.
 *
 * <h2>Reaching a player rather than a place</h2>
 * A staff member answering a report does not know where the reported player
 * is standing, only who they are. So a queued value may also name a player,
 * and the arriving server finds them itself; which server to hand over to is
 * read from a presence map every server keeps current — one entry per online
 * player, written on join, renewed by a heartbeat, withdrawn on quit, and
 * expiring on its own for a server that died. {@code ConnectOther} does the
 * reverse: the destination is queued under the <em>other</em> player's id and
 * the proxy pulls them here.
 *
 * <h2>Redis absent is not a failure</h2>
 * A server with no Redis configured has no handover, says so once, and is
 * otherwise completely unaffected: every local teleport works exactly as
 * before. Nothing here throws for a missing Redis, and every Redis type is
 * confined to this class, so a server without the library never loads it — the
 * same discipline {@code JedisClient} follows for Jedis itself.
 */
@ApiStatus.Internal
public final class CrossServer {

    /**
     * The channel every proxy listens on, used when the bridge is not there.
     *
     * <p>Named {@code BungeeCord} on every proxy that has ever existed,
     * Velocity included: the name is the protocol, not the software. It never
     * answers, which is why the bridge is preferred whenever it has answered
     * this server: a server name with a typo in it is then a console line
     * rather than a player standing still.
     */
    private static final String CHANNEL = "BungeeCord";

    /** The ExyliaProxyUtils module that moves a player and says whether it did. */
    private static final String CONNECT = "connect";

    /** What the pending key is filed under, after the network's own prefix. */
    private static final String KEY_INFIX = ":teleport:pending:";

    /**
     * What a queued value says when the destination is a player rather than a
     * place: {@code player:<uuid>}. The arriving server finds them itself.
     */
    private static final String FOLLOW_PREFIX = "player:";

    /** Where each player of the network is, after the network's own prefix. */
    private static final String PRESENCE_INFIX = ":players:";

    /**
     * How long a presence entry outlives its last heartbeat.
     *
     * <p>A server that crashes never withdraws its players, so the entries
     * expire instead: a staff member is sent nowhere on the word of a server
     * that died a minute ago. Three heartbeats of slack, so one missed write
     * under load does not make a whole server's players vanish from the map.
     */
    private static final int PRESENCE_TTL_SECONDS = 90;

    /** How often every online player's presence entry is renewed. */
    private static final long HEARTBEAT_TICKS = 20L * 30;

    /** The heartbeat, started on the first join a Redis is there for. */
    private static volatile @Nullable TaskHandle heartbeat;

    private CrossServer() {
        throw new AssertionError("No instances.");
    }

    /**
     * Whether this server can hand a player to another one at all.
     *
     * <p>False without Redis, and that is a configuration answer rather than an
     * error: a single server has nowhere to hand anybody to.
     *
     * @param plugin the plugin asking, whose {@code database.yml} is read
     * @return whether a handover would be attempted
     */
    public static boolean isAvailable(@NotNull Plugin plugin) {
        return client(plugin) != null;
    }

    /**
     * This server's own name on the network.
     *
     * <p>What a stored {@link ExyliaLocation#server()} is compared against, so
     * a destination naming this server is a local teleport rather than a
     * handover to itself.
     *
     * @param plugin the plugin asking
     * @return the configured {@code server-id}
     */
    public static @NotNull String serverId(@NotNull Plugin plugin) {
        return settings(plugin).serverId();
    }

    /**
     * Queues a destination on another server and sends the player there.
     *
     * <p><b>Threading:</b> safe from anywhere. The Redis write is I/O and runs
     * asynchronously; the {@code Connect} message touches a player and runs on
     * the thread that owns them.
     *
     * @param plan what the request decided
     * @return how it ended; {@link TeleportResult#SUCCESS} means the player was
     *         handed over, not that they arrived — the destination server
     *         answers for that half
     */
    public static @NotNull CompletableFuture<TeleportResult> hand(@NotNull TeleportPlan plan) {
        CompletableFuture<TeleportResult> result = new CompletableFuture<>();
        ExyliaLocation destination = plan.crossServer();
        UUID follow = plan.follow();
        Plugin plugin = plan.plugin();
        Debug debug = plan.debug();

        RedisClient redis = client(plugin);
        if ((destination == null && follow == null) || redis == null) {
            debug.warn("A teleport was aimed at " + (destination == null
                    ? "a player on another server" : "server \"" + destination.server() + "\"")
                    + ", but this server has no Redis configured, so it cannot hand anybody"
                    + " over. Turn on database.redis in database.yml on every server of the"
                    + " network.");
            result.complete(TeleportResult.CROSS_SERVER_UNAVAILABLE);
            return result;
        }

        RedisSettings redisSettings = settings(plugin);
        Player player = plan.player();
        String key = keyOf(redisSettings, player.getUniqueId().toString());

        if (follow != null) {
            plan.tasks().runAsync(() -> {
                String server;
                try {
                    server = redis.get(presenceKeyOf(redisSettings, follow));
                } catch (RuntimeException unreachable) {
                    debug.error("Could not look up which server holds " + follow
                            + "; " + player.getName() + " was not moved", unreachable);
                    result.complete(TeleportResult.CROSS_SERVER_UNAVAILABLE);
                    return;
                }
                if (server == null) {
                    result.complete(TeleportResult.TARGET_NOT_FOUND);
                    return;
                }
                if (server.equalsIgnoreCase(redisSettings.serverId())) {
                    // They came here while the request was being described, or
                    // during its countdown. A handover to ourselves would have
                    // the proxy reconnect the mover to the server they are on.
                    plan.tasks().runAtEntity(player, () -> {
                        Player target = Bukkit.getPlayer(follow);
                        if (target == null) {
                            result.complete(TeleportResult.TARGET_NOT_FOUND);
                            return;
                        }
                        Teleporter.teleport(plugin, player, target.getLocation(), plan.cause(),
                                        plan.tasks(), debug, plan.settings().backHistorySize())
                                .thenAccept(result::complete);
                    }, () -> result.complete(TeleportResult.PLAYER_LEFT));
                    return;
                }
                queueThenConnect(plan, redis, key, FOLLOW_PREFIX + follow, server, result);
            });
            return result;
        }
        queueThenConnect(plan, redis, key, destination.toString(), destination.server(), result);
        return result;
    }

    /** Store, then announce: the write that fails sends nothing. */
    private static void queueThenConnect(TeleportPlan plan, RedisClient redis, String key,
                                         String value, @Nullable String target,
                                         CompletableFuture<TeleportResult> result) {
        Plugin plugin = plan.plugin();
        Debug debug = plan.debug();
        Player player = plan.player();
        plan.tasks().runAsync(() -> {
            try {
                redis.set(key, value, plan.settings().crossServerTtlSeconds());
            } catch (RuntimeException unreachable) {
                // Nothing is sent. A Connect after a failed write moves a
                // player to a server that has no idea where to put them, and
                // the one place that could have told them is the one that just
                // failed. Staying put with a message is the better half of a
                // bad situation.
                debug.error("Could not queue a cross-server destination for "
                        + player.getName() + "; they were not moved", unreachable);
                result.complete(TeleportResult.CROSS_SERVER_UNAVAILABLE);
                return;
            }
            // Only now, and on the player's own thread: the message is sent
            // through them.
            plan.tasks().runAtEntity(player,
                    () -> connect(plugin, player, target, debug).thenAccept(result::complete),
                    () -> result.complete(TeleportResult.PLAYER_LEFT));
        });
    }

    /**
     * Pulls a player who is on another server to somebody here.
     *
     * <p>The same contract as {@link #hand}, mirrored: the destination is
     * queued under the <em>target's</em> id first, and only then is the proxy
     * asked to move them with {@code ConnectOther}, which is sent through the
     * player doing the pulling. A target the network does not know is
     * {@link TeleportResult#TARGET_NOT_FOUND} before anything is written.
     *
     * <p><b>Threading:</b> call from the puller's own thread; their location
     * is read here.
     *
     * @param plugin     whoever asked
     * @param to         who the target is pulled to
     * @param target     who is pulled
     * @param targetName their name, which is what the proxy knows them by
     * @param settings   how long the queued destination lasts
     * @return {@link TeleportResult#SUCCESS} once the proxy was told; the
     *         destination server answers for the arrival
     * @since 1.98.0
     */
    public static @NotNull CompletableFuture<TeleportResult> bring(
            @NotNull Plugin plugin, @NotNull Player to, @NotNull UUID target,
            @NotNull String targetName, @NotNull TeleportSettings settings) {
        CompletableFuture<TeleportResult> result = new CompletableFuture<>();
        Debug debug = Debug.of(plugin);
        RedisClient redis = client(plugin);
        if (redis == null) {
            debug.warn("A teleport tried to pull " + targetName + " from another server, but"
                    + " this server has no Redis configured. Turn on database.redis in"
                    + " database.yml on every server of the network.");
            result.complete(TeleportResult.CROSS_SERVER_UNAVAILABLE);
            return result;
        }
        RedisSettings redisSettings = settings(plugin);
        String here = redisSettings.serverId();
        String key = keyOf(redisSettings, target.toString());
        String value = ExyliaLocation.of(here, to.getLocation()).toString();
        TaskScheduler tasks = Tasks.of(plugin);

        tasks.runAsync(() -> {
            try {
                if (redis.get(presenceKeyOf(redisSettings, target)) == null) {
                    result.complete(TeleportResult.TARGET_NOT_FOUND);
                    return;
                }
                redis.set(key, value, settings.crossServerTtlSeconds());
            } catch (RuntimeException unreachable) {
                debug.error("Could not queue a cross-server destination for " + targetName
                        + "; they were not moved", unreachable);
                result.complete(TeleportResult.CROSS_SERVER_UNAVAILABLE);
                return;
            }
            tasks.runAtEntity(to,
                    () -> connectOther(plugin, to, target, targetName, here, debug)
                            .thenAccept(result::complete),
                    () -> result.complete(TeleportResult.PLAYER_LEFT));
        });
        return result;
    }

    // ---------------------------------------------------------------- presence

    /**
     * Which server of the network a player is on.
     *
     * <p>Answered from this server alone when they are here, so a single
     * server never asks Redis a question it can answer itself; empty for a
     * player the network does not know, and always empty without Redis.
     *
     * @param plugin whoever asks
     * @param player who to find
     * @return the server's {@code server-id}, or empty
     * @since 1.98.0
     */
    public static @NotNull CompletableFuture<Optional<String>> serverOf(@NotNull Plugin plugin,
                                                                       @NotNull UUID player) {
        if (Bukkit.getPlayer(player) != null) {
            return CompletableFuture.completedFuture(Optional.of(serverId(plugin)));
        }
        RedisClient redis = client(plugin);
        if (redis == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        RedisSettings redisSettings = settings(plugin);
        CompletableFuture<Optional<String>> result = new CompletableFuture<>();
        Tasks.of(plugin).runAsync(() -> {
            try {
                result.complete(Optional.ofNullable(redis.get(presenceKeyOf(redisSettings, player))));
            } catch (RuntimeException unreachable) {
                Debug.of(plugin).error("Could not look up which server holds " + player, unreachable);
                result.complete(Optional.empty());
            }
        });
        return result;
    }

    /**
     * Records that a player who just joined is on this server.
     *
     * <p>Also starts the heartbeat the first time there is a Redis to write
     * to, so a server that never configures one never runs a timer for it.
     */
    public static void announce(@NotNull Plugin library, @NotNull Player player) {
        RedisClient redis = client(library);
        if (redis == null) {
            return;
        }
        RedisSettings redisSettings = settings(library);
        UUID id = player.getUniqueId();
        Tasks.of(library).runAsync(() -> write(library, redis, redisSettings, List.of(id)));
        if (heartbeat == null) {
            synchronized (CrossServer.class) {
                if (heartbeat == null) {
                    heartbeat = Tasks.of(library).runTimer(HEARTBEAT_TICKS, HEARTBEAT_TICKS,
                            () -> renew(library));
                }
            }
        }
    }

    /**
     * Forgets a player who just left, unless another server already has them.
     *
     * <p>A proxy connects the player to the next server before it disconnects
     * them from this one, so the other server's write may already be there;
     * deleting blindly would erase it. Read, compare, delete is not atomic, and
     * the window is one round trip on a server the player just left.
     */
    public static void withdraw(@NotNull Plugin library, @NotNull Player player) {
        RedisClient redis = client(library);
        if (redis == null) {
            return;
        }
        RedisSettings redisSettings = settings(library);
        String key = presenceKeyOf(redisSettings, player.getUniqueId());
        Tasks.of(library).runAsync(() -> {
            try {
                if (redisSettings.serverId().equalsIgnoreCase(redis.get(key))) {
                    redis.delete(List.of(key));
                }
            } catch (RuntimeException unreachable) {
                // The entry expires on its own; the heartbeat is what makes it
                // safe to say nothing here.
            }
        });
    }

    /** Ends the heartbeat, on shutdown. */
    public static synchronized void stop() {
        TaskHandle running = heartbeat;
        heartbeat = null;
        if (running != null) {
            running.cancel();
        }
    }

    /** Renews every online player's entry; the snapshot is taken on the global thread. */
    private static void renew(Plugin library) {
        RedisClient redis = client(library);
        if (redis == null) {
            return;
        }
        List<UUID> online = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
        }
        if (online.isEmpty()) {
            return;
        }
        RedisSettings redisSettings = settings(library);
        Tasks.of(library).runAsync(() -> write(library, redis, redisSettings, online));
    }

    private static void write(Plugin library, RedisClient redis, RedisSettings settings,
                              List<UUID> players) {
        try {
            for (UUID id : players) {
                redis.set(presenceKeyOf(settings, id), settings.serverId(), PRESENCE_TTL_SECONDS);
            }
        } catch (RuntimeException unreachable) {
            Debug.of(library).error("Could not record which players are on this server", unreachable);
        }
    }

    /**
     * Moves a player who has just joined to whatever was queued for them.
     *
     * <p>The read and the delete happen asynchronously and <em>before</em> the
     * settle wait, so the destination belongs to this server from the moment it
     * is found. Only the move itself waits.
     *
     * <p>An absent key is the normal case for every ordinary join on the
     * server, so it does exactly nothing — no log line, no task, no move.
     *
     * @param library  the library plugin, which owns the join listener
     * @param player   who joined
     * @param settings how long to let the client settle
     */
    public static void claim(@NotNull Plugin library, @NotNull Player player,
                             @NotNull TeleportSettings settings) {
        RedisClient redis = client(library);
        if (redis == null) {
            return;
        }
        RedisSettings redisSettings = settings(library);
        Debug debug = Debug.of(library);
        TaskScheduler tasks = Tasks.of(library);
        String key = keyOf(redisSettings, player.getUniqueId().toString());

        tasks.runAsync(() -> {
            String stored;
            try {
                stored = redis.get(key);
                if (stored == null) {
                    // Every join that was not a handover. Silent on purpose:
                    // this runs for every player on the server.
                    return;
                }
                // Taken before the wait, so the destination is ours whatever
                // the server does next. Leaving it would move the player again
                // on their next login.
                redis.delete(List.of(key));
            } catch (RuntimeException unreachable) {
                debug.error("Could not read a queued cross-server destination for "
                        + player.getName(), unreachable);
                return;
            }

            long settle = Math.max(1L, Ticks.fromSeconds(settings.crossServerSettleSeconds()));
            if (stored.startsWith(FOLLOW_PREFIX)) {
                UUID target;
                try {
                    target = UUID.fromString(stored.substring(FOLLOW_PREFIX.length()));
                } catch (IllegalArgumentException unreadable) {
                    debug.error("A queued cross-server destination for " + player.getName()
                            + " could not be read: " + stored, unreadable);
                    return;
                }
                tasks.runAtEntityLater(player, settle, () -> arriveAt(library, player, target));
                return;
            }

            ExyliaLocation destination;
            try {
                destination = ExyliaLocation.fromString(stored);
            } catch (IllegalArgumentException unreadable) {
                debug.error("A queued cross-server destination for " + player.getName()
                        + " could not be read: " + stored, unreadable);
                return;
            }
            tasks.runAtEntityLater(player, settle, () -> arrive(library, player, destination));
        });
    }

    /** Puts the arriving player where the other server said, once loaded. */
    private static void arrive(Plugin library, Player player, ExyliaLocation destination) {
        Debug debug = Debug.of(library);
        Location live = destination.toBukkitLocation();
        if (live == null) {
            // The other server named a world this one does not have. A message
            // rather than silence: the player is standing in a lobby wondering
            // why the arena never opened, and the owner can fix the name.
            debug.warn("A player arrived for a teleport into world \"" + destination.world()
                    + "\", which is not loaded on this server.");
            return;
        }
        Teleporter.teleport(library, player, live, TeleportCause.CROSS_SERVER,
                Tasks.of(library), debug, 0);
    }

    /** Puts the arriving player next to whoever they came for, if they are still here. */
    private static void arriveAt(Plugin library, Player player, UUID target) {
        Debug debug = Debug.of(library);
        Player found = Bukkit.getPlayer(target);
        if (found == null) {
            // They left, or moved on, between the handover and the arrival. A
            // console line rather than silence; the mover is standing in a
            // lobby wondering why nothing happened.
            debug.warn(player.getName() + " arrived to reach player " + target
                    + ", who is no longer on this server.");
            return;
        }
        Teleporter.teleport(library, player, found.getLocation(), TeleportCause.CROSS_SERVER,
                Tasks.of(library), debug, 0);
    }

    /**
     * Tells the proxy to move somebody else here, through the player asking.
     *
     * <p>Through the bridge when ExyliaProxyUtils has answered this server,
     * which is the only way to learn whether the move happened; otherwise
     * {@code ConnectOther} on the {@code BungeeCord} channel, which every
     * proxy understands and none of them answers.
     */
    private static CompletableFuture<TeleportResult> connectOther(Plugin plugin, Player through,
                                                                  UUID target, String targetName,
                                                                  String here, Debug debug) {
        if (Proxy.isAvailable()) {
            return Proxy.request(through, CONNECT, here + '|' + target).thenApply(reply ->
                    reply.reachedProxy()
                            ? fromBridge(reply, targetName, here, debug, TeleportResult.TARGET_NOT_FOUND)
                            : legacyConnectOther(plugin, through, targetName, here, debug));
        }
        return CompletableFuture.completedFuture(
                legacyConnectOther(plugin, through, targetName, here, debug));
    }

    /**
     * Tells the proxy to move the player.
     *
     * <p>Same two roads as {@link #connectOther}: the bridge when it is there,
     * {@code Connect} on the {@code BungeeCord} channel when it is not.
     */
    private static CompletableFuture<TeleportResult> connect(Plugin plugin, Player player,
                                                             @Nullable String target, Debug debug) {
        if (target == null) {
            // A local place should never have reached here; a plan that names
            // no server is a bug rather than a misconfiguration.
            debug.warn("A cross-server teleport named no server; " + player.getName()
                    + " was not moved.");
            return CompletableFuture.completedFuture(TeleportResult.CROSS_SERVER_UNAVAILABLE);
        }
        if (Proxy.isAvailable()) {
            return Proxy.request(player, CONNECT, target).thenApply(reply ->
                    reply.reachedProxy()
                            ? fromBridge(reply, player.getName(), target, debug, TeleportResult.PLAYER_LEFT)
                            : legacyConnect(plugin, player, target, debug));
        }
        return CompletableFuture.completedFuture(legacyConnect(plugin, player, target, debug));
    }

    /**
     * What the proxy's answer means for the teleport.
     *
     * <p>A refusal that starts with {@code no server} is the one worth a
     * console line every time: it is a name in a config file, and nothing
     * else will ever say so. Any other refusal is about the player, and the
     * caller says what a missing player means for it.
     */
    private static TeleportResult fromBridge(ProxyReply reply, String who, String server,
                                             Debug debug, TeleportResult ifPlayerMissing) {
        if (reply.isOk()) {
            return TeleportResult.SUCCESS;
        }
        if (reply.status() == ProxyReply.Status.REJECTED && !reply.detail().startsWith("no server")) {
            return ifPlayerMissing;
        }
        debug.warn("The proxy did not move " + who + " to server \"" + server + "\": "
                + reply.detail());
        return TeleportResult.CROSS_SERVER_UNAVAILABLE;
    }

    /** {@code ConnectOther} with the target's name and this server's name; the proxy knows players by name. */
    private static TeleportResult legacyConnectOther(Plugin plugin, Player through, String targetName,
                                                     String here, Debug debug) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream message = new DataOutputStream(bytes);
            message.writeUTF("ConnectOther");
            message.writeUTF(targetName);
            message.writeUTF(here);
            through.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
            return TeleportResult.SUCCESS;
        } catch (IOException | RuntimeException refused) {
            debug.error("Could not pull " + targetName + " to server \"" + here
                    + "\"; the destination is queued but the proxy was not told", refused);
            return TeleportResult.CROSS_SERVER_UNAVAILABLE;
        }
    }

    /**
     * {@code Connect} with the target server's name, written as a UTF string
     * pair on the {@code BungeeCord} channel.
     */
    private static TeleportResult legacyConnect(Plugin plugin, Player player, String target,
                                                Debug debug) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream message = new DataOutputStream(bytes);
            message.writeUTF("Connect");
            message.writeUTF(target);
            player.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
            return TeleportResult.SUCCESS;
        } catch (IOException | RuntimeException refused) {
            // The destination is still queued and still has its TTL, so a
            // player who walks through a portal themselves within it still
            // lands correctly. Reported so the owner knows the channel is the
            // part that is broken.
            debug.error("Could not send " + player.getName() + " to server \"" + target
                    + "\"; the destination is queued but the proxy was not told", refused);
            return TeleportResult.CROSS_SERVER_UNAVAILABLE;
        }
    }

    /**
     * The key one player's queued destination lives under.
     *
     * <p>Prefixed with the network's own prefix, exactly as every cached row
     * is: two networks sharing one Redis must not hand each other's players
     * around.
     */
    private static String keyOf(RedisSettings settings, String uuid) {
        return settings.keyPrefix() + KEY_INFIX + uuid;
    }

    /** The key one player's current server is filed under. */
    private static String presenceKeyOf(RedisSettings settings, UUID player) {
        return settings.keyPrefix() + PRESENCE_INFIX + player;
    }

    /**
     * The shared connection, or {@code null} when there is none.
     *
     * <p>Always the one the cache already opened rather than a second pool:
     * two pools against one Redis is twice the connections for one server.
     */
    private static @Nullable RedisClient client(Plugin plugin) {
        try {
            return RedisRuntime.client(plugin, settings(plugin));
        } catch (RuntimeException | LinkageError absent) {
            // A server with no Redis library at all. Never fatal: the module
            // simply has no handover, and every local teleport is unaffected.
            Debug.of(plugin).warn("Redis is configured but its library is not installed,"
                    + " so cross-server teleports are unavailable: " + absent.getMessage());
            return null;
        }
    }

    /** This consumer's Redis block, read from its {@code database.yml}. */
    private static RedisSettings settings(Plugin plugin) {
        try {
            return DatabaseRuntime.redis(plugin);
        } catch (RuntimeException | LinkageError unavailable) {
            // A plugin that never touched the database module has no runtime
            // to ask. That is a server without Redis, which is a supported
            // arrangement rather than a fault.
            return new RedisSettings();
        }
    }
}
