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
 * <h2>Colours</h2>
 * Colours come from the server's palette, so a recoloured server recolours
 * its logs too. The message itself is appended literally — a stack trace or
 * a config line full of {@code &} and {@code {} } prints as-is.
 *
 * <h2>The banner</h2>
 * {@link #motd()} prints the plugin's name in ASCII art — the ExyliaCommons
 * touch worth keeping — followed by its version.
 *
 * @since 1.13.0
 */
public final class Debug {

    private static final Map<String, Debug> BY_PLUGIN = new ConcurrentHashMap<>();

    /** Fallbacks match the palette defaults, for when the lib is not loaded. */
    private static final TextColor PRIMARY = TextColor.color(0x8a51c4);
    private static final TextColor LETTERS = TextColor.color(0xe7cfff);
    private static final TextColor SUCCESS = TextColor.color(0x8fffc1);
    private static final TextColor WARNING = TextColor.color(0xff9500);
    private static final TextColor ERROR = TextColor.color(0xa33b53);
    private static final TextColor MUTED = TextColor.color(0x868e96);

    /**
     * Where lines go. Replaced in tests; by default, the server's console,
     * which renders component colours as terminal colours.
     */
    private static volatile Sink sink = (line, error) ->
            Bukkit.getConsoleSender().sendMessage(line);

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
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), key -> new Debug(plugin));
    }

    /** Drops one plugin's instance. Called when the plugin disables. */
    public static void release(@NotNull String pluginName) {
        BY_PLUGIN.remove(pluginName);
    }

    /** Drops every instance. Called by the library on shutdown. */
    public static void releaseAll() {
        BY_PLUGIN.clear();
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

    /** Returns whether debug lines show. */
    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    /** Prints an ordinary line: the day-to-day noise of a plugin running. */
    public void log(@NotNull String message) {
        send("letters", LETTERS, message);
    }

    /** Prints a line that says something went right, in the success colour. */
    public void success(@NotNull String message) {
        send("success", SUCCESS, message);
    }

    /** Prints a warning: wrong, but survivable. */
    public void warn(@NotNull String message) {
        send("warning", WARNING, message);
    }

    /** Prints an error. */
    public void error(@NotNull String message) {
        send("error", ERROR, message);
    }

    /** Prints an error followed by the throwable's stack trace. */
    public void error(@NotNull String message, @Nullable Throwable throwable) {
        send("error", ERROR, message, throwable);
    }

    /**
     * Prints a debug line, only when {@linkplain #enabled(boolean) enabled}.
     *
     * <p>For the detail nobody wants in production: timings, cache hits, the
     * values behind a decision. Toggle it from the plugin's config.
     */
    public void debug(@NotNull String message) {
        if (debugEnabled) {
            send("muted", MUTED, message);
        }
    }

    /**
     * Prints the plugin's name in ASCII art, with its version underneath.
     *
     * <p>Call once from {@code onEnable}. The version comes from the plugin
     * description; a plugin without one just gets the art.
     */
    public void motd() {
        TextColor primary = Colors.get("primary", PRIMARY);
        String art;
        try {
            art = FigletFont.convertOneLine(name);
        } catch (java.io.IOException missingFont) {
            // A broken jar without its font resource still gets a banner.
            art = name;
        }
        for (String line : art.split("\n")) {
            // Figlet pads the last rows with blanks; printing them adds
            // nothing but console height.
            if (!line.isBlank()) {
                sink.send(Component.text(line, primary), null);
            }
        }
        PluginDescriptionFile description = plugin.getDescription();
        if (description != null && description.getVersion() != null) {
            sink.send(Component.text("v" + description.getVersion(),
                    Colors.get("muted", MUTED)), null);
        }
    }

    private void send(String token, TextColor fallback, String message) {
        send(token, fallback, message, null);
    }

    private void send(String token, TextColor fallback, String message,
                      @Nullable Throwable error) {
        // The message is appended literally, never parsed: debug output is
        // exactly the place where a stray "&" or "{" must survive.
        Component line = Component.text("[" + name + "] ", Colors.get("primary", PRIMARY))
                .append(Component.text(message, Colors.get(token, fallback)));
        sink.send(line, error);
        if (error != null) {
            // The stack belongs in the log file, with a level and the plugin's
            // name on it — printStackTrace wrote it to raw stdout, where it
            // arrives as unowned noise. This was the last antique in here.
            plugin.getLogger().log(java.util.logging.Level.WARNING, message, error);
        }
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
