package net.exylia.lib.internal;

import net.exylia.lib.database.Databases;
import net.exylia.lib.database.PluginDatabase;
import net.exylia.lib.database.transfer.TransferReport;
import net.exylia.lib.database.transfer.Transfers;
import net.exylia.lib.redis.Redis;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Everything {@code /exylialib export|import} needs from the rest of the
 * library, behind one seam.
 *
 * <p>The command has a plugin <em>name</em> typed into a chat box; the transfer
 * module wants a {@link org.bukkit.plugin.Plugin}. Resolving one to the other
 * is the whole of this interface, and having it as an interface is what lets
 * the command's tests assert what it prints without a database, a server or a
 * file — the same reason {@code ReloadCommand} takes its version and platform
 * as suppliers.
 *
 * @since 1.36.0
 */
interface TransferAccess {

    /**
     * The plugins that have a database view, sorted.
     *
     * <p>What the argument suggestions offer. It is deliberately not every
     * plugin on the server: a plugin that stores nothing has nothing to export,
     * and offering it would only produce a refusal.
     */
    @NotNull List<String> plugins();

    /**
     * The tables one plugin has registered, or {@code null} when it has none.
     *
     * <p>Shown before an export runs, because the count on its own hides the
     * one failure mode this has: a plugin that registers a repository lazily
     * exports fewer tables than it owns, and only the names make that visible.
     */
    @Nullable List<String> tablesOf(@NotNull String pluginName);

    /** Whether the shared cache is on, which changes what an import has to warn about. */
    boolean redisActive();

    /** Starts an export of one plugin into a folder. */
    @NotNull CompletableFuture<TransferReport> export(@NotNull String pluginName,
                                                      @NotNull Path folder);

    /** Starts an import of one dump into one plugin's tables. */
    @NotNull CompletableFuture<TransferReport> importFrom(@NotNull String pluginName,
                                                          @NotNull Path file,
                                                          boolean force);

    /** The real one, wired to the modules. */
    static @NotNull TransferAccess live() {
        return new TransferAccess() {

            @Override
            public @NotNull List<String> plugins() {
                return Databases.registeredPlugins();
            }

            @Override
            public @Nullable List<String> tablesOf(@NotNull String pluginName) {
                PluginDatabase database = Databases.find(pluginName);
                // find rather than of: of would create the view, and creating
                // one loads — and writes — that plugin's database.yml. A
                // mistyped name would leave a config file behind in the data
                // folder of a plugin that never asked for one.
                return database == null ? null : List.copyOf(database.tables().keySet());
            }

            @Override
            public boolean redisActive() {
                return Redis.isActive();
            }

            @Override
            public @NotNull CompletableFuture<TransferReport> export(@NotNull String pluginName,
                                                                     @NotNull Path folder) {
                PluginDatabase database = Databases.find(pluginName);
                if (database == null) {
                    return CompletableFuture.completedFuture(TransferReport.failed(
                            pluginName + " has no registered tables.", java.time.Duration.ZERO));
                }
                return Transfers.of(database.plugin()).export(folder);
            }

            @Override
            public @NotNull CompletableFuture<TransferReport> importFrom(@NotNull String pluginName,
                                                                         @NotNull Path file,
                                                                         boolean force) {
                PluginDatabase database = Databases.find(pluginName);
                if (database == null) {
                    return CompletableFuture.completedFuture(TransferReport.failed(
                            pluginName + " has no registered tables.", java.time.Duration.ZERO));
                }
                return Transfers.of(database.plugin()).importFrom(file, force);
            }
        };
    }
}
