package net.exylia.lib.proxy;

import org.jetbrains.annotations.NotNull;

/**
 * One backend the proxy has registered, as the proxy last saw it.
 *
 * @param name    the proxy's name for it, which is also its {@code server-id}
 * @param players how many players the proxy has connected to it
 * @param online  whether it answered the proxy's ping in time
 * @since 1.557.0
 */
public record NetworkServer(@NotNull String name, int players, boolean online) {
}
