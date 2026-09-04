package net.exylia.lib.proxy;

import net.exylia.lib.proxy.internal.ProxyRuntime;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
 * Redis pub/sub, over the Redis that {@code plugins/ExyliaLib/database.yml}
 * names — the same one every plugin's cache uses. Not plugin messages: those
 * travel down a player's connection, a modified client can write one, and a
 * bridge built on them has to trust the proxy to filter the client's bytes
 * out. Redis is on the network's own side of the wall, and it works with
 * nobody online. Every request carries an id the proxy echoes, so any number
 * can be in flight at once, and a request may be <em>about</em> a player
 * without needing one.
 *
 * <h2>Nothing is assumed</h2>
 * A request the proxy never answers ends as {@link ProxyReply.Status#TIMEOUT}
 * after five seconds; a server whose proxy has no bridge finds out that way
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

    /** The module that finds a connected player by name or id. */
    public static final String PLAYER = "player";

    private Proxy() {
        throw new AssertionError("No instances.");
    }

    /**
     * Sends a request about a player to a module on the proxy.
     *
     * <p><b>Threading:</b> safe from anywhere; the future completes on the
     * Redis subscriber thread, so touching the Bukkit API from it means
     * hopping through {@code Tasks} first.
     *
     * @param about   the player the request concerns: who a {@code player-proxy:}
     *                command runs as, who a {@code connect} moves
     * @param module  the module on the proxy, such as {@link #COMMANDS}
     * @param payload what the module reads; its format is the module's own
     * @return the answer, never failing exceptionally
     */
    public static @NotNull CompletableFuture<ProxyReply> request(@NotNull Player about,
                                                                 @NotNull String module,
                                                                 @NotNull String payload) {
        return ProxyRuntime.request(about, module, payload);
    }

    /**
     * Sends a request about nobody in particular: a console command, a lookup.
     *
     * @param module  the module on the proxy
     * @param payload what the module reads
     * @return the answer, never failing exceptionally
     * @since 1.106.0
     */
    public static @NotNull CompletableFuture<ProxyReply> request(@NotNull String module,
                                                                 @NotNull String payload) {
        return ProxyRuntime.request((UUID) null, module, payload);
    }

    /**
     * Finds a player anywhere on the network, by name or by id.
     *
     * <p>A backend only knows the players it has seen; the proxy knows every
     * one that is connected. This is what resolves {@code /tp <name>} for
     * somebody who has never set foot on this server. Empty for a player who
     * is not on the network, and empty — never failing — when the bridge is
     * not there.
     *
     * @param nameOrId a name, or a uuid as text
     * @return the player and their server, or empty
     * @since 1.103.0
     */
    public static @NotNull CompletableFuture<Optional<ProxyPlayer>> find(@NotNull String nameOrId) {
        return request(PLAYER, nameOrId).thenApply(reply ->
                reply.isOk() ? ProxyPlayer.fromWire(reply.detail()) : Optional.empty());
    }

    /** {@link #find(String)}; the player asking no longer matters and is ignored. */
    public static @NotNull CompletableFuture<Optional<ProxyPlayer>> find(@NotNull Player asking,
                                                                         @NotNull String nameOrId) {
        return find(nameOrId);
    }

    /**
     * Every player on the network, by name, as of the last refresh.
     *
     * <p>Kept in memory and refreshed every ten seconds, so it can be read
     * where nothing can wait: a tab completion, a placeholder. Empty until
     * the bridge has answered. Names may be a few seconds stale; a name that
     * just left costs a "not online" line, nothing worse.
     *
     * @return the names, never {@code null}, not necessarily including this
     *         server's own players before the first refresh
     * @since 1.104.0
     */
    public static @NotNull Set<String> players() {
        return ProxyRuntime.players();
    }

    /**
     * Whether the bridge has answered this server.
     *
     * <p>False until the proxy answers the ping sent a second after startup,
     * and false again after a request timed out. A diagnostic:
     * {@link #request} always tries regardless.
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
