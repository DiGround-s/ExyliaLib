package net.exylia.lib.redis.internal;

import net.exylia.lib.database.internal.SqlBackend;
import org.jetbrains.annotations.NotNull;
import org.postgresql.PGConnection;

import java.sql.Connection;
import java.sql.Statement;

/**
 * Wakes the Postgres bus as soon as any server stores a message.
 *
 * <p>Its own class so only a Postgres network loads the driver's types. One
 * connection, held out of the bus's pool for as long as it listens; when it
 * breaks the bus falls back to plain polling and the next wait opens another.
 */
final class PostgresWake {

    private final SqlBackend backend;
    private final String channel;
    private volatile Connection connection;
    private volatile boolean closed;

    PostgresWake(@NotNull SqlBackend backend, @NotNull String channel) {
        this.backend = backend;
        this.channel = channel;
    }

    /**
     * Waits until a notification arrives or the time passes.
     *
     * @return whether it waited; {@code false} when there is no listening
     *         connection and the caller should sleep instead
     */
    boolean await(long millis) {
        PGConnection listening = listening();
        if (listening == null) {
            return false;
        }
        try {
            listening.getNotifications((int) Math.max(1, millis));
            return true;
        } catch (Exception broken) {
            drop();
            return false;
        }
    }

    private PGConnection listening() {
        if (closed) {
            return null;
        }
        Connection current = connection;
        try {
            if (current == null) {
                current = backend.connection();
                try (Statement statement = current.createStatement()) {
                    statement.execute("LISTEN " + channel);
                }
                connection = current;
            }
            return current.unwrap(PGConnection.class);
        } catch (Exception unavailable) {
            drop();
            return null;
        }
    }

    private void drop() {
        Connection current = connection;
        connection = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {
                // Already broken; the pool replaces it.
            }
        }
    }

    void close() {
        closed = true;
        Connection current = connection;
        if (current != null) {
            try (Statement statement = current.createStatement()) {
                // Back to the pool clean, not still subscribed.
                statement.execute("UNLISTEN *");
            } catch (Exception ignored) {
                // Broken already; closing it is all that is left.
            }
        }
        drop();
    }
}
