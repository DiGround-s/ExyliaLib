package net.exylia.lib.redis;

import net.exylia.lib.database.internal.DatabaseRuntime;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.redis.internal.RedisClient;
import net.exylia.lib.redis.internal.RedisRuntime;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-server events over the Redis a plugin already configured.
 *
 * <p>For telling every server that something <em>happened</em> — a staff
 * alert, a broadcast, a punishment to apply — never for carrying state. State
 * lives in a repository, which the cache module already keeps consistent
 * across the network; a message can be missed by a server that was restarting,
 * a row cannot.
 *
 * <p>Nothing to configure beyond {@code database.redis} in {@code database.yml};
 * without it the channels still deliver within this server.
 *
 * <pre>{@code
 * Channel alerts = Channels.of(this).channel("alerts");
 * alerts.subscribe(message -> {
 *     if (!message.local()) {
 *         Tasks.of(this).run(() -> notifyStaff(message.payload()));
 *     }
 * });
 * alerts.publish(player.getName() + " was reported");
 * }</pre>
 *
 * @since 1.75.0
 */
public final class Channels {

    private static final Map<String, PluginChannels> VIEWS = new ConcurrentHashMap<>();

    private Channels() {
        throw new AssertionError("No instances.");
    }

    /**
     * The channels of a plugin, opening the Redis connection the first time.
     *
     * @param plugin the plugin whose {@code database.yml} decides the Redis
     * @return the plugin's channels, the same instance every time
     */
    public static @NotNull PluginChannels of(@NotNull Plugin plugin) {
        return VIEWS.compute(plugin.getName(), (name, existing) ->
                existing != null && existing.ownedBy(plugin) ? existing : open(plugin));
    }

    private static PluginChannels open(Plugin plugin) {
        RedisSettings settings = settings(plugin);
        return new PluginChannels(plugin, settings.keyPrefix(), settings.serverId(),
                client(plugin, settings));
    }

    /** Closes the channels of this load of a plugin, leaving a newer load alone. */
    public static void release(@NotNull Plugin plugin) {
        VIEWS.computeIfPresent(plugin.getName(), (name, view) -> {
            if (!view.ownedBy(plugin)) {
                return view;
            }
            view.close();
            return null;
        });
    }

    /** Closes every plugin's channels; ExyliaLib calls this on shutdown. */
    public static void releaseAll() {
        VIEWS.values().forEach(PluginChannels::close);
        VIEWS.clear();
    }

    /** The plugin's Redis block; disabled for a plugin that never used the database module. */
    static @NotNull RedisSettings settings(@NotNull Plugin plugin) {
        try {
            return DatabaseRuntime.redis(plugin);
        } catch (RuntimeException | LinkageError unavailable) {
            return new RedisSettings();
        }
    }

    private static @Nullable RedisClient client(Plugin plugin, RedisSettings settings) {
        try {
            return RedisRuntime.client(plugin, settings);
        } catch (RuntimeException | LinkageError absent) {
            Debug.of(plugin).warn("Redis is configured but its library is not installed,"
                    + " so channels only deliver within this server: " + absent.getMessage());
            return null;
        }
    }
}
