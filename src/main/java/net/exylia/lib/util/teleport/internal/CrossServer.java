package net.exylia.lib.util.teleport.internal;

import net.exylia.lib.database.internal.DatabaseRuntime;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.effect.Ticks;
import net.exylia.lib.redis.RedisSettings;
import net.exylia.lib.redis.internal.RedisClient;
import net.exylia.lib.redis.internal.RedisRuntime;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.teleport.ExyliaLocation;
import net.exylia.lib.util.teleport.TeleportCause;
import net.exylia.lib.util.teleport.TeleportResult;
import net.exylia.lib.util.teleport.TeleportSettings;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
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
     * The channel a proxy listens on.
     *
     * <p>Named {@code BungeeCord} on every proxy that has ever existed,
     * Velocity included: the name is the protocol, not the software.
     */
    private static final String CHANNEL = "BungeeCord";

    /** What the pending key is filed under, after the network's own prefix. */
    private static final String KEY_INFIX = ":teleport:pending:";

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
        Plugin plugin = plan.plugin();
        Debug debug = plan.debug();

        RedisClient redis = client(plugin);
        if (destination == null || redis == null) {
            debug.warn("A teleport was aimed at server \"" + (destination == null
                    ? "?" : destination.server()) + "\", but this server has no Redis"
                    + " configured, so it cannot hand anybody over. Turn on database.redis"
                    + " in database.yml on every server of the network.");
            result.complete(TeleportResult.CROSS_SERVER_UNAVAILABLE);
            return result;
        }

        RedisSettings redisSettings = settings(plugin);
        TeleportSettings teleportSettings = plan.settings();
        Player player = plan.player();
        String key = keyOf(redisSettings, player.getUniqueId().toString());
        String value = destination.toString();
        String target = destination.server();

        plan.tasks().runAsync(() -> {
            try {
                redis.set(key, value, teleportSettings.crossServerTtlSeconds());
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
                    () -> result.complete(connect(plugin, player, target, debug)),
                    () -> result.complete(TeleportResult.PLAYER_LEFT));
        });
        return result;
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

            ExyliaLocation destination;
            try {
                destination = ExyliaLocation.fromString(stored);
            } catch (IllegalArgumentException unreadable) {
                debug.error("A queued cross-server destination for " + player.getName()
                        + " could not be read: " + stored, unreadable);
                return;
            }

            long settle = Math.max(1L, Ticks.fromSeconds(settings.crossServerSettleSeconds()));
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

    /**
     * Tells the proxy to move the player, in the format it has always used.
     *
     * <p>{@code Connect} with the target server's name, written as a UTF string
     * pair on the {@code BungeeCord} channel.
     */
    private static TeleportResult connect(Plugin plugin, Player player, @Nullable String target,
                                          Debug debug) {
        if (target == null) {
            // A local place should never have reached here; a plan that names
            // no server is a bug rather than a misconfiguration.
            debug.warn("A cross-server teleport named no server; " + player.getName()
                    + " was not moved.");
            return TeleportResult.CROSS_SERVER_UNAVAILABLE;
        }
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
