package net.exylia.lib.internal;

import net.exylia.lib.database.Databases;
import net.exylia.lib.database.PluginDatabase;
import net.exylia.lib.database.transfer.TransferOutcome;
import net.exylia.lib.database.transfer.TransferReport;
import net.exylia.lib.database.transfer.Transfers;
import net.exylia.lib.redis.Redis;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Everything {@code /exylialib export|import|wipe} needs from the rest of the
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

    /**
     * Empties one plugin's tables, or the one table named.
     *
     * @param pluginName whose tables to empty
     * @param table      the table, or {@code null} for every registered one
     * @since 1.76.0
     */
    @NotNull CompletableFuture<TransferReport> wipe(@NotNull String pluginName,
                                                    @Nullable String table);

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

            @Override
            public @NotNull CompletableFuture<TransferReport> wipe(@NotNull String pluginName,
                                                                    @Nullable String table) {
                PluginDatabase database = Databases.find(pluginName);
                if (database == null) {
                    return CompletableFuture.completedFuture(TransferReport.failed(
                            pluginName + " has no registered tables.", java.time.Duration.ZERO));
                }
                Plugin plugin = database.plugin();
                CompletableFuture<TransferReport> wiped = table == null
                        ? Transfers.of(plugin).wipeAll()
                        : Transfers.of(plugin).wipe(table);
                return wiped.thenCompose(report -> restarted(plugin, report));
            }
        };
    }

    /**
     * Disables and re-enables the plugin whose tables were just emptied.
     *
     * <p>Without this a wipe only looked like one. The plugin still held every
     * row in memory — a clan manager indexes its tables on enable and never
     * reads them again — and wrote them back on the next save: a stats flush
     * on quit, a DTR tick, the server stopping. The admin restarted, the data
     * was there, and the wipe had done nothing visible.
     *
     * <p>Wipe first, then restart, deliberately: once the plugin is disabled
     * the library releases its repositories, so there is nothing left to wipe
     * through. The gap between the last delete and the disable is one tick.
     * The enable waits two ticks more, because the library's own cleanup of a
     * disabled plugin runs a tick after the event, and a load enabled before
     * that runs would have its fresh datasource closed underneath it.
     */
    // ponytail: a plugin that flushes memory from its own onDisable writes it
    // back during the restart; disable first through a fresh view if one ever does.
    private static CompletableFuture<TransferReport> restarted(Plugin plugin, TransferReport report) {
        if (report.outcome() == TransferOutcome.FAILED || !plugin.isEnabled()) {
            return CompletableFuture.completedFuture(report);
        }
        Plugin library = JavaPlugin.getProvidingPlugin(TransferAccess.class);
        PluginManager plugins = Bukkit.getPluginManager();
        CompletableFuture<TransferReport> done = new CompletableFuture<>();
        Tasks.of(library).run(() -> {
            plugins.disablePlugin(plugin);
            Tasks.of(library).runLater(2L, () -> {
                try {
                    plugins.enablePlugin(plugin);
                    done.complete(report);
                } catch (Throwable failure) {
                    List<String> problems = new java.util.ArrayList<>(report.problems());
                    problems.add(plugin.getName() + " did not come back up after the wipe: "
                            + failure.getMessage() + ". Restart the server.");
                    done.complete(new TransferReport(TransferOutcome.PARTIAL, report.file(),
                            report.tables(), report.rows(), report.took(), problems));
                }
            });
        });
        return done;
    }
}
