package net.exylia.lib.internal;

import net.exylia.lib.ExyliaLib;
import net.exylia.lib.action.Actions;
import net.exylia.lib.config.Configs;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.transfer.TableTransfer;
import net.exylia.lib.database.transfer.TransferOutcome;
import net.exylia.lib.database.transfer.TransferReport;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.hologram.internal.HologramRuntime;
import net.exylia.lib.platform.Platform;
import net.exylia.lib.redis.Redis;
import net.exylia.lib.region.Regions;
import net.exylia.lib.scoreboard.internal.BoardManager;
import net.exylia.lib.text.Text;
import net.exylia.lib.ui.Menus;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.CommandPlaceholder;
import revxrsal.commands.annotation.Default;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.annotation.SuggestWith;
import revxrsal.commands.autocomplete.SuggestionProvider;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;
import revxrsal.commands.node.ExecutionContext;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * The library's own command, {@code /exylialib}.
 *
 * <p>Registered through Lamp, the framework the whole ecosystem standardises
 * on, and confined to this package: nothing outside it names a Lamp type.
 *
 * <h2>What it reloads</h2>
 * {@code reload} refreshes the library's own runtime settings — the shared
 * palette ({@code colors.yml}), number and date formats
 * ({@code formats.yml}), economy detection ({@code economy.yml}) and the
 * question runtime ({@code input.yml}) — plus {@code config.yml} itself
 * (debug, small text, auto-update). It never touches a consumer plugin's own
 * configuration: a plugin reloads itself through its own command, exactly as
 * {@code docs/reload.md} describes.
 *
 * <h2>What {@code info} and {@code stats} show</h2>
 * Both are read-only diagnostics built entirely from data the library
 * already exposes through public static entry points ({@link Effects},
 * {@link Menus}, {@link Actions}, {@link Regions}, {@link Databases},
 * {@link Redis}, {@link Configs}) or that Bukkit itself exposes
 * ({@link org.bukkit.plugin.PluginManager#getPlugins()}). Neither adds new
 * counters or tracking to the library; they only surface what is already
 * there.
 *
 * <h2>Permission</h2>
 * Every subcommand — including the read-only ones — sits behind
 * {@code exylialib.admin}. The values shown (plugin names, module counts) are
 * not secrets, but a server's admin commands are conventionally gated by one
 * node, and splitting the four here would only make that node harder to
 * reason about for no real gain in usability.
 */
@Command("exylialib")
public final class ReloadCommand {

    private final Runnable paletteReload;
    private final Supplier<String> version;
    private final Supplier<Platform> platform;
    private final Supplier<LibrarySettings> settings;
    private final Supplier<List<Dependent>> dependents;

    /**
     * Where a dump is written and looked for.
     *
     * <p>One folder for the whole server rather than one per plugin: a
     * migration moves several plugins at once, and an owner should be able to
     * copy one directory rather than hunt through {@code plugins/} for it.
     */
    private final Supplier<Path> dumpFolder;

    /** How a transfer is started, injected so a test does not need a database. */
    private final TransferAccess transfers;

    public ReloadCommand(@NotNull ExyliaLib plugin) {
        this(plugin::reloadPalette, plugin::version, Platform::current,
                LibrarySettings::get, () -> dependentsOf(plugin),
                () -> plugin.getDataFolder().toPath().resolve("dumps"),
                TransferAccess.live());
    }

    /** Test seam: every data source is injected. */
    ReloadCommand(Runnable paletteReload, Supplier<String> version, Supplier<Platform> platform,
                  Supplier<LibrarySettings> settings, Supplier<List<Dependent>> dependents) {
        this(paletteReload, version, platform, settings, dependents,
                () -> Path.of("dumps"), TransferAccess.live());
    }

    /** Test seam: the transfer side too, so no database has to exist. */
    ReloadCommand(Runnable paletteReload, Supplier<String> version, Supplier<Platform> platform,
                  Supplier<LibrarySettings> settings, Supplier<List<Dependent>> dependents,
                  Supplier<Path> dumpFolder, TransferAccess transfers) {
        this.paletteReload = paletteReload;
        this.version = version;
        this.platform = platform;
        this.settings = settings;
        this.dependents = dependents;
        this.dumpFolder = dumpFolder;
        this.transfers = transfers;
    }

    /**
     * Shows what the library is and what the command does.
     *
     * @param sender who asked
     */
    @CommandPlaceholder
    public void overview(@NotNull CommandSender sender) {
        Text.of(header()
                + "\n{letters_black}▎ {secondary}Reload {letters_black}» {letters}"
                + "{muted}/exylialib reload{letters} — refreshes colours, formats, "
                + "economy detection and input settings."
                + "\n{letters_black}▎ {secondary}Info {letters_black}» {letters}"
                + "{muted}/exylialib info{letters} — version, platform and who depends on this library."
                + "\n{letters_black}▎ {secondary}Stats {letters_black}» {letters}"
                + "{muted}/exylialib stats{letters} — live counters from every module."
                + "\n{letters_black}▎ {secondary}Export {letters_black}» {letters}"
                + "{muted}/exylialib export <plugin>{letters} — writes that plugin's tables to a dump."
                + "\n{letters_black}▎ {secondary}Import {letters_black}» {letters}"
                + "{muted}/exylialib import <plugin> <file> [force]{letters} — reads one back;"
                + " force MERGES rather than replacing."
        ).send(sender);
    }

    /**
     * Reloads the library's own runtime settings.
     *
     * <p>One command refreshes {@code config.yml}, {@code colors.yml},
     * {@code formats.yml}, {@code economy.yml} and {@code input.yml} — the
     * whole of {@link ExyliaLib#reloadPalette()}. The palette's own listener
     * re-sends every scoreboard, hologram, effect and item; plugins are not
     * involved and never need reloading from here.
     *
     * @param sender who asked
     */
    @Subcommand("reload")
    @CommandPermission("exylialib.admin")
    public void reload(@NotNull CommandSender sender) {
        long started = System.currentTimeMillis();
        paletteReload.run();
        long took = System.currentTimeMillis() - started;

        Text.of(header()
                + "\n{letters_black}▎ {success}Reloaded {letters_black}» {letters}"
                + "colours, formats, economy and input are live everywhere."
                + "\n{letters_black}▎ {secondary}Took {letters_black}» {info}" + took + "ms"
        ).send(sender);
    }

    /**
     * Shows static identity information: version, platform, the switches from
     * {@code config.yml}, and which plugins on this server depend on the
     * library.
     *
     * <p>The dependent list is the union of two signals — see
     * {@link #dependentsOf(Plugin)} for why neither is enough alone: a
     * {@code plugin.yml} {@code depend}/{@code softdepend} declaration, and
     * every plugin {@link Debug#registeredPlugins()} has seen call
     * {@code Debug.of(this)}. Neither is a registry the library added for
     * this — both already existed for their own reasons.
     *
     * @param sender who asked
     */
    @Subcommand("info")
    @CommandPermission("exylialib.admin")
    public void info(@NotNull CommandSender sender) {
        LibrarySettings current = settings.get();
        List<Dependent> plugins = dependents.get();

        StringBuilder text = new StringBuilder(header());
        text.append("\n{letters_black}▎ {secondary}Platform {letters_black}» {info}")
                .append(platform.get());
        text.append("\n{letters_black}▎ {secondary}Auto-update {letters_black}» ")
                .append(onOff(current.autoUpdate()))
                .append(current.autoUpdate()
                        ? " {letters}(every {info}" + current.updateCheckMinutes() + "{letters}min)"
                        : "");
        text.append("\n{letters_black}▎ {secondary}Debug {letters_black}» ").append(onOff(current.debug()));
        text.append("\n{letters_black}▎ {secondary}Small text {letters_black}» ").append(onOff(current.smallText()));

        text.append("\n\n{secondary}Depending plugins:");
        if (plugins.isEmpty()) {
            text.append("\n{letters_black}▎ {muted}none found on this server");
        } else {
            for (Dependent dependent : plugins) {
                text.append("\n{letters_black}▎ {letters}").append(dependent.name())
                        .append(" {letters_black}» {info}v").append(dependent.version());
            }
        }

        Text.of(text.toString()).send(sender);
    }

    /**
     * Shows live runtime counters from every module, all read from public
     * entry points that already exist — nothing here is a new counter.
     *
     * @param sender who asked
     */
    @Subcommand("stats")
    @CommandPermission("exylialib.admin")
    public void stats(@NotNull CommandSender sender) {
        StringBuilder text = new StringBuilder(header());

        text.append("\n{letters_black}▎ {secondary}Scoreboards {letters_black}» {info}")
                .append(BoardManager.activeCount());
        text.append("\n{letters_black}▎ {secondary}Holograms {letters_black}» ")
                .append(hologramsLine(HologramRuntime.isSupported(), HologramRuntime.count()));
        text.append("\n{letters_black}▎ {secondary}Effects {letters_black}» {info}")
                .append(Effects.active());
        text.append("\n{letters_black}▎ {secondary}Menus {letters_black}» {info}")
                .append(Menus.registered()).append(" {letters}plugins");
        text.append("\n{letters_black}▎ {secondary}Actions {letters_black}» {info}")
                .append(Actions.registered());
        text.append("\n{letters_black}▎ {secondary}Regions {letters_black}» {info}")
                .append(Regions.registered()).append(" {letters}plugins");
        text.append("\n{letters_black}▎ {secondary}Configs loaded {letters_black}» {info}")
                .append(Configs.loaded().size());

        text.append("\n\n{secondary}Database:");
        text.append("\n{letters_black}▎ {letters}Engine {letters_black}» {info}")
                .append(Databases.engine())
                .append(" {letters_black}(").append(onOff(Databases.isReady())).append("{letters_black})");
        text.append("\n{letters_black}▎ {letters}Plugins {letters_black}» {info}")
                .append(Databases.registered());
        text.append("\n{letters_black}▎ {letters}Redis {letters_black}» ")
                .append(Redis.isActive() ? "{success}on {letters_black}(" + Redis.stats() + ")" : "{muted}off");

        Text.of(text.toString()).send(sender);
    }

    // -------------------------------------------------------------- transfer

    /**
     * Writes one plugin's tables into a dump.
     *
     * <p>The tables found are named before the work starts, not only counted.
     * A plugin only appears in {@link Databases#find(String)} once it has asked
     * for its first repository, so one that registers a record type lazily
     * exports fewer tables than it owns — and the only way anybody notices is
     * by reading the names against what they expected.
     *
     * @param sender     who asked
     * @param pluginName the plugin whose tables to export
     */
    @Subcommand("export")
    @CommandPermission("exylialib.admin")
    public void export(@NotNull CommandSender sender,
                       @SuggestWith(KnownPlugins.class) @NotNull String pluginName) {
        List<String> tables = transfers.tablesOf(pluginName);
        if (tables == null || tables.isEmpty()) {
            Text.of(unknownPlugin(pluginName, transfers.plugins())).send(sender);
            return;
        }
        Path folder = dumpFolder.get();
        Text.of(header()
                + "\n{letters_black}▎ {secondary}Exporting {letters_black}» {info}" + pluginName
                + " {letters}(" + tables.size() + " tables)"
                + "\n{letters_black}▎ {secondary}Tables {letters_black}» {letters}"
                + String.join("{letters_black}, {letters}", tables)
                + "\n{letters_black}▎ {muted}A table a plugin registers lazily is not listed here"
                + " and will not be exported."
        ).send(sender);

        transfers.export(pluginName, folder).thenAccept(report ->
                Text.of(reportPanel("Export", pluginName, report)).send(sender));
    }

    /**
     * Reads a dump into one plugin's tables.
     *
     * <p>The file argument is a name inside the dump folder rather than a whole
     * path: an admin typing a path into chat is one slash away from reading
     * something the server should not, and the folder is where {@code export}
     * put it anyway.
     *
     * @param sender     who asked
     * @param pluginName the plugin whose tables to write
     * @param fileName   the dump's file name, inside the dump folder
     * @param force      whether to write into tables that already hold rows
     */
    @Subcommand("import")
    @CommandPermission("exylialib.admin")
    public void importDump(@NotNull CommandSender sender,
                           @SuggestWith(KnownPlugins.class) @NotNull String pluginName,
                           @NotNull String fileName,
                           @Optional @Default("false") boolean force) {
        List<String> tables = transfers.tablesOf(pluginName);
        if (tables == null || tables.isEmpty()) {
            Text.of(unknownPlugin(pluginName, transfers.plugins())).send(sender);
            return;
        }
        Path file = dumpFolder.get().resolve(safeName(fileName));
        Text.of(header()
                + "\n{letters_black}▎ {secondary}Importing {letters_black}» {info}" + pluginName
                + "\n{letters_black}▎ {secondary}File {letters_black}» {letters}" + file.getFileName()
                + "\n{letters_black}▎ {secondary}Mode {letters_black}» "
                + (force ? "{warning}force {letters}(merge)" : "{letters}safe")
        ).send(sender);

        transfers.importFrom(pluginName, file, force).thenAccept(report ->
                Text.of(importPanel(pluginName, fileName, report)).send(sender));
    }

    /**
     * The panel a finished transfer prints.
     *
     * <p>A pure function of the report so both branches — and every problem
     * line — are testable without a database, a file or a server.
     *
     * @param what       {@code "Export"} or {@code "Import"}
     * @param pluginName whose tables these were
     * @param report     what happened
     * @return the text, palette tokens included
     */
    static String reportPanel(String what, String pluginName, TransferReport report) {
        StringBuilder text = new StringBuilder("{primary}&l" + what.toUpperCase(java.util.Locale.ROOT)
                + "&r {muted}" + pluginName);
        text.append("\n{letters_black}▎ {secondary}Result {letters_black}» ")
                .append(outcome(report.outcome()));
        text.append("\n{letters_black}▎ {secondary}Rows {letters_black}» {info}").append(report.rows());
        text.append("\n{letters_black}▎ {secondary}Took {letters_black}» {info}")
                .append(report.took().toMillis()).append("ms");
        if (report.file() != null) {
            text.append("\n{letters_black}▎ {secondary}File {letters_black}» {letters}")
                    .append(report.file().getFileName());
        }
        for (TableTransfer table : report.tables()) {
            text.append("\n{letters_black}▎ {letters}").append(table.table())
                    .append(" {letters_black}» ")
                    .append(table.skipped() ? "{muted}skipped"
                            : (table.drifted() ? "{warning}" : "{info}") + table.rows()
                              + (table.drifted() ? " {letters}(layout drifted)" : ""));
        }
        if (!report.problems().isEmpty()) {
            text.append("\n\n{secondary}Problems:");
            for (String problem : report.problems()) {
                text.append("\n{letters_black}▎ {warning}").append(problem);
            }
        }
        return text.toString();
    }

    /**
     * The import panel, which adds the one thing an export never needs to say.
     *
     * <p>A refusal has to hand back the exact command that would go through,
     * and it has to say plainly that force merges. Somebody who reads "force"
     * as "replace" and runs it has silently mixed two servers into one table,
     * and nothing anywhere reports that.
     */
    static String importPanel(String pluginName, String fileName, TransferReport report) {
        String panel = reportPanel("Import", pluginName, report);
        boolean refused = report.outcome() == TransferOutcome.FAILED
                && report.problems().stream().anyMatch(line -> line.startsWith("Refused:"));
        if (!refused) {
            return panel;
        }
        return panel
                + "\n\n{warning}➥ Re-run with force to write anyway:"
                + "\n{letters_black}▎ {muted}/exylialib import " + pluginName + " " + fileName
                + " true"
                + "\n{letters_black}▎ {error}force MERGES, it does not replace{letters}: rows whose"
                + " key is in the dump are overwritten, rows that are not in the dump stay exactly"
                + " where they are.";
    }

    /** The refusal when a name matches no plugin that stores anything. */
    static String unknownPlugin(String pluginName, List<String> known) {
        return "{primary}&lEXYLIALIB&r {muted}transfer"
                + "\n{letters_black}▎ {error}" + pluginName + " has no registered tables."
                + "\n{letters_black}▎ {letters}A plugin appears here once it has asked for its"
                + " first repository."
                + "\n{letters_black}▎ {secondary}Available {letters_black}» "
                + (known.isEmpty() ? "{muted}none" : "{letters}" + String.join("{letters_black}, {letters}", known));
    }

    private static String outcome(TransferOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> "{success}success";
            case PARTIAL -> "{warning}partial";
            case FAILED -> "{error}failed";
        };
    }

    /**
     * A file name with no way out of the dump folder.
     *
     * <p>{@code Path.resolve} on {@code ../../server.properties} leaves the
     * folder, and this argument arrives from a chat box. Taking only the last
     * element of whatever was typed is what keeps the resolve inside.
     */
    static String safeName(String typed) {
        Path name = Path.of(typed).getFileName();
        return name == null ? typed : name.toString();
    }

    /**
     * The plugins an argument can name: the ones that actually store something.
     *
     * <p>Public and with a no-argument constructor because Lamp builds it
     * itself from {@code @SuggestWith}. It reads the same registry the command
     * does, so a name that is suggested is a name that resolves.
     */
    public static final class KnownPlugins implements SuggestionProvider<BukkitCommandActor> {

        @Override
        public java.util.Collection<String> getSuggestions(
                @NotNull ExecutionContext<BukkitCommandActor> context) {
            return Databases.registeredPlugins();
        }
    }

    /**
     * Shared header: bold name, muted version, for every subcommand's output.
     *
     * <p>The bold code is closed with {@code &r} right after the name. Legacy
     * codes are not scoped to the word they were written next to — an unclosed
     * {@code &l} stays open for the rest of the string, which is everything
     * else this text builds. Without the reset, the whole panel renders bold.
     */
    private String header() {
        return "{primary}&lEXYLIALIB&r {muted}v" + version.get();
    }

    private static String onOff(boolean value) {
        return value ? "{success}on" : "{error}off";
    }

    /**
     * Formats the holograms line: a count when the module is supported, or an
     * explicit "not supported" note when it is not.
     *
     * <p>Without PacketEvents, {@link HologramRuntime#count()} is always
     * {@code 0}, which reads exactly like "nothing is displayed" when the
     * truth is "the feature is off". Extracted as a pure function so the two
     * branches are testable without depending on {@code HologramRuntime}'s
     * global, JVM-wide {@code available} flag.
     *
     * @param supported whether {@link HologramRuntime#isSupported()} reported true
     * @param count     the live hologram count, ignored when unsupported
     * @return the formatted line, palette tokens included
     */
    static String hologramsLine(boolean supported, int count) {
        return supported ? "{info}" + count : "{muted}N/A (PacketEvents not present)";
    }

    /** One plugin found to depend on ExyliaLib, and the version it reports. */
    record Dependent(String name, String version) {
    }

    /**
     * Finds every plugin that actually uses the library, combining two
     * signals that neither covers alone.
     *
     * <p>{@code plugin.yml}'s {@code depend}/{@code softdepend} is what a
     * plugin <em>declared</em> — accurate when present, but easy to miss: a
     * plugin can call {@code Databases.of(this)} or {@code Menus.of(this)}
     * without ever naming the library, and Bukkit still starts it in the
     * right order by luck of load ordering. {@link Debug#registeredPlugins()}
     * is the opposite: it only knows a plugin once it has actually called
     * into the library — {@code Debug.of(this)} is the one call nearly every
     * module reaches for when it logs on a consumer's behalf — but it says
     * nothing about a plugin that has not logged anything yet.
     *
     * <p>The union of both is the closest this command gets to ground truth
     * without adding a registry the library did not already have. A plugin
     * only in the declared list, but silent so far, still gets a fair
     * mention; one only seen through {@code Debug} still gets listed even
     * though it never declared the dependency. The library itself is always
     * excluded, and only enabled plugins are considered.
     *
     * @param library the running ExyliaLib instance, excluded from its own list
     * @return the dependents, sorted by name
     */
    @SuppressWarnings("deprecation") // getDescription(): the portable call, see ExyliaLib#version()
    static List<Dependent> dependentsOf(Plugin library) {
        java.util.Set<String> seen = Debug.registeredPlugins();
        java.util.Map<String, Dependent> found = new java.util.TreeMap<>();
        for (Plugin candidate : Bukkit.getPluginManager().getPlugins()) {
            if (!candidate.isEnabled() || candidate.getName().equals(library.getName())) {
                continue;
            }
            boolean declared = dependsOnLibrary(candidate, library.getName());
            boolean active = seen.contains(candidate.getName());
            if (declared || active) {
                found.put(candidate.getName(),
                        new Dependent(candidate.getName(), candidate.getDescription().getVersion()));
            }
        }
        return List.copyOf(found.values());
    }

    /**
     * Whether a plugin's description names {@code libraryName} in either
     * dependency list, compared case-insensitively since {@code plugin.yml}
     * authors are inconsistent about casing.
     *
     * <p>Extracted as a pure function of {@link Plugin#getDescription()} so it
     * is testable without a fake {@code PluginManager} plugin list.
     *
     * @param candidate    the plugin whose description is inspected
     * @param libraryName  the name to look for, e.g. {@code "ExyliaLib"}
     * @return whether the candidate declares a dependency on that name
     */
    @SuppressWarnings("deprecation") // getDescription(): the portable call, see ExyliaLib#version()
    static boolean dependsOnLibrary(Plugin candidate, String libraryName) {
        return namesMatch(candidate.getDescription().getDepend(), libraryName)
                || namesMatch(candidate.getDescription().getSoftDepend(), libraryName);
    }

    private static boolean namesMatch(List<String> names, String libraryName) {
        if (names == null) {
            return false;
        }
        for (String name : names) {
            if (name.equalsIgnoreCase(libraryName)) {
                return true;
            }
        }
        return false;
    }
}
