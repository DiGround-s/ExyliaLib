package net.exylia.lib.input;

import net.exylia.lib.input.internal.InputRuntime;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Entry point for questions and forms shown to players.
 *
 * <pre>{@code
 * PluginInputs inputs = Inputs.of(plugin);
 * inputs.text(player, "Name the arena")
 *       .maxLength(32)
 *       .validate(value -> !value.isBlank(), "Cannot be empty")
 *       .open()
 *       .thenAccept(result -> result.ifCompleted(this::createArena));
 * }</pre>
 *
 * <p>The view is cached by exact Bukkit plugin name so every request has one
 * owner and disable-time cleanup cannot retain a consumer's classloader.
 *
 * @since 1.31.0
 */
public final class Inputs {

    private static final ConcurrentMap<String, PluginInputs> BY_PLUGIN = new ConcurrentHashMap<>();
    private static volatile Duration defaultTimeout = Duration.ofSeconds(60);

    private Inputs() {
        throw new AssertionError("No instances.");
    }

    /**
     * Returns the request factory owned by a plugin.
     *
     * @param plugin the plugin that owns callbacks and pending requests
     * @return its cached input view
     * @throws InputException when the plugin or its name is invalid
     */
    public static @NotNull PluginInputs of(@NotNull Plugin plugin) {
        if (plugin == null) {
            throw new InputException("plugin must not be null");
        }
        String name = plugin.getName();
        if (name == null || name.isBlank()) {
            throw new InputException("plugin needs a non-blank name");
        }
        return BY_PLUGIN.computeIfAbsent(name, ignored -> new PluginInputs(plugin));
    }

    /** Ends the active request for a player as an explicit cancellation. */
    public static void cancel(@NotNull Player player) {
        InputRuntime.cancel(requirePlayer(player).getUniqueId());
    }

    /** Returns whether a player currently has a request from any plugin. */
    public static boolean hasActive(@NotNull Player player) {
        return InputRuntime.hasActive(requirePlayer(player).getUniqueId());
    }

    /**
     * Releases one plugin's cached view and pending requests.
     *
     * <p>Lifecycle code calls this on disable so futures and callbacks cannot
     * retain the disabled plugin's classloader.
     */
    public static void release(@NotNull String pluginName) {
        if (pluginName == null || pluginName.isBlank()) {
            throw new InputException("pluginName must not be blank");
        }
        BY_PLUGIN.remove(pluginName);
        InputRuntime.releasePlugin(pluginName);
    }

    /** Releases every cached view and ends every active request. */
    public static void releaseAll() {
        BY_PLUGIN.clear();
        InputRuntime.shutdown();
    }

    /** Returns the timeout copied into newly created builders. */
    @ApiStatus.Internal
    public static @NotNull Duration defaultTimeout() {
        return defaultTimeout;
    }

    /**
     * Changes the timeout copied into future builders.
     *
     * <p>This is a settings bridge, not per-request configuration. Existing
     * builders retain their timeout so a reload cannot move a live deadline.
     */
    @ApiStatus.Internal
    public static void defaultTimeout(@NotNull Duration timeout) {
        defaultTimeout = requirePositive(timeout, "default timeout");
    }

    static @NotNull Duration requirePositive(Duration duration, String name) {
        if (duration == null) {
            throw new InputException(name + " must not be null");
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new InputException(name + " must be positive");
        }
        return duration;
    }

    static <T> @NotNull T require(@NotNull T value, String name) {
        if (value == null) {
            throw new InputException(name + " must not be null");
        }
        return value;
    }

    static @NotNull String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new InputException(name + " must not be blank");
        }
        return value;
    }

    private static Player requirePlayer(Player player) {
        return require(player, "player");
    }
}
