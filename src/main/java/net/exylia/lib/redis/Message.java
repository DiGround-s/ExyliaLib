package net.exylia.lib.redis;

import org.jetbrains.annotations.NotNull;

/**
 * One message received on a {@link Channel}.
 *
 * <p>{@code local} is true when this server published it: every message is
 * delivered to the sender's own subscribers too, so a handler that must not
 * react to its own announcement checks this rather than comparing names.
 *
 * @param sender  the {@code server-id} of the server that published it
 * @param payload what was published, exactly as it was given
 * @param local   whether this server is the sender
 * @since 1.75.0
 */
public record Message(@NotNull String sender, @NotNull String payload, boolean local) {
}
