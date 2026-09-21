package net.exylia.lib.redis;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.redis.internal.RedisClient;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The channels one plugin owns.
 *
 * <p>Channel names are scoped to the plugin: two plugins asking for
 * {@code "alerts"} get two separate channels. Every channel is closed with the
 * plugin, so nothing here needs releasing by hand.
 *
 * @since 1.75.0
 */
public final class PluginChannels {

    private final Plugin plugin;
    private final String prefix;
    private final String serverId;
    private final Supplier<@Nullable RedisClient> clients;
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    PluginChannels(@NotNull Plugin plugin, @NotNull String keyPrefix, @NotNull String serverId,
                   @NotNull Supplier<@Nullable RedisClient> clients) {
        this.plugin = plugin;
        this.prefix = keyPrefix + ":ch:" + plugin.getName().toLowerCase(Locale.ROOT) + ':';
        this.serverId = serverId;
        this.clients = clients;
    }

    /**
     * The channel with this name, the same instance every time.
     *
     * @param name the channel name, any non-empty string
     * @return the channel
     */
    public @NotNull Channel channel(@NotNull String name) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("A channel needs a name.");
        }
        return channels.computeIfAbsent(name, key ->
                new Channel(key, prefix + key, serverId, clients, Debug.of(plugin)));
    }

    /** Subscribes the channels whose Redis was not reachable before. */
    void reconnect() {
        channels.values().forEach(Channel::reconnect);
    }

    /** Closes every channel; done for you when the plugin disables. */
    public void close() {
        channels.values().forEach(Channel::close);
        channels.clear();
    }

    boolean ownedBy(Plugin candidate) {
        return plugin == candidate;
    }
}
