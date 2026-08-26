package net.exylia.lib.debug;

import com.github.lalyos.jfiglet.FigletFont;
import net.exylia.lib.text.Colors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a plugin says to the console, in colour.
 *
 * <pre>{@code
 * private Debug debug;
 *
 * @Override
 * public void onEnable() {
 *     debug = Debug.of(this);
 *     debug.enabled(getConfig().getBoolean("debug"));
 *     debug.motd();
 *     debug.success("Ready in " + millis + "ms");
 * }
 * }</pre>
 *
 * <h2>Deliberately small</h2>
 * Six methods and one toggle. There are no categories, no numeric levels and
 * no configuration format: a message is a log, a success, a warning, an
 * error, or a debug line that only shows when enabled. ExyliaCommons shipped
 * four axes of classification and forty entry points to say the same five
 * things; nobody picks the right combination at 3 a.m., which is when debug
 * output matters.
 *
 * <h2>The shape of a line</h2>
 * {@code [PluginName] [WARN] the message}. The name is painted secondary to
 * primary and back; the label says which of the five kinds this is and wears
 * that kind's colour. Both come from the server's palette, so a recoloured
 * server recolours its console too. The message itself is appended literally
 * — a stack trace or a config line full of {@code &} and {@code {} } prints
 * as-is.
 *
 * <h2>Who a line belongs to</h2>
 * The name in front is the plugin passed to {@link #of(Plugin)}, and it
 * answers <em>whose problem this is</em> rather than which jar the code lives
 * in. The library reports an unreadable {@code database.yml} against the
 * plugin that wrote it, and its own cross-server trouble against itself.
 * ExyliaCommons split this into a pair of methods per kind — a plugin one and
 * a library one — which asked the caller a question the reader never asks, and
 * got it wrong invisibly.
 *
 * <h2>The banner</h2>
 * {@link #motd()} prints the plugin's name in ASCII art — the ExyliaCommons
 * touch worth keeping — with its version, debug state and the Exylia link.
 *
 * @since 1.13.0
 */
public final class Debug {

    private static final Map<String, Debug> BY_PLUGIN = new ConcurrentHashMap<>();

    /** Fallbacks match the palette defaults, for when the lib is not loaded. */
    private static final TextColor PRIMARY = TextColor.color(0x8a51c4);
    private static final TextColor SECONDARY = TextColor.color(0xaa76de);
    private static final TextColor LETTERS = TextColor.color(0xe7cfff);
    private static final TextColor SUCCESS = TextColor.color(0x8fffc1);
    private static final TextColor WARNING = TextColor.color(0xff9500);
    private static final TextColor ERROR = TextColor.color(0xa33b53);
    private static final TextColor INFO = TextColor.color(0x59a4ff);
    private static final TextColor MUTED = TextColor.color(0x868e96);
    private static final TextColor BRACKETS = TextColor.color(0x868e96);

    /**
     * Where lines go. Replaced in tests; by default, the server's console,
     * which renders component colours as terminal colours.
     */
    private static volatile Sink sink = (line, error) ->
            Bukkit.getConsoleSender().sendMessage(line);

    /**
     * The library-wide switch, from {@code plugins/ExyliaLib/config.yml}.
     *
     * <p>Static because it answers a question no single plugin owns: a server
     * owner diagnosing a problem turns on the detail of everything at once,
     * rather than editing a dozen configs and restarting between each.
     */
    private static volatile boolean allEnabled;

    private final String name;
    private final Plugin plugin;
    private volatile boolean debugEnabled;

    private Debug(Plugin plugin) {
        this.plugin = plugin;
        this.name = plugin.getName();
    }

    /**
     * Returns the debug instance for a plugin, creating it on first use.
     *
     * @param plugin the plugin whose name prefixes every line
     * @return its debug instance
     */
    public static @NotNull Debug of(@NotNull Plugin plugin) {
        // Reloaded in place: an instance owned by a different Plugin object
        // belongs to the previous load, whose cleanup runs a tick after it was
        // disabled and has not had a tick yet.
        BY_PLUGIN.computeIfPresent(plugin.getName(),
                (ignored, debug) -> debug.plugin.equals(plugin) ? debug : null);
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), key -> new Debug(plugin));
    }

    /** Drops one plugin's instance. Called when the plugin disables. */
    public static void release(@NotNull String pluginName) {
        BY_PLUGIN.remove(pluginName);
    }

    /**
     * Drops one load of a plugin's instance, leaving a newer load's alone.
     *
     * <p>A plugin reloaded in place has two loads alive at once, because the
     * tool that reloaded it disabled and enabled within a single tick.
     *
     * @param plugin the load being let go
     * @since 1.64.0
     */
    public static void release(@NotNull Plugin plugin) {
        BY_PLUGIN.computeIfPresent(plugin.getName(),
                (ignored, debug) -> debug.plugin.equals(plugin) ? null : debug);
    }

    /** Drops every instance. Called by the library on shutdown. */
    public static void releaseAll() {
        BY_PLUGIN.clear();
    }

    /**
     * Names of every plugin that has called {@link #of(Plugin)}, for
     * diagnostics.
     *
     * <p>This is the widest signal the library has for "who uses ExyliaLib":
     * a plugin can run without a menu, a database or a scoreboard, but every
     * one written against the library ends up logging through {@code Debug}
     * sooner or later. A {@code depend}/{@code softdepend} entry in
     * {@code plugin.yml} is a separate, narrower signal — this one only knows
     * a plugin exists once it has actually called into the library.
     *
     * @return an immutable snapshot of the registered plugin names
     */
    public static @NotNull java.util.Set<String> registeredPlugins() {
        return java.util.Set.copyOf(BY_PLUGIN.keySet());
    }

    /**
     * Turns debug lines on for every plugin, from the library's own config.
     *
     * <p>Called by ExyliaLib when it loads {@code config.yml} and again when
     * that file is reloaded. It raises the floor rather than replacing each
     * plugin's toggle: a plugin that enabled its own stays enabled when this
     * is off, and everything prints when this is on. A server owner chasing a
     * bug flips one value instead of a dozen.
     *
     * @param enabled whether every plugin's debug lines print
     */
    public static void all(boolean enabled) {
        allEnabled = enabled;
    }

    /** Whether the library-wide switch is on. */
    public static boolean isAllEnabled() {
        return allEnabled;
    }

    /**
     * Sets whether {@link #debug(String)} prints. Everything else always does.
     *
     * @param enabled whether debug lines show
     * @return this instance
     */
    public @NotNull Debug enabled(boolean enabled) {
        this.debugEnabled = enabled;
        return this;
    }

    /** Returns whether debug lines show, from either switch. */
    public boolean isDebugEnabled() {
        return debugEnabled || allEnabled;
    }

    /** Prints an ordinary line: the day-to-day noise of a plugin running. */
    public void log(@NotNull String message) {
        send("INFO", "info", INFO, "letters", LETTERS, message);
    }

    /** Prints a line that says something went right, in the success colour. */
    public void success(@NotNull String message) {
        send("SUCCESS", "success", SUCCESS, "success", SUCCESS, message);
    }

    /** Prints a warning: wrong, but survivable. */
    public void warn(@NotNull String message) {
        send("WARN", "warning", WARNING, "warning", WARNING, message);
    }

    /** Prints an error. */
    public void error(@NotNull String message) {
        send("ERROR", "error", ERROR, "error", ERROR, message);
    }

    /** Prints an error followed by the throwable's stack trace. */
    public void error(@NotNull String message, @Nullable Throwable throwable) {
        send("ERROR", "error", ERROR, "error", ERROR, message, throwable);
    }

    /**
     * Prints a debug line, only when {@linkplain #enabled(boolean) enabled}.
     *
     * <p>For the detail nobody wants in production: timings, cache hits, the
     * values behind a decision. Toggle it from the plugin's config.
     */
    public void debug(@NotNull String message) {
        if (isDebugEnabled()) {
            send("DEBUG", "muted", MUTED, "muted", MUTED, message);
        }
    }

    /**
     * Prints the plugin's name in ASCII art, framed the ExyliaCommons way.
     *
     * <p>A blank line, the art, the version alongside whether debug is on, the
     * Exylia link, and a blank line to close. Call once from {@code onEnable}.
     * The version comes from the plugin description; a plugin without one
     * still gets the art and the link.
     */
    public void motd() {
        TextColor primary = Colors.get("primary", PRIMARY);
        TextColor muted = Colors.get("muted", MUTED);
        String art;
        try {
            art = FigletFont.convertOneLine(name);
        } catch (java.io.IOException missingFont) {
            // A broken jar without its font resource still gets a banner.
            art = name;
        }
        // The blank lines around the banner are the ExyliaCommons framing: a
        // banner wedged between two plugins' startup noise is not a banner.
        sink.send(Component.empty(), null);
        for (String line : art.split("\\n")) {
            // Figlet pads the last rows with blanks; printing them adds
            // nothing but console height.
            if (!line.isBlank()) {
                sink.send(Component.text(line, primary), null);
            }
        }
        PluginDescriptionFile description = plugin.getDescription();
        if (description != null && description.getVersion() != null) {
            sink.send(Component.text("Version: v" + description.getVersion(), muted)
                    .append(Component.text(" | ", BRACKETS))
                    .append(Component.text("Debug: " + isDebugEnabled(), muted)), null);
        }
        sink.send(Component.text("Powered by Exylia - ", muted)
                .append(Component.text("https://discord.exylia.net",
                        Colors.get("secondary", SECONDARY))), null);
        sink.send(Component.empty(), null);
    }

    private void send(String label, String labelToken, TextColor labelFallback,
                      String bodyToken, TextColor bodyFallback, String message) {
        send(label, labelToken, labelFallback, bodyToken, bodyFallback, message, null);
    }

    private void send(String label, String labelToken, TextColor labelFallback,
                      String bodyToken, TextColor bodyFallback, String message,
                      @Nullable Throwable error) {
        TextColor brackets = Colors.get("muted", BRACKETS);
        TextColor labelColour = Colors.get(labelToken, labelFallback);
        // The message is appended literally, never parsed: debug output is
        // exactly the place where a stray "&" or "{" must survive.
        Component line = Component.text("[", brackets)
                .append(gradientName())
                .append(Component.text("] ", brackets))
                .append(Component.text("[", brackets))
                .append(Component.text(label, labelColour))
                .append(Component.text("] ", brackets))
                .append(Component.text(message, Colors.get(bodyToken, bodyFallback)));
        sink.send(line, error);
        if (error != null) {
            // The stack belongs in the log file, with a level and the plugin's
            // name on it — printStackTrace wrote it to raw stdout, where it
            // arrives as unowned noise. This was the last antique in here.
            plugin.getLogger().log(java.util.logging.Level.WARNING, message, error);
        }
    }

    /**
     * The plugin's name, painted secondary &rarr; primary &rarr; secondary.
     *
     * <p>The ExyliaCommons look, rebuilt out of palette tokens rather than the
     * hardcoded hexes it used: a server that recolours its palette recolours
     * its console too. Read on every line rather than cached, which is what
     * lets this module answer a palette reload without an
     * {@code invalidateAll()} hook — a plugin name is a dozen characters and a
     * log line is not a hot path, so caching would buy nothing and cost the
     * coupling.
     */
    private Component gradientName() {
        TextColor edge = Colors.get("secondary", SECONDARY);
        TextColor middle = Colors.get("primary", PRIMARY);
        int length = name.length();
        if (length == 0) {
            return Component.empty();
        }
        if (length == 1) {
            return Component.text(name, middle);
        }
        Component painted = Component.empty();
        int last = length - 1;
        for (int index = 0; index < length; index++) {
            // Two runs: out to the middle and back, so both ends read as the
            // same colour however long the name is.
            double toMiddle = (double) index / (last / 2.0);
            double position = toMiddle <= 1 ? toMiddle : 2 - toMiddle;
            painted = painted.append(Component.text(name.charAt(index),
                    blend(edge, middle, position)));
        }
        return painted;
    }

    /** A colour {@code position} of the way from {@code from} to {@code to}. */
    private static TextColor blend(TextColor from, TextColor to, double position) {
        double clamped = Math.max(0, Math.min(1, position));
        return TextColor.color(
                (int) Math.round(from.red() + (to.red() - from.red()) * clamped),
                (int) Math.round(from.green() + (to.green() - from.green()) * clamped),
                (int) Math.round(from.blue() + (to.blue() - from.blue()) * clamped));
    }

    /** Test seam: captures lines instead of printing them. */
    interface Sink {
        void send(Component line, @Nullable Throwable error);
    }

    static void setSink(@NotNull Sink replacement) {
        sink = replacement;
    }

    static void resetSink() {
        sink = (line, error) ->
                Bukkit.getConsoleSender().sendMessage(line);
    }
}
