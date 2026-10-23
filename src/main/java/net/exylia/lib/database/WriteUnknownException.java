package net.exylia.lib.database;

import org.jetbrains.annotations.NotNull;

/**
 * A save that failed in a way that may still have landed, and that reading the row
 * back could not settle.
 *
 * <p>Whoever paid for the change is neither refunded nor paid on: either would be
 * wrong for one of the two ways it went. See
 * {@link RowLocks#write(Repository, String, java.util.function.UnaryOperator, java.util.function.BiPredicate)}.
 *
 * @since 1.354.0
 */
public final class WriteUnknownException extends RuntimeException {

    /**
     * @param what  what was written, as the console names it
     * @param cause the failed save
     */
    public WriteUnknownException(@NotNull String what, @NotNull Throwable cause) {
        super("Whether " + what + " was written is unknown", cause, false, false);
    }
}
