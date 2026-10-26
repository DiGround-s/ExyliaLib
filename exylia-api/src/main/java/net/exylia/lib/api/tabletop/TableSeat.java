package net.exylia.lib.api.tabletop;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * One player of a match. Seat 0 is the host.
 *
 * @param player who sits there
 * @param name   their name when they sat down
 * @since exylia-api 3.0.0
 */
public record TableSeat(@NotNull UUID player, @NotNull String name) {
}
