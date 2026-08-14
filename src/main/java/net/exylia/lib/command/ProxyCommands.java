package net.exylia.lib.command;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Carries a command to the proxy.
 *
 * <p>Paper cannot run a proxy command itself: {@code /server}, and anything
 * else Velocity owns, only exists on the proxy. Something has to carry the
 * request across, and that something is a bridge plugin on the proxy side.
 *
 * <p>Until such a bridge is installed, {@link #none()} is in place and every
 * proxy command reports {@link CommandResult.Status#NO_TRANSPORT}. This is the
 * point of the interface: the previous system wrote bytes into a plugin channel
 * nobody listened on and reported success, so a proxy command that never ran
 * looked exactly like one that did.
 *
 * <h2>Implementing one</h2>
 * A transport is responsible for reaching the proxy and for reporting what
 * happened, not for deciding what to send. It receives the rendered command and
 * the player it is for; the player also serves as the carrier, since plugin
 * messages travel down a player's connection.
 *
 * @since 1.22.0
 */
public interface ProxyCommands {

    /**
     * Sends a command to the proxy.
     *
     * @param actor   whether the proxy should run it as the player or as its console
     * @param command the command, already rendered, without a leading slash
     * @param carrier the player whose connection carries the request
     * @return what happened, completed when the proxy answers or the attempt fails
     */
    @NotNull CompletableFuture<CommandResult> send(@NotNull CommandActor actor,
                                                   @NotNull String command,
                                                   @NotNull Player carrier);

    /** Returns whether this transport can currently reach the proxy. */
    boolean isAvailable();

    /**
     * The transport used when no bridge is installed.
     *
     * <p>Reports {@link CommandResult.Status#NO_TRANSPORT} rather than
     * pretending, so a server without the bridge fails visibly and once.
     *
     * @return a transport that carries nothing
     */
    static @NotNull ProxyCommands none() {
        return new ProxyCommands() {
            @Override
            public @NotNull CompletableFuture<CommandResult> send(@NotNull CommandActor actor,
                                                                  @NotNull String command,
                                                                  @NotNull Player carrier) {
                return CompletableFuture.completedFuture(CommandResult.noTransport(command,
                        "no proxy bridge is installed, so \"" + actor.prefix()
                                + ":\" commands cannot run"));
            }

            @Override
            public boolean isAvailable() {
                return false;
            }
        };
    }
}
