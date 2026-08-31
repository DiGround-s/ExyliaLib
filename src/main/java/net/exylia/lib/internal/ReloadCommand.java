package net.exylia.lib.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.ExyliaLib;
import net.exylia.lib.action.Actions;
import net.exylia.lib.config.Configs;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.PluginDatabase;
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
import net.exylia.lib.internal.ExyliaLibUpdater.UpdateOutcome;
import net.exylia.lib.internal.ExyliaLibUpdater.UpdateStatus;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import net.exylia.lib.ui.Menus;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
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
 * {@code exylialib.admin}, except {@code wipe}, which is behind
 * {@code exylialib.admin.wipe}: it is the only one that destroys data, and a
 * server that hands the admin node to a moderator should be able to hand it
 * without that. The values shown (plugin names, module counts) are
 * not secrets, but a server's admin commands are conventionally gated by one
 * node, and splitting the four here would only make that node harder to
 * reason about for no real gain in usability.
 */
@Command("exylialib")
public final class ReloadCommand {

    /** How long a wipe confirmation stays typeable. */
    static final long CONFIRM_SECONDS = 60L;

    /** The table argument that means every table the plugin registered. */
    static final String ALL_TABLES = "*";

    /**
     * The word {@link #ALL_TABLES} is also accepted as.
     *
     * <p>Both are suggested and both work. {@code *} is what the panels print,
     * because it cannot be read as a table called "all"; {@code all} is what an
     * admin types when they are not looking at a panel, and refusing it would
     * only teach them that the command is fussy.
     */
    static final String ALL_TABLES_WORD = "all";

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

