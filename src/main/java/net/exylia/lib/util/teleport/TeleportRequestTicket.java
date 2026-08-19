package net.exylia.lib.util.teleport;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One outstanding request from a player to another.
 *
 * <pre>{@code
 * for (TeleportRequestTicket ticket : teleports.pendingFor(player)) {
 *     Text.of("{primary}%name% {letters}wants to teleport to you {letters_black}» {info}%time%s")
 *             .with("name", Bukkit.getOfflinePlayer(ticket.from()).getName())
 *             .with("time", ticket.remainingSeconds())
 *             .send(player);
 * }
 * }</pre>
 *
 * <h2>Why identities rather than players</h2>
 * A ticket outlives the tick it was made in, and a {@link org.bukkit.entity.Player}
 * held past that is a reference to an object the server may already have thrown
 * away. Identities are looked up when they are needed and answer honestly when
 * the person is gone.
 *
 * <h2>Expiry is read, never counted down</h2>
 * A ticket carries the moment it stops being answerable and nothing anywhere
 * watches the clock for it. The same rule as {@link net.exylia.lib.util.Cooldowns}:
 * a hundred requests nobody answered cost nothing at all until somebody looks
 * at them, whereas a hundred scheduled expiries are a hundred tasks.
 *
 * @param from      who asked
 * @param to        who was asked
 * @param direction which of them moves if it is accepted
 * @param expiresAt when it stops being answerable
 * @param requester the plugin that created it
 * @since 1.34.0
 */
public record TeleportRequestTicket(@NotNull UUID from, @NotNull UUID to,
                                    @NotNull TeleportDirection direction,
                                    @NotNull Instant expiresAt,
                                    @NotNull Plugin requester) {

    public TeleportRequestTicket {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(requester, "requester");
    }

    /** Whether it has run out and can no longer be answered. */
    public boolean isExpired() {
        return !Instant.now().isBefore(expiresAt);
    }

    /**
     * How long is left, in seconds.
     *
     * <p>Decimals included, and never negative: a request that ran out has zero
     * seconds left rather than a negative countdown, so a message showing it
     * cannot read {@code -4.2}.
     *
     * @return the seconds remaining
     */
    public double remainingSeconds() {
        long millis = java.time.Duration.between(Instant.now(), expiresAt).toMillis();
        return millis <= 0 ? 0.0 : millis / 1000.0;
    }

    /**
     * Who actually moves if this is accepted.
     *
     * <p>Worked out here rather than at each call site, because it is the one
     * thing about a request that is easy to get backwards and impossible to
     * notice until a player is somewhere they did not agree to be.
     *
     * @return the identity of whoever is teleported
     */
    public @NotNull UUID traveller() {
        return direction == TeleportDirection.TO_TARGET ? from : to;
    }

    /**
     * Whose location the traveller is going to.
     *
     * @return the identity of whoever stays put
     */
    public @NotNull UUID anchor() {
        return direction == TeleportDirection.TO_TARGET ? to : from;
    }
}
