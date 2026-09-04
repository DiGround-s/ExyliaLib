package net.exylia.lib.command;

import net.exylia.lib.proxy.internal.BridgeCommands;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Runs configured commands for a plugin.
 *
 * <pre>{@code
 * PluginCommands commands = Commands.of(this);
 * List<CommandLine> lines = commands.compileAll(config.getStringList("commands"));
 *
 * // when the button is pressed
 * commands.run(lines, player);
 * }</pre>
 *
 * <h2>Order</h2>
 * A list runs in the order it is written, one command at a time, each starting
 * only once the one before it has been dispatched. This matters more than it
 * sounds: a list that sets a region flag and then warps the player only works
 * in that order, and the previous system started every command at once and let
 * the scheduler decide, which usually looked fine and occasionally did not.
 *
 * @since 1.22.0
 */
public final class PluginCommands {

    private final Plugin plugin;
    private volatile ProxyCommands proxy = BridgeCommands.INSTANCE;

    PluginCommands(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Installs the bridge that carries proxy commands.
     *
     * <p>The default reaches ExyliaProxyUtils on the proxy through the
     * {@code exylia:bridge} channel; until that plugin answers, {@code player-proxy:}
     * and {@code console-proxy:} report {@link CommandResult.Status#NO_TRANSPORT}.
     *
     * @param transport the bridge, or {@link ProxyCommands#none()} to remove it
     */
    public void proxy(@NotNull ProxyCommands transport) {
        this.proxy = transport;
    }

    /** The bridge currently carrying proxy commands. */
    public @NotNull ProxyCommands proxy() {
        return proxy;
    }

    /**
     * Compiles one configured line.
     *
     * @param line the line as written
     * @return the compiled command
     * @throws IllegalArgumentException if there is no command in it
     */
    public @NotNull CommandLine compile(@NotNull String line) {
        return CommandLine.compile(line);
    }

    /**
     * Compiles a configured list, in order.
     *
     * @param lines the lines as written
     * @return the compiled commands
     * @throws IllegalArgumentException if any line has no command in it
     */
    public @NotNull List<CommandLine> compileAll(@NotNull List<String> lines) {
        List<CommandLine> compiled = new ArrayList<>(lines.size());
        for (String line : lines) {
            compiled.add(CommandLine.compile(line));
        }
        return List.copyOf(compiled);
    }

    /**
     * Runs one command.
     *
     * @param line   the compiled command
     * @param player who it runs as, and who its placeholders read
     * @return what happened
     */
    public @NotNull CompletableFuture<CommandResult> run(@NotNull CommandLine line,
                                                         @NotNull Player player) {
        return run(line, player, Map.of());
    }

    /**
     * Runs one command with extra values for its placeholders.
     *
     * @param line   the compiled command
     * @param player who it runs as, and who its placeholders read
     * @param data   values for the resolvers, such as the row that was clicked
     * @return what happened
     */
    public @NotNull CompletableFuture<CommandResult> run(@NotNull CommandLine line,
                                                         @NotNull Player player,
                                                         @NotNull Map<String, Object> data) {
        if (!player.isOnline()) {
            return CompletableFuture.completedFuture(CommandResult.noPlayer(line.raw()));
        }
        String command = line.render(player, data);
        if (command.isBlank()) {
            // A placeholder resolved to nothing and took the command with it.
            return CompletableFuture.completedFuture(
                    CommandResult.rejected(line.raw(), "resolved to an empty command"));
        }
        if (line.actor().isProxy()) {
            return proxy.send(line.actor(), command, player);
        }
        return dispatch(line.actor(), command, player);
    }

    /**
     * Runs a list in order, stopping at the first command that does not
     * dispatch.
     *
     * @param lines  the compiled commands
     * @param player who they run as
     * @return every result up to and including the one that stopped it
     */
    public @NotNull CompletableFuture<List<CommandResult>> run(@NotNull List<CommandLine> lines,
                                                               @NotNull Player player) {
        return run(lines, player, Map.of());
    }

    /**
     * Runs a list in order with extra values for its placeholders.
     *
     * @param lines  the compiled commands
     * @param player who they run as
     * @param data   values for the resolvers
     * @return every result up to and including the one that stopped it
     */
    public @NotNull CompletableFuture<List<CommandResult>> run(@NotNull List<CommandLine> lines,
                                                               @NotNull Player player,
                                                               @NotNull Map<String, Object> data) {
        List<CommandResult> results = new ArrayList<>(lines.size());
        CompletableFuture<List<CommandResult>> done = new CompletableFuture<>();
        advance(lines, 0, player, data, results, done);
        return done;
    }

    private void advance(List<CommandLine> lines, int index, Player player,
                         Map<String, Object> data, List<CommandResult> results,
                         CompletableFuture<List<CommandResult>> done) {
        if (index >= lines.size()) {
            done.complete(List.copyOf(results));
            return;
        }
        run(lines.get(index), player, data).whenComplete((result, error) -> {
            CommandResult actual = error == null
                    ? result
                    : CommandResult.failed(lines.get(index).raw(), error);
            results.add(actual);
            if (actual.continues()) {
                advance(lines, index + 1, player, data, results, done);
            } else {
                done.complete(List.copyOf(results));
            }
        });
    }

    /**
     * Hands a command to this server, on the thread allowed to run it.
     *
     * <p>The two actors want different threads, which is the whole of this
     * method. A player command runs through the player, so it belongs on the
     * player's own thread; running it inline when already there keeps a button
     * press within the same tick, which is what makes a menu feel immediate. A
     * console command is dispatched by the server, and a regionised server
     * dispatches only on the global tick thread — from a player's region it
     * throws {@code IllegalStateException: Dispatching command async}.
     *
     * <p>Both used to take the player's thread, so on a regionised server every
     * console line a config file listed threw, the throw was caught into a
     * failed result, and the reward it was paying quietly paid nothing.
     */
    private CompletableFuture<CommandResult> dispatch(CommandActor actor, String command,
                                                      Player player) {
        CompletableFuture<CommandResult> result = new CompletableFuture<>();
        TaskScheduler tasks = Tasks.of(plugin);
        Runnable body = () -> {
            try {
                if (!player.isOnline()) {
                    result.complete(CommandResult.noPlayer(command));
                    return;
                }
                boolean accepted = actor == CommandActor.PLAYER
                        ? player.performCommand(command)
                        : Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                result.complete(accepted
                        ? CommandResult.dispatched(command)
                        : CommandResult.rejected(command, "the server did not accept the command"));
            } catch (Exception failure) {
                result.complete(CommandResult.failed(command, failure));
            }
        };

        if (actor != CommandActor.PLAYER) {
            // Runs in place on an ordinary server, where the global thread and
            // the player's are the same one, so nothing waits a tick for a
            // reward it could have paid immediately.
            tasks.execute(body);
            return result;
        }
        if (tasks.isOwnedBy(player)) {
            body.run();
        } else {
            tasks.runAtEntity(player, body, () -> result.complete(CommandResult.noPlayer(command)));
        }
        return result;
    }
}
