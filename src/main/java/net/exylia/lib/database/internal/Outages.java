package net.exylia.lib.database.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Tells a database that is not answering apart from code that is wrong.
 *
 * <p>A pool that timed out waiting for a connection, a link that dropped, a
 * host that refused the socket: none of those is a bug in the plugin that
 * asked, and none of them is fixed by reading its stack trace. They are said
 * as one line to the operator, who can bring the database back, and kept out
 * of the error telemetry, where a single outage otherwise arrives as one
 * "bug" per table the plugins happened to be writing at the time.
 */
public final class Outages {

    /** SQLSTATE class 08: connection exception, in every driver that sets one. */
    private static final String CONNECTION_STATE = "08";

    /** Mongo's own, named rather than imported: the driver is optional. */
    private static final Set<String> MONGO = Set.of(
            "com.mongodb.MongoTimeoutException",
            "com.mongodb.MongoSocketException");

    private Outages() {
    }

    /**
     * Whether a failure, or anything it wraps, is the database being unreachable.
     *
     * @param failure what was thrown or completed a future
     * @return {@code true} for a connection problem rather than a code problem
     */
    public static boolean is(@Nullable Throwable failure) {
        return cause(failure) != null;
    }

    /**
     * The connection failure itself, for its message.
     *
     * @param failure what was thrown or completed a future
     * @return the innermost wrapper that names the outage, or {@code null} for anything else
     */
    public static @Nullable Throwable cause(@Nullable Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (connection(current)) {
                return current;
            }
        }
        return null;
    }

    private static boolean connection(@NotNull Throwable failure) {
        if (failure instanceof SQLTransientConnectionException
                || failure instanceof SQLNonTransientConnectionException) {
            return true;
        }
        if (failure instanceof SQLException sql && sql.getSQLState() != null
                && sql.getSQLState().startsWith(CONNECTION_STATE)) {
            return true;
        }
        for (Class<?> type = failure.getClass(); type != null; type = type.getSuperclass()) {
            if (MONGO.contains(type.getName())) {
                return true;
            }
        }
        return false;
    }
}
