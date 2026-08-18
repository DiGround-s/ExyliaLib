package net.exylia.lib.redis;

import net.exylia.lib.config.Comment;
import org.jetbrains.annotations.NotNull;

/**
 * How a server reaches Redis, and how long it trusts what it finds there.
 *
 * <p>Lives under {@code database.redis} in a consumer's {@code database.yml},
 * with the key names ExyliaCommons already wrote, so a server that had Redis
 * working before keeps its file. What was dropped from that block is what this
 * library does not implement rather than what it does differently.
 *
 * <h2>Off unless asked for</h2>
 * A single server has nothing to gain here: it already has the row in memory,
 * and adding a network round trip in front of that is slower, not faster. This
 * is for a network of servers sharing one database, which is why {@code enabled}
 * defaults to false and why turning it on is a decision an operator makes once.
 *
 * @param enabled      whether to use Redis at all
 * @param host         the Redis host
 * @param port         the port
 * @param password     the password, empty when the server needs none
 * @param database     which numbered Redis database to use
 * @param poolSize     connections at most
 * @param ttlSeconds   how long a row stays in Redis
 * @param localSeconds how long a row stays in this process's memory
 * @param localEntries how many rows this process keeps at most
 * @param keyPrefix    what every key of this network starts with
 * @param serverId     this server's name, used to ignore its own invalidations
 * @since 1.31.0
 */
@Comment("Shared cache and cross-server invalidation.")
@Comment("")
@Comment("Off by default, and a single server should leave it that way: a lone")
@Comment("server already has the row in memory, so this would only add a network")
@Comment("round trip in front of it.")
@Comment("")
@Comment("Turn it on for a network of servers sharing one database. A change made")
@Comment("on one server is then visible on the others immediately, including for")
@Comment("a player the proxy moves between them in the same tick.")
@Comment("")
@Comment("Redis is never load-bearing: if it stops answering, everything keeps")
@Comment("working straight against the database and the console says so once.")
public record RedisSettings(

        @Comment("Whether to use Redis. Leave false on a single server.")
        boolean enabled,

        @Comment("Host of the Redis server.")
        String host,

        @Comment("Port of the Redis server.")
        int port,

        @Comment("Password, if the server needs one.")
        String password,

        @Comment("Which numbered Redis database to use. 0 unless you share the")
        @Comment("server with something else that already uses it.")
        int database,

        @Comment("Connections kept at most. The default suits any number of")
        @Comment("plugins on one server: they share this pool.")
        int poolSize,

        @Comment("How long a row stays in Redis, in seconds.")
        @Comment("This is a cache, not storage: the database is still the truth,")
        @Comment("and an entry expiring only costs one query to rebuild.")
        int ttlSeconds,

        @Comment("How long a row stays in this server's own memory, in seconds.")
        @Comment("Shorter than the Redis one on purpose. This is the backstop for")
        @Comment("the one invalidation that never arrived, so a shorter value")
        @Comment("bounds how long a server can be wrong; it does not add queries,")
        @Comment("because a local miss is answered by Redis, not by the database.")
        int localSeconds,

        @Comment("Rows this server keeps in memory at most.")
        @Comment("Reached only on a very large network; past it the least useful")
        @Comment("rows are dropped and re-read from Redis when needed.")
        int localEntries,

        @Comment("What every key of this network starts with.")
        @Comment("Change it only if two separate networks share one Redis server,")
        @Comment("in which case each needs its own value or they will read each")
        @Comment("other's data.")
        String keyPrefix,

        @Comment("This server's name on the network.")
        @Comment("Used to ignore its own messages, and to say which server a")
        @Comment("change came from. Give every server a different one: two")
        @Comment("servers sharing a name ignore each other's changes.")
        String serverId
) {

    /** The defaults: off, and pointed at a local Redis if it is ever turned on. */
    public RedisSettings() {
        this(false, "localhost", 6379, "", 0, 8, 1800, 300, 10_000, "exylia", "server-1");
    }

    /** The key prefix, never empty: an empty one would put keys at the root. */
    public @NotNull String keyPrefix() {
        return keyPrefix == null || keyPrefix.isBlank() ? "exylia" : keyPrefix.trim();
    }

    /** This server's name, never empty. */
    public @NotNull String serverId() {
        return serverId == null || serverId.isBlank() ? "server-1" : serverId.trim();
    }

    /** The host, never empty. */
    public @NotNull String host() {
        return host == null || host.isBlank() ? "localhost" : host.trim();
    }

    /** The password, or an empty string when the server needs none. */
    public @NotNull String password() {
        return password == null ? "" : password;
    }

    /** The port, or Redis's own default when none was set. */
    public int port() {
        return port > 0 ? port : 6379;
    }

    /** Connections at most, floored at one. */
    public int poolSize() {
        return poolSize > 0 ? poolSize : 8;
    }

    /** How long a row stays in Redis, floored at a second. */
    public int ttlSeconds() {
        return ttlSeconds > 0 ? ttlSeconds : 1800;
    }

    /** How long a row stays in memory, floored at a second. */
    public int localSeconds() {
        return localSeconds > 0 ? localSeconds : 300;
    }

    /** Rows kept in memory at most, floored at one. */
    public int localEntries() {
        return localEntries > 0 ? localEntries : 10_000;
    }
}
