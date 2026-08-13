package net.exylia.lib.text;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The prefix a plugin puts in front of its messages.
 *
 * <p>Nearly every message a plugin sends starts with the same tag, and it is
 * always the same question: where does it live? Written into each line, changing
 * it means editing every message in the file. Registered as a placeholder, two
 * plugins fight over the one name {@code %prefix%}.
 *
 * <p>So a prefix belongs to a plugin, not to the server:
 *
 * <pre>{@code
 * Prefixes.set(this, messages.prefix());
 * Text.from(this, messages.warmup().timer()).send(player);   // %prefix% resolves
 * }</pre>
 *
 * <p>Two plugins can both use {@code %prefix%} in their own files and each gets
 * its own, because the text knows which plugin it came from. Text that does not
 * name a plugin leaves {@code %prefix%} alone rather than guessing.
 *
 * <p>The prefix is set from config on enable and on reload, like any other
 * message; nothing here reads a file.
 *
 * @since 1.18.0
 */
public final class Prefixes {

    /** Keyed by plugin name, matching how the placeholder registry tracks owners. */
    private static final Map<String, String> PREFIXES = new ConcurrentHashMap<>();

    private Prefixes() {
        throw new AssertionError("No instances.");
    }

    /**
     * Sets the prefix for a plugin's messages.
     *
     * <p>Call again after a reload to pick up an edited value.
     *
     * @param plugin the plugin the prefix belongs to
     * @param prefix the prefix, in any notation {@link Text} understands
     */
    public static void set(@NotNull Plugin plugin, @NotNull String prefix) {
        PREFIXES.put(plugin.getName(), prefix);
    }

    /**
     * Returns a plugin's prefix.
     *
     * @param plugin the plugin
     * @return the prefix, or {@code null} when the plugin never set one
     */
    public static @Nullable String get(@NotNull Plugin plugin) {
        return PREFIXES.get(plugin.getName());
    }

    /**
     * Returns a plugin's prefix by name.
     *
     * @param pluginName the plugin's name
     * @return the prefix, or {@code null} when there is none
     */
    public static @Nullable String get(@NotNull String pluginName) {
        return PREFIXES.get(pluginName);
    }

    /** Forgets a plugin's prefix, called when it is disabled. */
    public static void release(@NotNull String pluginName) {
        PREFIXES.remove(pluginName);
    }

    /** Forgets every prefix. Used on shutdown and by tests. */
    public static void releaseAll() {
        PREFIXES.clear();
    }
}
