package net.exylia.lib.redis;

import net.exylia.lib.redis.internal.RedisRuntime;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * The shared cache that makes one database look the same from every server.
 *
 * <h2>There is nothing to call</h2>
 * This is the unusual module: a plugin does not use it. It turns itself on from
 * {@code database.yml} and every repository the plugin already has starts
 * answering from Redis instead of the database, and telling the other servers
 * when a row changes. Code written before this module existed gains all of it
 * without a line changing.
 *
 * <pre>{@code
 * # plugins/<Plugin>/database.yml
 * database:
 *   type: mysql
 *   mysql: { host: 10.0.0.5, database: network, username: exylia, password: '...' }
 *   redis:
 *     enabled: true
 *     host: 10.0.0.6
 *     server-id: lobby-1     # different on every server
 * }</pre>
 *
 * <p>That is the whole configuration. The class exists for the two questions
 * worth asking at runtime — is it on, and is it working — which belong in a
 * diagnostic command rather than in a plugin's logic.
 *
 * <h2>What it guarantees</h2>
 * A row written on one server is visible on every other, immediately, without
 * waiting for a message to travel: the write reaches Redis before it is
 * announced, and a server that does not have the row reads it from there rather
 * than from the database. That ordering is what makes a proxy moving a player
 * between servers inside one tick land on the state the previous server just
 * wrote — the case that otherwise looks like settings resetting on a server
 * switch.
 *
 * <h2>What it does not do</h2>
 * It is a cache, not storage. The database remains the truth, every write still
 * completes against it before anything is cached, and losing Redis entirely
 * costs performance and cross-server freshness — never data. Filtered queries
 * are not cached at all: a leaderboard changes whenever anyone's score does,
 * and no key predicts that.
 *
 * @since 1.31.0
 */
public final class Redis {

    private Redis() {
        throw new AssertionError("No instances.");
    }

    /**
     * Whether a shared cache is connected and serving reads.
     *
     * <p>False on a server that did not configure Redis, and also on one that
     * did but could not reach it — in both cases everything still works, so
     * this is a diagnostic, not a condition to branch on.
     *
     * @return whether the cache is running
     */
    public static boolean isActive() {
        return RedisRuntime.isActive();
    }

    /**
     * Hits, misses and failures of every open cache.
     *
     * <p>For a diagnostic command. A low hit rate on a network usually means
     * the servers disagree about {@code key-prefix}, or share a
     * {@code server-id} and are ignoring each other's changes.
     *
     * @return one line per open cache, or a note that none is running
     */
    public static @NotNull String stats() {
        return RedisRuntime.stats();
    }

    /**
     * This server's name on the network, as configured in the plugin's
     * {@code database.yml}.
     *
     * <p>{@code server-1} when Redis is off or the block is absent, so the name
     * is always usable: it is what {@link Message#sender()} carries.
     *
     * @param plugin the plugin whose configuration is read
     * @return the configured {@code server-id}
     * @since 1.75.0
     */
    public static @NotNull String serverId(@NotNull Plugin plugin) {
        return Channels.settings(plugin).serverId();
    }
}
