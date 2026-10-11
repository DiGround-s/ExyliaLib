package net.exylia.lib.redis;

import net.exylia.lib.redis.internal.RedisClient;
import net.exylia.lib.redis.internal.RedisRuntime;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Plain keys and streams on the Redis a plugin already configured.
 *
 * <p>For data other programs read by name — a proxy plugin checking a key at
 * login, a producer on another platform appending to a stream — where a
 * repository's cache keys or a {@link Channel} do not fit. Rows still belong in
 * a repository; this is the shared wire, not storage.
 *
 * <pre>{@code
 * RedisKeys keys = RedisKeys.of(this);
 * tasks.runAsync(() -> {
 *     if (keys.available()) {
 *         keys.set(keys.prefix() + ":punish:ban:" + uuid, json, expiresAt);
 *     }
 * });
 * }</pre>
 *
 * <p><b>Threading:</b> every call is a network round trip and blocks, so it
 * belongs on an async thread. Each throws {@link IllegalStateException} when
 * Redis is off or unreachable; ask {@link #available()} first. Keys are full
 * keys: build them from {@link #prefix()} so every server of a network agrees.
 *
 * @since 1.271.0
 */
public final class RedisKeys {

    private final Plugin plugin;
    private final RedisSettings settings;

    private RedisKeys(Plugin plugin, RedisSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    /**
     * The keys of a plugin's configured Redis.
     *
     * @param plugin the plugin whose {@code database.yml} decides the Redis
     * @return a handle; cheap, keep or recreate it as convenient
     */
    public static @NotNull RedisKeys of(@NotNull Plugin plugin) {
        return new RedisKeys(plugin, Channels.settings(plugin));
    }

    /** The network's {@code key-prefix}, {@code exylia} by default. */
    public @NotNull String prefix() {
        return settings.keyPrefix();
    }

    /** Whether Redis is configured and connected; from the server thread a first call may still be opening it. */
    public boolean available() {
        return client() != null;
    }

    /** The value, or {@code null} when absent or expired. */
    public @Nullable String get(@NotNull String key) {
        return require().get(key);
    }

    /**
     * Writes a value.
     *
     * @param expireAtMillis epoch millis it expires at, {@code 0} to keep it until deleted
     */
    public void set(@NotNull String key, @NotNull String value, long expireAtMillis) {
        require().setUntil(key, value, expireAtMillis);
    }

    /** Writes a value only when the key is absent: a short lock across servers. */
    public boolean setIfAbsent(@NotNull String key, @NotNull String value, long ttlMillis) {
        return require().setIfAbsent(key, value, ttlMillis);
    }

    public void delete(@NotNull String... keys) {
        require().delete(Arrays.asList(keys));
    }

    /** {@code XADD stream MAXLEN ~ maxLength * fields}; answers the entry id. */
    public @NotNull String streamAdd(@NotNull String stream, @NotNull Map<String, String> fields, long maxLength) {
        return require().streamAdd(stream, fields, maxLength);
    }

    /** {@code XGROUP CREATE stream group 0 MKSTREAM}; an existing group is fine. */
    public void streamGroup(@NotNull String stream, @NotNull String group) {
        require().streamGroup(stream, group);
    }

    /** {@code XREADGROUP ... COUNT count STREAMS stream >}, without blocking. */
    public @NotNull List<StreamEntry> streamRead(@NotNull String stream, @NotNull String group,
                                                 @NotNull String consumer, int count) {
        return require().streamRead(stream, group, consumer, count).stream()
                .map(entry -> new StreamEntry(entry.getKey(), entry.getValue())).toList();
    }

    /** {@code XPENDING stream group IDLE minIdle - + count}. */
    public @NotNull List<StreamPending> streamPending(@NotNull String stream, @NotNull String group,
                                                     long minIdleMillis, int count) {
        return require().streamPending(stream, group, minIdleMillis, count).stream()
                .map(entry -> new StreamPending(entry.getKey(), entry.getValue())).toList();
    }

    /** {@code XCLAIM}: takes pending entries over; each claim counts as a delivery. */
    public @NotNull List<StreamEntry> streamClaim(@NotNull String stream, @NotNull String group,
                                                  @NotNull String consumer, long minIdleMillis,
                                                  @NotNull Collection<String> ids) {
        return require().streamClaim(stream, group, consumer, minIdleMillis, ids).stream()
                .map(entry -> new StreamEntry(entry.getKey(), entry.getValue())).toList();
    }

    /** {@code XACK}. */
    public void streamAck(@NotNull String stream, @NotNull String group, @NotNull Collection<String> ids) {
        require().streamAck(stream, group, ids);
    }

    /** One stream entry. */
    public record StreamEntry(@NotNull String id, @NotNull Map<String, String> fields) {
    }

    /** One pending entry and how many times it was delivered. */
    public record StreamPending(@NotNull String id, long deliveries) {
    }

    private @Nullable RedisClient client() {
        try {
            return RedisRuntime.client(plugin, settings);
        } catch (RuntimeException | LinkageError absent) {
            return null;
        }
    }

    private RedisClient require() {
        RedisClient client = client();
        if (client == null) {
            throw new IllegalStateException("Redis is not connected for " + plugin.getName());
        }
        return client;
    }
}
