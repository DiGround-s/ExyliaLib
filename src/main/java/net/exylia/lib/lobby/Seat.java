package net.exylia.lib.lobby;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

/**
 * One player at a {@link Table}. Seat 0 is the host.
 *
 * @param player who sits there
 * @param name   their name when they sat down, for screens and messages shown after they left
 * @since 1.356.0
 */
public record Seat(@NotNull UUID player, @NotNull String name) {

    public Seat {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(name, "name");
    }
}
