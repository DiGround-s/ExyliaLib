package net.exylia.lib.text;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>A prefix never styles the message after it: one that does not end in a
 * reset gets one, so an owner's {@code {primary}&lX »} cannot bold every line.
 * And exactly one space separates it from the message, whether the line is
 * written {@code %prefix% text} or {@code %prefix%text} and whether the prefix
 * ends in a space or not (since 1.471.0).
 *
 * @since 1.18.0
 */
public final class Prefixes {

    /** Keyed by plugin name, matching how the placeholder registry tracks owners. */
    private static final Map<String, String> PREFIXES = new ConcurrentHashMap<>();

    /** The token and the spaces written after it, which {@link #fill} replaces with one. */
    private static final Pattern TOKEN = Pattern.compile("%prefix% *");

    /** A reset at the very end: {@code <reset>}, {@code &r} or {@code §r}. */
    private static final Pattern ENDS_IN_RESET = Pattern.compile("(?i)(<reset>|[&§]r)$");

    private Prefixes() {
        throw new AssertionError("No instances.");
    }

    /**
     * Sets the prefix for a plugin's messages.
     *
     * <p>Call again after a reload to pick up an edited value. Trailing spaces
     * are dropped and a reset is added when the prefix does not end in one, so
     * its formatting stops where it does.
     *
     * @param plugin the plugin the prefix belongs to
     * @param prefix the prefix, in any notation {@link Text} understands
     */
    public static void set(@NotNull Plugin plugin, @NotNull String prefix) {
        PREFIXES.put(plugin.getName(), sealed(prefix));
    }

    /**
     * Puts a prefix in place of every {@code %prefix%} in a message.
     *
     * <p>The token and any spaces after it become the prefix and one space, so
     * {@code %prefix%text} and {@code %prefix% text} read the same. Nothing is
     * added before a line break or at the end, and an empty prefix takes its
     * space with it. The prefix is sealed as {@link #set} seals it, so code that
     * substitutes its own prefix gets the same guarantees.
     *
     * @param message the message
     * @param prefix  the prefix, or {@code null} to leave the token
     * @return the message with the prefix in place
     * @since 1.471.0
     */
    public static @NotNull String fill(@NotNull String message, @Nullable String prefix) {
        if (prefix == null || !message.contains("%prefix%")) {
            return message;
        }
        String sealed = sealed(prefix);
        return TOKEN.matcher(message).replaceAll(match -> Matcher.quoteReplacement(
                sealed.isEmpty() || match.end() == message.length()
                        || Character.isWhitespace(message.charAt(match.end()))
                        ? sealed : sealed + " "));
    }

    /** The prefix without trailing spaces, ending in a reset unless it is empty. */
    static String sealed(String prefix) {
        String trimmed = prefix.stripTrailing();
        return trimmed.isEmpty() || ENDS_IN_RESET.matcher(trimmed).find() ? trimmed : trimmed + "<reset>";
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
