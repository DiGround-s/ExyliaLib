package net.exylia.lib.redis;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.redis.internal.RedisClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A named pub/sub channel every server of the network shares.
 *
 * <p>A message published here reaches every subscriber on every server —
 * this one included — exactly once. Without Redis the channel still works,
 * confined to this process: single-server code and network code are the same
 * code, and {@link #isNetworked()} only exists for diagnostics.
 *
 * <h2>Threads</h2>
 * {@link #publish} is one Redis round trip on the calling thread, so a hot
 * path publishes from {@code runAsync}. Handlers run on the subscriber thread
 * (or on the publisher's, without Redis), never on the main thread: touching
 * the Bukkit API from one means hopping through {@code Tasks} first.
 *
 * <pre>{@code
 * Channel alerts = Channels.of(plugin).channel("alerts");
 * alerts.subscribe(message -> Tasks.of(plugin).run(() ->
 *         Bukkit.broadcast(Text.parse(message.payload()))));
 * alerts.publish("<red>Maintenance in five minutes.");
 * }</pre>
 *
 * @since 1.75.0
 */
public final class Channel {

    /** The wire separator, sender first so it is found with one {@code indexOf}. */
    private static final char SEPARATOR = '|';

    private final String name;
    private final String key;
    private final String serverId;
    private final @Nullable RedisClient client;
    private final Debug debug;
    private final List<Consumer<Message>> handlers = new CopyOnWriteArrayList<>();
    private volatile @Nullable RedisClient.Subscription wire;
    private volatile boolean closed;

    Channel(@NotNull String name, @NotNull String key, @NotNull String serverId,
            @Nullable RedisClient client, @NotNull Debug debug) {
        this.name = name;
        this.key = key;
        this.serverId = serverId;
        this.client = client;
        this.debug = debug;
    }

    /** The name this channel was asked for by. */
    public @NotNull String name() {
        return name;
    }

    /**
     * Whether a Redis carries this channel to other servers.
     *
     * <p>False on a server without Redis, where it still delivers locally.
     * A diagnostic, not a condition to branch on.
     *
     * @return whether other servers receive what is published here
     */
    public boolean isNetworked() {
        return client != null;
    }

    /**
     * Sends a payload to every subscriber on every server, this one included.
     *
     * <p>Never throws for an unreachable Redis: the message is then delivered
     * to this server's subscribers only, and the console says so.
     *
     * @param payload what to send; may contain anything, pipes included
     */
    public void publish(@NotNull String payload) {
        if (closed) {
            return;
        }
        if (client != null) {
            try {
                client.publish(key, serverId + SEPARATOR + payload);
                return;
            } catch (RuntimeException unreachable) {
                debug.warn("Redis could not carry a message on channel \"" + name + "\" ("
                        + unreachable.getMessage() + "); delivered to this server only.");
            }
        }
        deliver(new Message(serverId, payload, true));
    }

    /**
     * Listens until the returned subscription, or the owning plugin, closes.
     *
     * <p>The handler runs off the main thread; see the class note.
     *
     * @param handler what to do with each message
     * @return a handle that stops this handler
     */
    public @NotNull Subscription subscribe(@NotNull Consumer<Message> handler) {
        handlers.add(handler);
        openWire();
        return () -> handlers.remove(handler);
    }

    /** A running subscription; closing it is idempotent. */
    @FunctionalInterface
    public interface Subscription extends AutoCloseable {

        /** Stops the handler. */
        @Override
        void close();
    }

    /** One Redis subscription per channel, opened by the first subscriber. */
    private synchronized void openWire() {
        if (client == null || wire != null || closed) {
            return;
        }
        try {
            wire = client.subscribe(key, this::receive);
        } catch (RuntimeException unreachable) {
            debug.warn("Redis could not subscribe channel \"" + name + "\" ("
                    + unreachable.getMessage() + "); only this server's messages arrive.");
        }
    }

    private void receive(String raw) {
        int split = raw.indexOf(SEPARATOR);
        if (split <= 0) {
            return;
        }
        String sender = raw.substring(0, split);
        deliver(new Message(sender, raw.substring(split + 1), sender.equals(serverId)));
    }

    private void deliver(Message message) {
        for (Consumer<Message> handler : handlers) {
            try {
                handler.accept(message);
            } catch (RuntimeException failure) {
                // One handler must not take the subscriber thread down with it.
                debug.warn("A handler on channel \"" + name + "\" failed: " + failure);
            }
        }
    }

    synchronized void close() {
        closed = true;
        handlers.clear();
        RedisClient.Subscription open = wire;
        wire = null;
        if (open != null) {
            try {
                open.close();
            } catch (RuntimeException ignored) {
                // Closing: a connection that will not close is not worth a stack trace.
            }
        }
    }
}
