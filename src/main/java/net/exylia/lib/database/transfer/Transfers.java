package net.exylia.lib.database.transfer;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * A plugin's whole database, written to one file and read back.
 *
 * <p>What a server owner needs when moving from H2 to MySQL, when copying a
 * network's data onto a test server, or when taking something off a box before
 * it is rebuilt. It is not a backup tool and does not try to be: it moves rows
 * between two databases this library can already talk to.
 *
 * <pre>{@code
 * PluginTransfers transfers = Transfers.of(this);
 *
 * transfers.export(getDataFolder().toPath()).thenAccept(report -> {
 *     if (!report.successful()) {
 *         report.problems().forEach(getLogger()::warning);
 *     }
 * });
 * }</pre>
 *
 * <p>There is also {@code /exylialib export <plugin>} and
 * {@code /exylialib import <plugin> [force]}, which is how an owner uses this
 * without any plugin writing a line of code.
 *
 * <h2>The format</h2>
 * One gzip-compressed file of NDJSON: a header line carrying every table's
 * column layout, then a marker line per table, then one line per row. Values
 * are written as the type their column stores, never inferred on the way back
 * — see {@code docs/transfer.md} for the whole of it, and for why
 * ExyliaCommons' single nested JSON object could not survive a truncated file
 * or a {@code BigDecimal}.
 *
 * <h2>Memory</h2>
 * Constant on both sides. A thousand rows are alive at a time whatever the
 * table holds, and no row is ever decoded into a game object: a serialised
 * inventory is Base64 text at both ends.
 *
 * <h2>Threads</h2>
 * Every method is safe from any thread and none of them blocks. The work runs
 * on the library's own scheduler and the futures complete there.
 *
 * <h2>Reload</h2>
 * Nothing here is derived from the palette, so this module has no
 * {@code invalidateAll()} and is deliberately absent from
 * {@code ExyliaLib.loadPalette}.
 *
 * @see PluginTransfers
 * @see TransferReport
 * @since 1.36.0
 */
public final class Transfers {

    /**
     * The library itself, so a transfer's work outlives the plugin that asked.
     *
     * <p>Set once when ExyliaLib enables. A transfer scheduled on a consumer's
     * own scheduler would be cancelled the moment that consumer is disabled —
     * halfway through writing a file, or halfway through an import.
     */
    private static volatile Plugin library;

    private Transfers() {
        throw new AssertionError("No instances.");
    }

    /**
     * The transfer view of a plugin.
     *
     * <p>Cheap: it holds two references and no state. Storing it is fine but
     * unnecessary.
     *
     * @param plugin the plugin whose tables these are
     * @return its view
     * @throws IllegalStateException if ExyliaLib has not enabled yet
     */
    public static @NotNull PluginTransfers of(@NotNull Plugin plugin) {
        Plugin owner = library;
        if (owner == null) {
            throw new IllegalStateException("The transfer module was used before ExyliaLib"
                    + " enabled. Ask for it from onEnable, not from a static initialiser.");
        }
        return new PluginTransfers(owner, plugin);
    }

    /**
     * Starts the module.
     *
     * <p>Called once by ExyliaLib on enable; a consumer does not call this.
     * Nothing is opened and nothing is read here — the module holds only the
     * library reference it schedules its work on.
     *
     * @param plugin the library plugin
     */
    public static void init(@NotNull Plugin plugin) {
        library = plugin;
    }

    /**
     * Forgets the library reference.
     *
     * <p>Called by ExyliaLib on shutdown. There is nothing else to release:
     * this module owns no cache, no listener, no task and no open file between
     * transfers. A transfer in flight owns its own streams and closes them in
     * a {@code finally} whatever happens to it.
     */
    public static void releaseAll() {
        library = null;
    }
}
