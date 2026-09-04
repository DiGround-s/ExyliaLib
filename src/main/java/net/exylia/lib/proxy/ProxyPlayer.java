package net.exylia.lib.proxy;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;

/**
 * A player the proxy has, and where.
 *
 * @param id     their id
 * @param name   their name as the proxy has it
 * @param server the backend they are on, by the proxy's name for it; empty
 *               while they are between servers
 * @since 1.103.0
 */
public record ProxyPlayer(@NotNull UUID id, @NotNull String name, @NotNull String server) {

    /** Reads what the {@code player} module writes: {@code uuid|name|server}. */
    static @NotNull Optional<ProxyPlayer> fromWire(@NotNull String detail) {
        String[] parts = detail.split("\\|", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ProxyPlayer(UUID.fromString(parts[0]), parts[1], parts[2]));
        } catch (IllegalArgumentException unreadable) {
            return Optional.empty();
        }
    }

    /** Whether the proxy knows which server they are on right now. */
    public boolean isOnAServer() {
        return !server.isEmpty();
    }
}
