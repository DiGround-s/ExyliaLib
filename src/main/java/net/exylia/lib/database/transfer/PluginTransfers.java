package net.exylia.lib.database.transfer;

import net.exylia.lib.database.transfer.internal.TransferRuntime;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * One plugin's tables, moved to a file and back.
 *
 * <p>Obtained from {@link Transfers#of(Plugin)}. Everything here returns
 * immediately and completes on a background thread; nothing blocks and nothing
 * touches the game.
 *
 * @since 1.36.0
 */
public final class PluginTransfers {

    private final Plugin library;
    private final Plugin plugin;

    PluginTransfers(@NotNull Plugin library, @NotNull Plugin plugin) {
        this.library = library;
        this.plugin = plugin;
    }

    /** The plugin whose tables these are. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /**
     * Writes every table this plugin has registered into one dump.
     *
     * <pre>{@code
     * Transfers.of(this).export(getDataFolder().toPath().resolve("dumps"))
     *         .thenAccept(report -> getLogger().info(report.toString()));
     * }</pre>
     *
     * <h2>What gets exported</h2>
     * The tables the plugin has <em>registered</em> — the ones it has asked
     * {@code Databases.of(this).repository(...)} for by the time this is
     * called. A plugin that registers a record type lazily has fewer tables
     * here than it eventually will, and nothing can tell from outside; that is
     * why {@link TransferReport#tableNames()} exists and why every caller
     * should show it rather than only a count.
     *
     * <h2>Where it lands</h2>
     * A destination that names a folder — or anything not ending in
     * {@code .exyliadump.gz} — gets a generated file inside it, named for the
     * plugin, the engine and the moment, so exporting twice does not overwrite
     * the first copy. A destination that names a dump file is written exactly.
     * Missing parent directories are created.
     *
     * <h2>Memory</h2>
     * Constant, whatever the table holds. Rows are streamed a thousand at a
     * time from the database straight into the compressed file; nothing keeps
     * a table, and nothing decodes a row into a game object on the way past.
     *
     * @param destination the folder to write into, or the exact file to write
     * @return the report — never fails; a failure is a
     *         {@link TransferOutcome#FAILED} report with the reason in it
     */
    public @NotNull CompletableFuture<TransferReport> export(@NotNull Path destination) {
        return TransferRuntime.export(library, plugin, destination);
    }

    /**
     * Reads a dump into this plugin's registered tables, refusing to write into
     * any that already hold rows.
     *
     * @param source the dump
     * @return the report; {@link TransferOutcome#FAILED} when a target table
     *         is not empty, naming which and how many rows
     */
    public @NotNull CompletableFuture<TransferReport> importFrom(@NotNull Path source) {
        return importFrom(source, false);
    }

    /**
     * Reads a dump into this plugin's registered tables.
     *
     * <h2>What force means, exactly</h2>
     * {@code force} is a <b>merge, not a replace</b>: a row whose primary key
     * is in the dump overwrites the one in the table, and a row in the table
     * whose key is <em>not</em> in the dump is left exactly where it is. It
     * does not empty anything. Somebody expecting "replace" and getting this
     * has quietly mixed two servers' data into one table, which is why the
     * refusal without it names the sentence rather than the flag.
     *
     * <h2>What the refusal checks</h2>
     * Only the tables the dump actually carries and this plugin actually
     * claims. A table full of rows that the dump has nothing for is not in the
     * way of anything and does not block the import.
     *
     * <h2>Layout drift</h2>
     * Rows are bound by column <em>name</em>, using the layout in the dump's
     * header. A record that gained a component since the dump was written
     * imports with that column null; one that lost a component imports without
     * it. Either way it is reported and the outcome is
     * {@link TransferOutcome#PARTIAL}.
     *
     * <h2>Generated keys</h2>
     * Ids in the dump are written as they are, and the table's identity counter
     * is moved past them afterwards. Without that the next insert would ask for
     * a key the imported rows already hold.
     *
     * <h2>Redis</h2>
     * A bulk write does not invalidate the shared cache — see
     * {@code docs/transfer.md}. Importing into a table other servers are
     * already serving leaves them on their cached rows until those expire;
     * importing into a fresh table, which is the migration case, is unaffected.
     *
     * @param source the dump
     * @param force  whether to write into tables that already hold rows
     * @return the report — never fails
     */
    public @NotNull CompletableFuture<TransferReport> importFrom(@NotNull Path source,
                                                                 boolean force) {
        return TransferRuntime.importFrom(library, plugin, source, force);
    }

    @Override
    public String toString() {
        return "PluginTransfers[" + plugin.getName() + ']';
    }
}
