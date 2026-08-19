package net.exylia.lib.internal;

import net.exylia.lib.ExyliaLib;
import net.exylia.lib.action.Actions;
import net.exylia.lib.config.Configs;
import net.exylia.lib.database.Databases;
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
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.bukkit.annotation.CommandPermission;

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

    public ReloadCommand(@NotNull ExyliaLib plugin) {
        this(plugin::reloadPalette, plugin::version, Platform::current,
                LibrarySettings::get, () -> dependentsOf(plugin));
    }

    /** Test seam: every data source is injected. */
    ReloadCommand(Runnable paletteReload, Supplier<String> version, Supplier<Platform> platform,
                  Supplier<LibrarySettings> settings, Supplier<List<Dependent>> dependents) {
        this.paletteReload = paletteReload;
        this.version = version;
        this.platform = platform;
        this.settings = settings;
        this.dependents = dependents;
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
     * <p>The dependent list comes from {@link org.bukkit.plugin.PluginManager}
     * alone — each enabled plugin's {@code plugin.yml} is checked for
     * {@code ExyliaLib} under {@code depend} or {@code softdepend}, compared
     * case-insensitively. This is a read of data Bukkit already holds, not a
     * registry the library maintains.
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

    /** Shared header: bold name, muted version, for every subcommand's output. */
    private String header() {
        return "{primary}&lEXYLIALIB {muted}v" + version.get();
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
     * Finds every enabled plugin whose {@code plugin.yml} names
     * {@code ExyliaLib} under {@code depend} or {@code softdepend}.
     *
     * <p>Pure inspection of {@link org.bukkit.plugin.PluginManager}: no
     * registry is added to the library for this, since Bukkit already knows
     * the answer for every installed plugin. The library itself is always
     * excluded.
     *
     * @param library the running ExyliaLib instance, excluded from its own list
     * @return the dependents, in the order the server reports them
     */
    @SuppressWarnings("deprecation") // getDescription(): the portable call, see ExyliaLib#version()
    static List<Dependent> dependentsOf(Plugin library) {
        List<Dependent> found = new java.util.ArrayList<>();
        for (Plugin candidate : Bukkit.getPluginManager().getPlugins()) {
            if (!candidate.isEnabled() || candidate.getName().equals(library.getName())) {
                continue;
            }
            if (dependsOnLibrary(candidate, library.getName())) {
                found.add(new Dependent(candidate.getName(), candidate.getDescription().getVersion()));
            }
        }
        return List.copyOf(found);
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
