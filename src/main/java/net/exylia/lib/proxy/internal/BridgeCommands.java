package net.exylia.lib.proxy.internal;

import net.exylia.lib.command.CommandActor;
import net.exylia.lib.command.CommandResult;
import net.exylia.lib.command.ProxyCommands;
import net.exylia.lib.proxy.Proxy;
import net.exylia.lib.proxy.ProxyReply;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * The transport every plugin's commands use unless it installs its own.
 *
 * <p>{@code player-proxy:} and {@code console-proxy:} lines become one
 * request to the proxy's {@code commands} module, {@code <actor>:<command>},
 * and the proxy's answer becomes the result the list continues or stops on.
 * A proxy that is not there is still {@link CommandResult.Status#NO_TRANSPORT},
 * exactly as before this transport existed — only now it is found out rather
 * than assumed.
 */
@ApiStatus.Internal
public final class BridgeCommands implements ProxyCommands {

    public static final BridgeCommands INSTANCE = new BridgeCommands();

    private BridgeCommands() {
    }

    @Override
    public @NotNull CompletableFuture<CommandResult> send(@NotNull CommandActor actor,
                                                          @NotNull String command,
                                                          @NotNull Player carrier) {
        return Proxy.request(carrier, Proxy.COMMANDS, actor.prefix() + ':' + command)
                .thenApply(reply -> toResult(reply, command));
    }

    @Override
    public boolean isAvailable() {
        return Proxy.isAvailable();
    }

    public static @NotNull CommandResult toResult(@NotNull ProxyReply reply, @NotNull String command) {
        return switch (reply.status()) {
            case OK -> CommandResult.dispatched(command);
            case REJECTED -> CommandResult.rejected(command, reply.detail());
            case FAILED -> CommandResult.failed(command, new IllegalStateException(reply.detail()));
            case NO_PLAYER -> CommandResult.noPlayer(command);
            case UNKNOWN_MODULE -> CommandResult.noTransport(command,
                    "the proxy bridge has no commands module: " + reply.detail());
            case NO_BRIDGE, TIMEOUT -> CommandResult.noTransport(command, reply.detail());
        };
    }
}
