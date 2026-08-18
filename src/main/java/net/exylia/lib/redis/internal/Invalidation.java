package net.exylia.lib.redis.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One message on the invalidation channel.
 *
 * <p>The wire format is {@code <sender>|<table>|<id>}, with an empty id meaning
 * the whole table. Pipe-delimited rather than JSON: this is the hottest thing
 * the module sends, every peer parses every message including the ones it will
 * discard, and the sender field is read first so a server's own message is
 * dropped after one {@code indexOf} rather than after parsing a document.
 *
 * <p>A table name cannot contain a pipe — SQL identifiers do not — and an id
 * can, so the id is whatever follows the second separator and is never split
 * again.
 *
 * @param sender the server that sent it, so a peer can ignore its own
 * @param table  the table whose rows are affected
 * @param id     the row's stored key, or {@code null} for the whole table
 */
record Invalidation(@NotNull String sender, @NotNull String table, @Nullable String id) {

    private static final char SEPARATOR = '|';

    /** This message as it travels. */
    @NotNull String encode() {
        return sender + SEPARATOR + table + SEPARATOR + (id == null ? "" : id);
    }

    /**
     * Parses a message, or {@code null} when it is not one.
     *
     * <p>Never throws. A malformed message is another version of the library, a
     * different tool writing to the same channel, or a key prefix an operator
     * shares with something else. None of those is worth an exception on a
     * subscriber thread.
     *
     * @param message the raw message
     * @return the parsed message, or {@code null} to ignore it
     */
    static @Nullable Invalidation decode(@NotNull String message) {
        int first = message.indexOf(SEPARATOR);
        if (first <= 0) {
            return null;
        }
        int second = message.indexOf(SEPARATOR, first + 1);
        if (second < 0) {
            return null;
        }
        String table = message.substring(first + 1, second);
        if (table.isEmpty()) {
            return null;
        }
        String id = message.substring(second + 1);
        return new Invalidation(message.substring(0, first), table, id.isEmpty() ? null : id);
    }

    /** Whether this message names the sender given. */
    boolean sentBy(@NotNull String server) {
        return sender.equals(server);
    }

    /** Whether this drops a whole table rather than one row. */
    boolean wholeTable() {
        return id == null;
    }
}
