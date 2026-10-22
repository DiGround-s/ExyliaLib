package net.exylia.lib.network;

import net.exylia.lib.proxy.Proxy;
import net.exylia.lib.proxy.ProxyPlayer;
import net.exylia.lib.redis.Channel;
import net.exylia.lib.redis.Channels;
import net.exylia.lib.redis.Message;
import net.exylia.lib.redis.Redis;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.teleport.internal.CrossServer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * One plugin's addressed messages; see {@link Network}.
 *
 * <p>Every server of the network listening on a channel name is the same
 * plugin on another backend: names are scoped by plugin, like
 * {@link Channels}.
 *
 * @since 1.347.0
 */
public final class PluginNetwork {

    /** Between the target and the payload; a server name never holds one. */
    private static final char SEPARATOR = '|';

    private final Plugin plugin;

    PluginNetwork(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * This server's name: the one the proxy addresses it by when the bridge is
     * configured, the plugin's Redis {@code server-id} otherwise. What
     * {@link Message#sender()} carries is the {@code server-id}; on a network
     * set up as the proxy module asks, the two are equal.
     *
     * @return the name a {@link #send(String, String, String)} reaches this server by
     */
    public @NotNull String serverId() {
        return CrossServer.serverId(plugin);
    }

    /**
     * Sends a payload to this plugin's handlers of {@code channel} on one server.
     *
     * <p>One Redis round trip on the calling thread, like
     * {@link Channel#publish}; send from {@code runAsync} on a hot path. A
     * message to this server is delivered here with or without Redis.
     *
     * @param server  the target server's name (its {@code server-id})
     * @param channel the channel name
     * @param payload anything, pipes included
     * @return {@link Delivery#SENT}, or {@link Delivery#NO_NETWORK} when the
     *         server is another one and no Redis carries the message
     */
    public @NotNull Delivery send(@NotNull String server, @NotNull String channel, @NotNull String payload) {
        if (server.isBlank() || server.indexOf(SEPARATOR) >= 0) {
            throw new IllegalArgumentException("Not a server name: \"" + server + "\".");
        }
        Channel wire = wire(channel);
        if (!isHere(server) && !wire.isNetworked()) {
            return Delivery.NO_NETWORK;
        }
        // ponytail: an unreachable Redis is only logged by the channel, so a
        // message that falls back to local delivery still reports SENT.
        wire.publish(frame(server, payload));
        return Delivery.SENT;
    }

    /**
     * Sends a payload to this plugin's handlers of {@code channel} on the
     * server the player is on.
     *
     * <p>A player on this server is reached at once; anyone else is looked up
     * through the proxy bridge ({@link Proxy#find}) first. Completes on the
     * calling thread for a player here, on the Redis subscriber thread
     * otherwise; never exceptionally.
     *
     * @param player  who the message is for
     * @param channel the channel name
     * @param payload anything, pipes included
     * @return {@link Delivery#SENT}; {@link Delivery#NO_PLAYER} for a player on
     *         no server; {@link Delivery#NO_NETWORK} when the player is not
     *         here and the bridge did not answer
     */
    public @NotNull CompletableFuture<Delivery> send(@NotNull UUID player, @NotNull String channel,
                                                     @NotNull String payload) {
        if (Bukkit.getPlayer(player) != null) {
            return CompletableFuture.completedFuture(send(serverId(), channel, payload));
        }
        return Proxy.find(player.toString()).handle((found, failure) -> {
            Optional<String> server = found == null ? Optional.empty()
                    : found.filter(ProxyPlayer::isOnAServer).map(ProxyPlayer::server);
            if (server.isPresent()) {
                return send(server.get(), channel, payload);
            }
            return Proxy.isAvailable() ? Delivery.NO_PLAYER : Delivery.NO_NETWORK;
        });
    }

    /**
     * Listens for messages addressed to this server on {@code channel}.
     *
     * <p>Runs on the Redis subscriber thread (on the sender's thread without
     * Redis), never on the main thread: hop through {@code Tasks} before
     * touching Bukkit. {@link Message#sender()} is the sending server's
     * {@code server-id}, the address to answer to.
     *
     * @param channel the channel name
     * @param handler what to do with each message
     * @return a handle that stops this handler; the plugin's disable stops all
     */
    public @NotNull Channel.Subscription on(@NotNull String channel, @NotNull Consumer<Message> handler) {
        return wire(channel).subscribe(message -> {
            String payload = payloadFor(message.payload());
            if (payload != null) {
                handler.accept(new Message(message.sender(), payload, message.local()));
            }
        });
    }

    /**
     * Waits until every one of these players is online on this server, or the
     * timeout passes, whichever is first.
     *
     * <p>What a server receiving a match calls once it has been told who is
     * coming: players already here count at once. Checked every five ticks off
     * the main thread; completes on that thread. A player who arrived and left
     * again before the end is missing. The wait is the plugin's task, so a
     * plugin that disables leaves it uncompleted.
     *
     * @param players who is coming
     * @param timeout how long to wait at most
     * @return who arrived and who did not; {@link Arrival#complete()} when all did
     */
    public @NotNull CompletableFuture<Arrival> awaitArrival(@NotNull Collection<UUID> players,
                                                            @NotNull Duration timeout) {
        Collection<UUID> expected = java.util.List.copyOf(players);
        long deadline = System.nanoTime() + timeout.toNanos();
        CompletableFuture<Arrival> result = new CompletableFuture<>();
        // ponytail: polls every five ticks instead of hooking the join event;
        // a quarter second at most before a match starts.
        Tasks.of(plugin).runAsyncTimer(0L, 5L, handle -> {
            Arrival now = Arrival.of(expected, id -> Bukkit.getPlayer(id) != null);
            if (now.complete() || System.nanoTime() - deadline >= 0) {
                handle.cancel();
                result.complete(now);
            }
        });
        return result;
    }

    private Channel wire(String channel) {
        return Channels.of(plugin).channel("net:" + channel);
    }

    private boolean isHere(String server) {
        return server.equalsIgnoreCase(serverId()) || server.equalsIgnoreCase(Redis.serverId(plugin));
    }

    /** {@code <target>|<payload>}. */
    static @NotNull String frame(@NotNull String server, @NotNull String payload) {
        return server + SEPARATOR + payload;
    }

    /** The payload of a frame addressed to this server, or {@code null} for another or a broken one. */
    private @Nullable String payloadFor(String frame) {
        int split = frame.indexOf(SEPARATOR);
        return split > 0 && isHere(frame.substring(0, split)) ? frame.substring(split + 1) : null;
    }
}
