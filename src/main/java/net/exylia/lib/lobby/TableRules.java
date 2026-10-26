package net.exylia.lib.lobby;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Objects;

/**
 * How one table fills: its seats, how long it waits, and the command its chat buttons run.
 *
 * <pre>{@code
 * TableRules rules = new TableRules("tabletop:checkers", "/checkers", 2, 2,
 *         Duration.ofMinutes(10), Duration.ofSeconds(60));
 * }</pre>
 *
 * @param kind        the activity the seated players are claimed for ({@link net.exylia.lib.session.Claim#kind}),
 *                    which other plugins read when they are told the player is busy
 * @param command     the command the buttons run, with its slash: {@code /checkers} makes the invitation's
 *                    buttons run {@code /checkers accept <host>} and {@code /checkers deny <host>}, and the
 *                    host's start button {@code /checkers start}
 * @param minSeats    the fewest seated players the host may start with, at least 2
 * @param maxSeats    the seats; the table starts by itself once they are all taken
 * @param openExpiry  how long an open table waits for its seats to fill
 * @param inviteExpiry how long an invitation waits for its answer
 * @since 1.356.0
 */
public record TableRules(@NotNull String kind, @NotNull String command, int minSeats, int maxSeats,
                         @NotNull Duration openExpiry, @NotNull Duration inviteExpiry) {

    public TableRules {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(openExpiry, "openExpiry");
        Objects.requireNonNull(inviteExpiry, "inviteExpiry");
        if (minSeats < 2 || maxSeats < minSeats) {
            throw new IllegalArgumentException("A table needs 2 <= minSeats <= maxSeats, got " + minSeats + " and " + maxSeats + ".");
        }
        if (openExpiry.isNegative() || openExpiry.isZero() || inviteExpiry.isNegative() || inviteExpiry.isZero()) {
            throw new IllegalArgumentException("A table's expiries must be positive.");
        }
        if (!command.startsWith("/")) command = "/" + command;
    }

    /** Whether the host may start before every seat is taken. */
    public boolean startsEarly() {
        return minSeats < maxSeats;
    }
}
