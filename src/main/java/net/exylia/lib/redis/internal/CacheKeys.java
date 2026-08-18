package net.exylia.lib.redis.internal;

import net.exylia.lib.database.internal.EntityModel;
import org.jetbrains.annotations.NotNull;

/**
 * How a row is named in a keyspace every server and every plugin shares.
 *
 * <p>The shape is {@code <prefix>:row:<table>:<id>}, which is ExyliaCommons'
 * {@code <prefix>:cache:<namespace>:<key>} with the segment renamed to say what
 * it holds. Two decisions in it are load-bearing:
 *
 * <ul>
 *   <li><b>The table name, never the class name.</b> Half the plugins in the
 *       ecosystem declare a record called {@code PlayerData}; keying by simple
 *       name would have them read each other's rows out of a shared Redis.
 *       {@code @Table} is already the thing that has to be unique for the same
 *       reason in SQL.</li>
 *   <li><b>The stored id, never the record's own.</b> A {@code UUID} key
 *       encodes to text on the way to a column, and a key built from
 *       {@code toString()} on one side and the codec on the other matches
 *       nothing while looking correct.</li>
 * </ul>
 */
final class CacheKeys {

    private CacheKeys() {
        throw new AssertionError("No instances.");
    }

    /**
     * The key holding one row.
     *
     * @param prefix the operator's key prefix, isolating one network from another
     * @param model  the compiled model, which names the table
     * @param id     the key in stored form
     * @return the full Redis key
     */
    static @NotNull String row(@NotNull String prefix, @NotNull EntityModel<?> model, @NotNull Object id) {
        return prefix + ":row:" + model.table() + ':' + id;
    }

    /**
     * The prefix every key of one table shares.
     *
     * <p>What a table-wide drop scans for, and what a peer matches an incoming
     * invalidation against.
     *
     * @param prefix the operator's key prefix
     * @param table  the table name
     * @return the prefix, ending in a colon
     */
    static @NotNull String table(@NotNull String prefix, @NotNull String table) {
        return prefix + ":row:" + table + ':';
    }

    /**
     * The channel invalidations travel on.
     *
     * <p>One channel for the whole network rather than one per table: a
     * subscriber costs a connection and a thread, and routing by table happens
     * on the message, which is a string compare. ExyliaCommons made the same
     * call and it is the right one — the alternative is a connection per record
     * type per plugin.
     *
     * @param prefix the operator's key prefix
     * @return the channel name
     */
    static @NotNull String channel(@NotNull String prefix) {
        return prefix + ":invalidate";
    }
}