    /**
     * Wipes somebody has asked for and not yet confirmed, one per sender.
     *
     * <p>Expiring rather than cleared by hand: a confirmation nobody types is
     * the normal case — somebody reads what it would delete and thinks better
     * of it — and an entry that outlived that decision is a code still live
     * minutes later, when the same admin is running something else. Sixty
     * seconds is long enough to read the panel and short enough that the
     * command that follows is the one that was being thought about.
     */
    private final Cache<String, PendingWipe> pendingWipes = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(CONFIRM_SECONDS))
            .maximumSize(64)
            .build();

    /**
     * How an update check is started, injected so a test does not need a
     * network. Returns a future because the check talks to GitHub and the
     * command runs on the main thread.
     */
    private final Supplier<CompletableFuture<UpdateOutcome>> updateCheck;

    public ReloadCommand(@NotNull ExyliaLib plugin) {
        this(plugin::reloadPalette, plugin::version, Platform::current,
                LibrarySettings::get, () -> dependentsOf(plugin),
                () -> plugin.getDataFolder().toPath().resolve("dumps"),
                TransferAccess.live(), () -> checkAsync(plugin));
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
        this(paletteReload, version, platform, settings, dependents, dumpFolder, transfers,
                () -> CompletableFuture.completedFuture(
                        new UpdateOutcome(UpdateStatus.UP_TO_DATE, version.get(), null)));
    }

    /** Test seam: the update side too, so no network has to exist. */
    ReloadCommand(Runnable paletteReload, Supplier<String> version, Supplier<Platform> platform,
                  Supplier<LibrarySettings> settings, Supplier<List<Dependent>> dependents,
                  Supplier<Path> dumpFolder, TransferAccess transfers,
                  Supplier<CompletableFuture<UpdateOutcome>> updateCheck) {
        this.paletteReload = paletteReload;
        this.version = version;
        this.platform = platform;
        this.settings = settings;
        this.dependents = dependents;
        this.dumpFolder = dumpFolder;
        this.transfers = transfers;
        this.updateCheck = updateCheck;
    }

    /**
     * Runs the check off the main thread and hands back its answer.
     *
     * <p>The check is an HTTP round trip and, when there is something to fetch,
     * a two-megabyte download. On the main thread that is the server frozen for
     * as long as GitHub takes.
     */
    private static CompletableFuture<UpdateOutcome> checkAsync(ExyliaLib plugin) {
        CompletableFuture<UpdateOutcome> answer = new CompletableFuture<>();
        Tasks.of(plugin).runAsync(() -> {
            try {
                answer.complete(ExyliaLibUpdater.stageNow(plugin));
            } catch (Throwable failure) {
                // Completed rather than rethrown: a sender who typed the
                // command is owed an answer, and an uncaught throwable on the
                // async thread would leave them watching nothing happen.
                answer.complete(new UpdateOutcome(
                        UpdateStatus.FAILED, plugin.version(), String.valueOf(failure.getMessage())));
            }
        });
        return answer;
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
                + "\n{letters_black}▎ {secondary}Update {letters_black}» {letters}"
                + "{muted}/exylialib update{letters} — checks GitHub now and stages a newer release."
                + "\n{letters_black}▎ {secondary}Export {letters_black}» {letters}"
                + "{muted}/exylialib export <plugin>{letters} — writes that plugin's tables to a dump."
                + "\n{letters_black}▎ {secondary}Import {letters_black}» {letters}"
                + "{muted}/exylialib import <plugin> <file> [force]{letters} — reads one back;"
                + " force MERGES rather than replacing."
                + "\n{letters_black}▎ {secondary}Wipe {letters_black}» {letters}"
                + "{muted}/exylialib wipe <plugin> <table|*>{letters} — empties tables, after a"
                + " typed confirmation and an automatic dump."
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

    // ---------------------------------------------------------------- update

    /**
     * Checks GitHub now and stages a newer release if there is one.
     *
     * <p>Runs regardless of {@code auto-update}: that setting governs the
     * checks nobody asked for, and this one was typed. The jar is verified
     * to be the release it claims and written to the update folder, which the
     * server applies while it discovers plugins — so the answer is always
     * "restart", never "done".
     *
     * @param sender who asked
     */
    @Subcommand("update")
    @CommandPermission("exylialib.admin")
    public void update(@NotNull CommandSender sender) {
        Text.of(header()
                + "\n{letters_black}▎ {secondary}Checking {letters_black}» {letters}"
                + "asking GitHub for the newest release..."
        ).send(sender);

        updateCheck.get().thenAccept(outcome ->
                Text.of(updatePanel(outcome)).send(sender));
    }

    /**
     * The panel a finished check prints.
     *
     * <p>A pure function of the outcome, so every branch is testable without a
     * network.
     *
     * @param outcome what the check found
     * @return the text, palette tokens included
     */
    static String updatePanel(UpdateOutcome outcome) {
        StringBuilder text = new StringBuilder("{primary}&lEXYLIALIB&r {muted}update");
        switch (outcome.status()) {
            case UP_TO_DATE, DISABLED -> text
                    .append("\n{letters_black}▎ {success}Up to date {letters_black}» {letters}running {info}")
                    .append(outcome.version())
                    .append("{letters}, which is the newest release.");
            case STAGED -> text
                    .append("\n{letters_black}▎ {success}Staged {letters_black}» {info}")
                    .append(outcome.version())
                    .append(" {letters}downloaded and verified.")
                    .append("\n\n{warning}➥ Restart the server to apply it")
                    .append("\n{letters_black}▎ {muted}A reload cannot swap a library every plugin is bound to.");
            case ALREADY_STAGED -> text
                    .append("\n{letters_black}▎ {secondary}Already staged {letters_black}» {info}")
                    .append(outcome.version())
                    .append(" {letters}is waiting in the update folder.")
                    .append("\n\n{warning}➥ Restart the server to apply it");
            case FAILED -> text
                    .append("\n{letters_black}▎ {error}Failed {letters_black}» {letters}")
                    .append(outcome.detail() == null ? "the check did not finish" : outcome.detail())
                    .append("\n{letters_black}▎ {muted}Still running {letters}")
                    .append(outcome.version())
                    .append("{muted}. Nothing was changed.");
        }
        return text.toString();
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
     * Empties a plugin's tables, in two steps, after a dump is written.
     *
     * <p>Two steps because there is no undo. The first run deletes nothing: it
     * names what would go and hands back a code. The second run is the same
     * command with that code on the end, and only that sender's code, only
     * within {@value #CONFIRM_SECONDS} seconds, and only for the same plugin
     * and table, will do anything. A confirmation that is merely "run it
     * again" is confirmed by an arrow-up and an enter, which is how the
     * accident this is guarding against actually happens.
     *
     * <p>An export runs first, always, and a wipe whose export failed does not
     * happen. That is the difference between a mistake somebody recovers from
     * in one command and a mistake that ends the conversation.
     *
     * @param sender     who asked
     * @param pluginName the plugin whose tables to empty
     * @param table      one table's name, or {@code *} for every registered one
     * @param code       the code from the first run, on the second run
     */
    @Subcommand("wipe")
    @CommandPermission("exylialib.admin.wipe")
    public void wipe(@NotNull CommandSender sender,
                     @SuggestWith(KnownPlugins.class) @NotNull String pluginName,
                     @SuggestWith(WipeTargets.class) @NotNull String table,
                     @Optional String code) {
        List<String> tables = transfers.tablesOf(pluginName);
        if (tables == null || tables.isEmpty()) {
            Text.of(unknownPlugin(pluginName, transfers.plugins())).send(sender);
            return;
        }
        boolean everything = ALL_TABLES.equals(table) || ALL_TABLES_WORD.equalsIgnoreCase(table);
        if (!everything && tables.stream().noneMatch(known -> known.equalsIgnoreCase(table))) {
            Text.of(unknownTable(pluginName, table, tables)).send(sender);
            return;
        }
        String target = everything ? null : table;

        String who = String.valueOf(sender.getName());
        if (code == null) {
            String issued = newCode();
            pendingWipes.put(who, new PendingWipe(pluginName, target, issued));
            Text.of(wipePreview(pluginName, target, everything ? tables : List.of(table),
                    issued, dumpFolder.get())).send(sender);
            return;
        }

        PendingWipe pending = pendingWipes.getIfPresent(who);
        if (pending == null || !pending.matches(pluginName, target, code)) {
            Text.of(badConfirmation(pluginName, table)).send(sender);
            return;
        }
        // Taken before the work starts, not after: a code that survives its own
        // wipe is a second wipe one arrow-up away.
        pendingWipes.invalidate(who);

        Text.of(header()
                + "\n{letters_black}▎ {secondary}Wiping {letters_black}» {info}" + pluginName
                + " {letters}(" + (everything ? tables.size() + " tables" : table) + ")"
                + "\n{letters_black}▎ {secondary}Backup {letters_black}» {letters}writing a dump"
                + " first, into " + dumpFolder.get().getFileName()
        ).send(sender);

        transfers.export(pluginName, dumpFolder.get()).thenCompose(backup -> {
            if (backup.outcome() == TransferOutcome.FAILED) {
                Text.of(wipeAborted(pluginName, backup)).send(sender);
                return CompletableFuture.completedFuture(null);
            }
            return transfers.wipe(pluginName, target).thenAccept(report ->
                    Text.of(wipePanel(pluginName, target, backup, report)).send(sender));
        });
    }

    /** A code short enough to type and long enough not to be guessed at. */
    private static String newCode() {
        return Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000, 0x100000));
    }

    /** One wipe waiting for its code, and what that code was issued for. */
    record PendingWipe(@NotNull String plugin, @Nullable String table, @NotNull String code) {

        /**
         * Whether a second command is the one this was issued for.
         *
         * <p>The plugin and the table are checked as well as the code, so a
         * code issued for one table cannot confirm a wipe of another: an admin
         * who edits the plugin name in the line they are about to re-send has
         * changed what the command does, and the code they are carrying over
         * was never shown for it.
         */
        boolean matches(String plugin, @Nullable String table, String code) {
            return this.plugin.equalsIgnoreCase(plugin)
                    && java.util.Objects.equals(this.table, table)
                    && this.code.equalsIgnoreCase(code);
        }
    }

    /**
     * What the first run prints: what would go, and the line that would do it.
     *
     * <p>Pure, so the wording of the one panel nobody should misread is
     * testable without a database or a server.
     */
    static String wipePreview(String pluginName, @Nullable String table, List<String> tables,
                              String code, Path dumpFolder) {
        return "{primary}&lWIPE&r {muted}" + pluginName
                + "\n{letters_black}▎ {error}This deletes rows. There is no undo."
                + "\n{letters_black}▎ {secondary}Target {letters_black}» {letters}"
                + (table == null ? "every registered table (" + tables.size() + ")" : table)
                + "\n{letters_black}▎ {secondary}Tables {letters_black}» {letters}"
                + String.join("{letters_black}, {letters}", tables)
                + "\n{letters_black}▎ {secondary}Backup {letters_black}» {letters}a dump is written"
                + " into " + dumpFolder.getFileName() + " first, and the wipe is cancelled if it fails"
                + "\n\n{warning}➥ Confirm within " + CONFIRM_SECONDS + "s:"
                + "\n{letters_black}▎ {muted}/exylialib wipe " + pluginName + " "
                + (table == null ? ALL_TABLES : table) + " " + code;
    }

    /** The refusal when a code is wrong, missing or too late. */
    static String badConfirmation(String pluginName, String table) {
        return "{primary}&lWIPE&r {muted}" + pluginName
                + "\n{letters_black}▎ {error}That confirmation is not valid."
                + "\n{letters_black}▎ {letters}A code is issued for one sender, one plugin and one"
                + " table, and expires after " + CONFIRM_SECONDS + " seconds."
                + "\n{letters_black}▎ {muted}Nothing was deleted."
                + "\n\n{warning}➥ Start again:"
                + "\n{letters_black}▎ {muted}/exylialib wipe " + pluginName + " " + table;
    }

    /** The refusal when a plugin has no table by that name. */
    static String unknownTable(String pluginName, String table, List<String> known) {
        return "{primary}&lWIPE&r {muted}" + pluginName
                + "\n{letters_black}▎ {error}" + pluginName + " has no table named " + table + "."
                + "\n{letters_black}▎ {secondary}Tables {letters_black}» {letters}"
                + String.join("{letters_black}, {letters}", known)
                + "\n{letters_black}▎ {muted}Use " + ALL_TABLES + " (or " + ALL_TABLES_WORD
                + ") to wipe every one of them.";
    }

    /** What a wipe prints when the dump that would have saved it failed. */
    static String wipeAborted(String pluginName, TransferReport backup) {
        StringBuilder text = new StringBuilder("{primary}&lWIPE&r {muted}" + pluginName)
                .append("\n{letters_black}▎ {error}Cancelled {letters_black}» {letters}the backup"
                        + " export failed, so nothing was deleted.");
        for (String problem : backup.problems()) {
            text.append("\n{letters_black}▎ {warning}").append(problem);
        }
        return text.toString();
    }

    /** What a finished wipe prints: the dump it took first, then the rows it removed. */
    static String wipePanel(String pluginName, @Nullable String table, TransferReport backup,
                            TransferReport report) {
        StringBuilder text = new StringBuilder(reportPanel("Wipe", pluginName, report));
        if (backup.file() != null) {
            text.append("\n{letters_black}▎ {secondary}Backup {letters_black}» {letters}")
                    .append(backup.file().getFileName())
                    .append(" {muted}(").append(backup.rows()).append(" rows)");
        }
        if (report.outcome() != TransferOutcome.FAILED) {
            text.append("\n\n{warning}➥ Restore it with:")
                    .append("\n{letters_black}▎ {muted}/exylialib import ").append(pluginName)
                    .append(' ').append(backup.file() == null ? "<dump>" : backup.file().getFileName())
                    .append(" true");
        }
        return text.toString();
    }

    /**
     * The tables an argument can name: the ones the plugin already typed
     * registers, plus {@code *}.
     *
     * <p>Reads the plugin argument out of the context, so the suggestions
     * follow what is being typed rather than offering every table on the
     * server. A plugin that has not been typed yet suggests only {@code *},
     * which is the one answer that is right whatever comes before it.
     */
    public static final class WipeTargets implements SuggestionProvider<BukkitCommandActor> {

        @Override
        public java.util.Collection<String> getSuggestions(
                @NotNull ExecutionContext<BukkitCommandActor> context) {
            String plugin = context.getResolvedArgumentOrNull("pluginName");
            if (plugin == null) {
                return List.of(ALL_TABLES, ALL_TABLES_WORD);
            }
            PluginDatabase database = Databases.find(plugin);
            if (database == null) {
                return List.of(ALL_TABLES, ALL_TABLES_WORD);
            }
            List<String> names = new java.util.ArrayList<>(database.tables().keySet());
            names.add(ALL_TABLES);
            names.add(ALL_TABLES_WORD);
            return names;
        }
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
