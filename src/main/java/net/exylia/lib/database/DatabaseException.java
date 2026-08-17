package net.exylia.lib.database;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a database operation failed with, said in words a server owner can act
 * on.
 *
 * <p>Arrives as the cause of a {@link java.util.concurrent.CompletionException}
 * on the future that failed, never thrown from the call itself — the call
 * returned long before the database answered.
 *
 * <pre>{@code
 * stats.save(record).exceptionally(failure -> {
 *     getLogger().warning(failure.getCause().getMessage());
 *     return null;
 * });
 * }</pre>
 *
 * <h2>Why a type of our own rather than the driver's</h2>
 * A {@code SQLException} names a vendor error code and a statement fragment,
 * and nothing about which plugin, which record or which operation produced it.
 * The message here always names the table and what was being done to it,
 * because that is the part a person reading a console at three in the morning
 * needs; the driver's exception is kept as the cause for the part a developer
 * needs.
 *
 * <p>Unchecked on purpose. It is delivered through a future, where a checked
 * exception cannot be declared anyway, and there is nothing a caller can do to
 * recover in a {@code catch} that it could not do in
 * {@link java.util.concurrent.CompletableFuture#exceptionally}.
 *
 * @since 1.24.0
 */
public class DatabaseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * A failure with a message and the driver's own exception underneath.
     *
     * @param message what was being done, and to what
     * @param cause   what the driver threw, or {@code null}
     */
    public DatabaseException(@NotNull String message, @Nullable Throwable cause) {
        super(message, cause);
    }

    /**
     * A failure with only a message.
     *
     * @param message what went wrong
     */
    public DatabaseException(@NotNull String message) {
        super(message);
    }
}
