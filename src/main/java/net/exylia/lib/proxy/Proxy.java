package net.exylia.lib.proxy;

import net.exylia.lib.proxy.internal.ProxyRuntime;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Talking to the proxy.
 *
 * <p>A proxy owns things no backend server can reach: {@code /server}, the
 * whole network's player list, anything a proxy plugin does. ExyliaProxyUtils
 * is the plugin on that side, and this is the one way to reach it from here.
 * Every request names a <em>module</em> the proxy plugin registers, and
 * carries a string that module reads; adding a capability to the network is
 * one module there and one {@link #request} here, with nothing in between
 * to update.
 *
 * <h2>How it travels</h2>
 * Plugin messages on the {@code exylia:bridge} channel, down the connection
 * of a player: that is the only road a backend has to its proxy without
 * either side opening a socket, and it is why every request needs a carrier.
 * The proxy answers on the same road, and the answer is matched back to the
 * request by an id, so several can be in flight at once.
 *
 * <h2>Nothing is assumed</h2>
 * A request the proxy never answers ends as {@link ProxyReply.Status#TIMEOUT}
 * after five seconds; a server without the bridge plugin finds out that way
 * once, says so in the console, and every {@code player-proxy:} command from
 * then on reports {@code NO_TRANSPORT} rather than pretending. The previous
 * system wrote bytes into a channel nobody listened on and reported success.
 *
 * <pre>{@code
 * Proxy.request(player, "commands", "player-proxy:server lobby")
 *      .thenAccept(reply -> {
 *          if (!reply.isOk()) player.sendMessage(reply.detail());
 *      });
 * }</pre>
 *
 * @since 1.101.0
 */
public final class Proxy {

    /** The module that runs a command; what {@code player-proxy:} lines use. */
    public static final String COMMANDS = "commands";

    private Proxy() {
        throw new AssertionError("No instances.");
    }

    /**
     * Sends a request to a module on the proxy.
     *
     * <p><b>Threading:</b> safe from anywhere; the message is sent on the
     * carrier's thread and the future completes on the server thread that
     * received the answer.
     *
     * @param carrier the player whose connection carries it; must be online
     * @param module  the module on the proxy, such as {@link #COMMANDS}
     * @param payload what the module reads; its format is the module's own
     * @return the answer, never failing exceptionally
     */
    public static @NotNull CompletableFuture<ProxyReply> request(@NotNull Player carrier,
                                                                 @NotNull String module,
                                                                 @NotNull String payload) {
        return ProxyRuntime.request(carrier, module, payload);
    }

    /**
     * Whether the bridge has answered this server.
     *
     * <p>False until the first player joins and the proxy answers the ping
     * sent through them, and false again after a request timed out. A
     * diagnostic: {@link #request} always tries regardless.
     *
     * @return whether the proxy is known to be listening
     */
    public static boolean isAvailable() {
        return ProxyRuntime.isAvailable();
    }

    /**
     * What is listening on the other side, as it introduced itself.
     *
     * @return something like {@code ExyliaProxyUtils 1.0.0 on Velocity}, or
     *         empty until it has answered
     */
    public static @NotNull Optional<String> bridge() {
        return ProxyRuntime.bridge();
    }
}
