package net.exylia.lib.internal;

import net.exylia.lib.ExyliaLib;
import net.exylia.lib.database.transfer.TransferReport;
import net.exylia.lib.database.transfer.Transfers;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The first-run setup of a server: offered to admins after a fresh install, opened any time with
 * {@code /exylialib setup}.
 *
 * <p>State lives in {@code plugins/ExyliaLib/setup.yml}: whether the wizard is still waiting, which
 * plugins it pointed at a shared database, and the rows it set aside to copy there on the next start.
 * A server that ran the library before this existed has no such file and is never asked.
 */
public final class SetupWizard {

    static final String PERMISSION = "exylialib.admin";

    private static final String FILE = "setup.yml";
    private static final String PENDING = "pending";
    private static final String DONE = "done";

    /** How long after joining an admin is invited, after the join messages. */
    private static final long INVITE_TICKS = 80L;

    private static volatile @Nullable ExyliaLib library;
    private static volatile boolean waiting;

    /** Admins invited since the server started: once each, LATER means "not this time". */
    private static final Set<UUID> INVITED = ConcurrentHashMap.newKeySet();

    private SetupWizard() {
    }

    /**
     * Reads the state, marking a fresh install as waiting for its setup.
     *
     * @param plugin the library
     */
    public static void init(@NotNull ExyliaLib plugin) {
        library = plugin;
        YamlConfiguration state = state();
        if (LibrarySettings.freshInstall() && !state.contains("status")) {
            state.set("status", PENDING);
            save(state);
        }
        if (state.getBoolean("restart", false)) {
            // This start is the restart the setup asked for.
            state.set("restart", null);
            save(state);
        }
        waiting = PENDING.equals(state.getString("status"));
    }

    /** Whether the wizard still waits for an admin. */
    static boolean waiting() {
        return waiting;
    }

    /** The library, once enabled. */
    static @Nullable ExyliaLib library() {
        return library;
    }

    // ------------------------------------------------------------------ invitation

    /**
     * Invites an admin who joins while the setup waits, once per admin and start.
     *
     * @param player who joined, on their thread
     */
    public static void joined(@NotNull Player player) {
        ExyliaLib plugin = library;
        if (plugin == null || !waiting || !player.hasPermission(PERMISSION) || !INVITED.add(player.getUniqueId())) {
            return;
        }
        Tasks.of(plugin).runAtEntityLater(player, INVITE_TICKS, () -> invite(player));
    }

    /** The centred invitation with its two buttons. */
    static void invite(@NotNull Player player) {
        String setUp = "<click:run_command:'/exylialib setup'><hover:show_text:\"{letters}"
                + Phrases.tr("Click to open the setup") + "\">{success}&l[✔ " + Phrases.tr("SET UP") + "]</hover></click>";
        String later = "<click:run_command:'/exylialib setup later'><hover:show_text:\"{letters}"
                + Phrases.tr("Click to be asked next time") + "\">{error}&l[✘ " + Phrases.tr("LATER") + "]</hover></click>";
        for (String line : List.of(
                "[center; sound:BLOCK_NOTE_BLOCK_PLING|0.7|1.6]&f",
                "[center]{primary}&l" + Phrases.tr("SERVER SETUP"),
                "[center]{letters}" + Phrases.tr("Choose the language, menus, colours"),
                "[center]{letters}" + Phrases.tr("and database of every Exylia plugin."),
                "[center]&f",
                "[center]" + setUp + " {letters_black}· " + later,
                "[center]{muted}" + Phrases.tr("Open it any time with /exylialib setup"),
                "[center]&f")) {
            Text.of(line).send(player);
        }
    }

    /** LATER: says where to find it, and leaves it waiting for the next start. */
    static void later(@NotNull Player player) {
        Text.of(prefix() + Phrases.tr("{letters}Setup left for later. Open it any time with {highlight}/exylialib setup{letters}."))
                .send(player);
    }

