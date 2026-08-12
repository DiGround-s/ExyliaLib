package net.exylia.lib.internal;

import net.exylia.lib.ExyliaLib;
import net.exylia.lib.text.Text;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.CommandPlaceholder;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.bukkit.annotation.CommandPermission;

/**
 * The library's own command, {@code /exylialib}.
 *
 * <p>Registered through Lamp, the framework the whole ecosystem standardises
 * on, and confined to this package: nothing outside it names a Lamp type.
 *
 * <h2>Why a command at all</h2>
 * The shared palette is the only state of the library that a server owner
 * edits, so it is the only thing worth reloading from in-game. Everything
 * else a plugin owns reloads through that plugin's own command — a plugin
 * reload never touches the library, and this command never touches plugins.
 */
@Command("exylialib")
public final class ReloadCommand {

    private final Runnable paletteReload;
    private final java.util.function.Supplier<String> version;

    public ReloadCommand(@NotNull ExyliaLib plugin) {
        this(plugin::reloadPalette, plugin::version);
    }

    /** Test seam: the reload action and the version are injected. */
    ReloadCommand(Runnable paletteReload, java.util.function.Supplier<String> version) {
        this.paletteReload = paletteReload;
        this.version = version;
    }

    /**
     * Shows what the library is and what the command does.
     *
     * @param sender who asked
     */
    @CommandPlaceholder
    public void overview(@NotNull CommandSender sender) {
        Text.of("{primary}ExyliaLib {muted}v" + version.get()
                + " {letters}— {muted}/exylialib reload{letters} recolours "
                + "everything from colors.yml.").send(sender);
    }

    /**
     * Reloads the shared palette.
     *
     * <p>One command recolours the whole server: the palette applies, the
     * parse cache drops, and every scoreboard and hologram re-sends itself.
     * Plugins are not involved and do not need reloading.
     *
     * @param sender who asked
     */
    @Subcommand("reload")
    @CommandPermission("exylialib.admin")
    public void reload(@NotNull CommandSender sender) {
        long started = System.currentTimeMillis();
        paletteReload.run();
        long took = System.currentTimeMillis() - started;

        Text.of("{success}ExyliaLib reloaded {muted}(" + took + "ms)"
                + "{letters} — new colours are live everywhere.").send(sender);
    }
}
