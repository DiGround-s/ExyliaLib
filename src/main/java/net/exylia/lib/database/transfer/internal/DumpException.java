package net.exylia.lib.database.transfer.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A dump that could not be read, and the line it gave up on.
 *
 * <p>Internal: it never reaches a caller as a throwable. The transfer module
 * catches it and puts its message — line number included — into the report,
 * because a failed import is a result somebody has to read, not an exception
 * somebody has to catch.
 *
 * <p>The line number is the whole reason this type exists rather than a plain
 * {@link java.io.IOException}. "Line 41 812" is something an operator can open
 * the file at; "malformed JSON" is not.
 *
 * @since 1.36.0
 */
final class DumpException extends RuntimeException {

    private final long line;

    DumpException(@NotNull String message, long line) {
        this(message, line, null);
    }

    DumpException(@NotNull String message, long line, @Nullable Throwable cause) {
        super(message, cause);
        this.line = line;
    }

    /** The line the reader was on, counting the header as line one. */
    long line() {
        return line;
    }

    /** The message with the line in it, which is what the report shows. */
    @NotNull String describe() {
        return "line " + line + ": " + getMessage();
    }
}