    /** Ends the wizard: no more invitations. */
    static void finish(@NotNull Player player) {
        YamlConfiguration state = state();
        state.set("status", DONE);
        save(state);
        waiting = false;
        boolean restart = !imports(state).isEmpty() || state.getBoolean("restart", false);
        Text.of("[sound:ENTITY_EXPERIENCE_ORB_PICKUP|0.6|1.2]" + prefix()
                + (restart
                ? Phrases.tr("{success}Setup finished. {warning}Restart the server so every change takes effect.")
                : Phrases.tr("{success}Setup finished. {letters}Every change is already in place.")))
                .send(player);
    }

    static String prefix() {
        return "{primary}&lEXYLIALIB {letters_black}•<reset> ";
    }

    // ------------------------------------------------------------------ state

    /** {@code setup.yml}, or an empty one when there is none. */
    static synchronized YamlConfiguration state() {
        ExyliaLib plugin = library;
        YamlConfiguration yaml = new YamlConfiguration();
        if (plugin == null) {
            return yaml;
        }
        File file = new File(plugin.getDataFolder(), FILE);
        if (file.isFile()) {
            yaml = YamlConfiguration.loadConfiguration(file);
        }
        return yaml;
    }

    static synchronized void save(YamlConfiguration state) {
        ExyliaLib plugin = library;
        if (plugin == null) {
            return;
        }
        state.options().setHeader(List.of(
                "Written by /exylialib setup. status: pending until an admin finishes it.",
                "moved: plugins the setup pointed at the shared database.",
                "imports: rows they had, copied into that database on the next start."));
        try {
            state.save(new File(plugin.getDataFolder(), FILE));
        } catch (IOException failed) {
            plugin.getLogger().warning("Could not save " + FILE + ": " + failed.getMessage());
        }
    }

    /** The dumps waiting to be imported, by plugin name. */
    static Map<String, String> imports(YamlConfiguration state) {
        Map<String, String> out = new LinkedHashMap<>();
        ConfigurationSection section = state.getConfigurationSection("imports");
        if (section != null) {
            for (String plugin : section.getKeys(false)) {
                out.put(plugin, section.getString(plugin, ""));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ after a restart

    /**
     * Copies the rows set aside before a move into the shared database, once every plugin has enabled.
     *
     * <p>An import refuses a table that already holds rows, so a server that was already on the shared
     * database, or a second server of the network starting on it, never doubles anything: the refusal is
     * logged and the dump stays on disk.
     */
    public static void afterStartup() {
        ExyliaLib plugin = library;
        if (plugin == null) {
            return;
        }
        YamlConfiguration state = state();
        Map<String, String> pending = imports(state);
        if (pending.isEmpty()) {
            return;
        }
        state.set("imports", null);
        state.set("restart", null);
        save(state);
        pending.forEach((name, dump) -> {
            Plugin owner = Bukkit.getPluginManager().getPlugin(name);
            if (owner == null || !owner.isEnabled() || dump.isBlank()) {
                plugin.getLogger().warning("Setup: " + name + " is not running, so its rows stay in " + dump
                        + ". Import them later with /exylialib import " + name + " <file>.");
                return;
            }
            Transfers.of(owner).importFrom(Path.of(dump)).thenAccept(report -> logImport(plugin, name, dump, report));
        });
    }

    private static void logImport(ExyliaLib plugin, String name, String dump, TransferReport report) {
        if (report.successful()) {
            plugin.getLogger().info("Setup: copied " + report.rows() + " rows of " + name
                    + " into the shared database.");
            return;
        }
        plugin.getLogger().warning("Setup: " + name + "'s rows were not all copied (" + report.outcome() + "). "
                + "They stay in " + dump + "; run /exylialib import " + name + " <file> to try again.");
        report.problems().forEach(problem -> plugin.getLogger().warning("  " + problem));
    }
}
