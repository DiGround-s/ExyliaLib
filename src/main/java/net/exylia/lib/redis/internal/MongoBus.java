package net.exylia.lib.redis.internal;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import net.exylia.lib.database.internal.MongoBackend;
import net.exylia.lib.database.internal.SqlSettings;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The database bus on MongoDB, standalone or replica set.
 *
 * <p>Numbers come from a counter document ({@code $inc}), so messages and
 * stream entries are ordered by the database rather than by anyone's clock.
 *
 * <h2>Streams without transactions</h2>
 * A standalone Mongo has no transactions, so a group read cannot lock out an
 * append the way SQL does. Instead a group only reads up to the counter value
 * this server saw {@link DatabaseBus#LOOKBACK_MILLIS} ago: every number handed
 * out by then has long been stored. The group's position moves by
 * compare-and-set, so two servers reading one group never get the same entry.
 * The cost is that an entry reaches its consumer about two seconds after it
 * was appended, which suits what streams are for here: feeds polled every
 * second or so.
 */
final class MongoBus extends DatabaseBus {

    private static final String COUNTERS = "exylia_bus_counters";
    private static final String MESSAGE_SEQ = "messages";
    private static final String ENTRY_SEQ = "entries";

    private final MongoBackend backend;
    private final MongoCollection<Document> counters;
    private final MongoCollection<Document> messages;
    private final MongoCollection<Document> keys;
    private final MongoCollection<Document> entries;
    private final MongoCollection<Document> groups;
    private final MongoCollection<Document> pending;

    /** Entry counter values seen, with when: the settle window of group reads. */
    private final ArrayDeque<long[]> entryMarks = new ArrayDeque<>();

    private MongoBus(MongoBackend backend, String name) {
        super(name);
        this.backend = backend;
        MongoDatabase database = backend.database();
        this.counters = database.getCollection(COUNTERS);
        this.messages = database.getCollection(SqlBus.MESSAGES);
        this.keys = database.getCollection(SqlBus.KEYS);
        this.entries = database.getCollection(SqlBus.ENTRIES);
        this.groups = database.getCollection(SqlBus.GROUPS);
        this.pending = database.getCollection(SqlBus.PENDING);
    }

    static @NotNull MongoBus connect(@NotNull SqlSettings settings, @NotNull String name) {
        MongoBackend backend = MongoBackend.open(settings, name + "-bus");
        MongoBus bus = new MongoBus(backend, name);
        try {
            bus.messages.createIndex(Indexes.ascending("created"));
            bus.keys.createIndex(Indexes.ascending("expires"));
            bus.entries.createIndex(Indexes.ascending("stream", "_id"));
            bus.pending.createIndex(Indexes.ascending("s", "g", "delivered"));
        } catch (RuntimeException failure) {
            backend.close();
            throw new IllegalStateException("Could not prepare the cross-server collections: "
                    + failure.getMessage(), failure);
        }
        return bus;
    }

    private long next(String counter) {
        Document updated = counters.findOneAndUpdate(Filters.eq("_id", counter), Updates.inc("seq", 1L),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        return number(updated, "seq");
    }

    private long current(String counter) {
        Document found = counters.find(Filters.eq("_id", counter)).first();
        return found == null ? 0L : number(found, "seq");
    }

    private static long number(@Nullable Document document, String field) {
        Object value = document == null ? null : document.get(field);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    // ----------------------------------------------------------------- messages

    @Override
    protected long newestId() {
        return current(MESSAGE_SEQ);
    }

    @Override
    protected @NotNull List<Envelope> readAfter(long after, int limit) {
        List<Envelope> found = new ArrayList<>();
        for (Document document : messages.find(Filters.gt("_id", after)).sort(Sorts.ascending("_id")).limit(limit)) {
            found.add(new Envelope(number(document, "_id"), document.getString("channel"),
                    document.getString("payload")));
        }
        return found;
    }

    @Override
    protected void insert(@NotNull String channel, @NotNull String message, long now) {
        messages.insertOne(new Document("_id", next(MESSAGE_SEQ)).append("channel", channel)
                .append("payload", message).append("created", now));
    }

    @Override
    protected void purge(long before, long now) {
        messages.deleteMany(Filters.lt("created", before));
        keys.deleteMany(Filters.and(Filters.gt("expires", 0L), Filters.lte("expires", now)));
    }

    @Override
    protected void closeStore() {
        backend.close();
    }

    // --------------------------------------------------------------------- keys

    @Override
    public @Nullable String get(@NotNull String key) {
        Document found = keys.find(Filters.eq("_id", key)).first();
        if (found == null) {
            return null;
        }
        long expires = number(found, "expires");
        return expires > 0 && expires <= System.currentTimeMillis() ? null : found.getString("v");
    }

    @Override
    public void set(@NotNull String key, @NotNull String value, int ttlSeconds) {
        setUntil(key, value, System.currentTimeMillis() + Math.max(1, ttlSeconds) * 1_000L);
    }

    @Override
    public void setUntil(@NotNull String key, @NotNull String value, long expireAtMillis) {
        keys.replaceOne(Filters.eq("_id", key),
                new Document("_id", key).append("v", value).append("expires", Math.max(0, expireAtMillis)),
                new ReplaceOptions().upsert(true));
    }

    @Override
    public boolean setIfAbsent(@NotNull String key, @NotNull String value, long ttlMillis) {
        long now = System.currentTimeMillis();
        keys.deleteOne(Filters.and(Filters.eq("_id", key), Filters.gt("expires", 0L), Filters.lte("expires", now)));
        try {
            keys.insertOne(new Document("_id", key).append("v", value).append("expires", now + Math.max(1, ttlMillis)));
            return true;
        } catch (MongoWriteException taken) {
            if (taken.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                return false;
            }
            throw taken;
        }
    }

    @Override
    public void delete(@NotNull Collection<String> keys) {
        if (!keys.isEmpty()) {
            this.keys.deleteMany(Filters.in("_id", keys));
        }
    }

    @Override
    public void deleteByPrefix(@NotNull String prefix) {
        keys.deleteMany(Filters.regex("_id", "^" + Pattern.quote(prefix)));
    }

    // ------------------------------------------------------------------ streams

    @Override
    public @NotNull String streamAdd(@NotNull String stream, @NotNull Map<String, String> fields, long maxLength) {
        long id = next(ENTRY_SEQ);
        entries.insertOne(new Document("_id", id).append("stream", stream).append("fields", new Document(
                new LinkedHashMap<>(fields))));
        if (maxLength > 0) {
            entries.deleteMany(Filters.and(Filters.eq("stream", stream), Filters.lte("_id", id - maxLength)));
        }
        return Long.toString(id);
    }

    @Override
    public void streamGroup(@NotNull String stream, @NotNull String group) {
        try {
            groups.insertOne(new Document("_id", groupId(stream, group)).append("last", 0L));
        } catch (MongoWriteException exists) {
            if (exists.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                throw exists;
            }
        }
    }

    @Override
    public @NotNull List<Map.Entry<String, Map<String, String>>> streamRead(@NotNull String stream,
                                                                          @NotNull String group,
                                                                          @NotNull String consumer, int count) {
        long settled = settled();
        String id = groupId(stream, group);
        for (int attempt = 0; attempt < 5; attempt++) {
            Document position = groups.find(Filters.eq("_id", id)).first();
            if (position == null) {
                throw new IllegalStateException("NOGROUP No such consumer group '" + group
                        + "' for stream '" + stream + "'");
            }
            long last = number(position, "last");
            List<Document> batch = new ArrayList<>();
            entries.find(Filters.and(Filters.eq("stream", stream), Filters.gt("_id", last),
                            Filters.lte("_id", settled)))
                    .sort(Sorts.ascending("_id")).limit(Math.max(1, count)).into(batch);
            if (batch.isEmpty()) {
                return List.of();
            }
            long newest = number(batch.get(batch.size() - 1), "_id");
            // Compare-and-set: only the reader that still saw `last` takes these.
            if (groups.updateOne(Filters.and(Filters.eq("_id", id), Filters.eq("last", last)),
                    Updates.set("last", newest)).getModifiedCount() == 0) {
                continue;
            }
            long now = System.currentTimeMillis();
            List<Document> owed = new ArrayList<>();
            List<Map.Entry<String, Map<String, String>>> read = new ArrayList<>();
            for (Document entry : batch) {
                long entryId = number(entry, "_id");
                owed.add(new Document("_id", pendingId(stream, group, entryId)).append("s", stream)
                        .append("g", group).append("i", entryId).append("consumer", consumer)
                        .append("delivered", now).append("deliveries", 1L));
                read.add(pair(entryId, entry));
            }
            pending.insertMany(owed);
            return read;
        }
        // Lost the race five times running: others are reading this group
        // right now, and the next poll gets its turn.
        return List.of();
    }

    /** The entry counter as it stood a lookback ago: every number up to it is stored. */
    private synchronized long settled() {
        long now = System.currentTimeMillis();
        entryMarks.addLast(new long[]{now, current(ENTRY_SEQ)});
        long cutoff = now - LOOKBACK_MILLIS;
        while (entryMarks.size() >= 2) {
            Iterator<long[]> iterator = entryMarks.iterator();
            iterator.next();
            if (iterator.next()[0] > cutoff) {
                break;
            }
            entryMarks.removeFirst();
        }
        long[] oldest = entryMarks.peekFirst();
        return oldest[0] <= cutoff ? oldest[1] : 0L;
    }

    @Override
    public @NotNull List<Map.Entry<String, Long>> streamPending(@NotNull String stream, @NotNull String group,
                                                                long minIdleMillis, int count) {
        List<Map.Entry<String, Long>> found = new ArrayList<>();
        for (Document document : pending.find(Filters.and(Filters.eq("s", stream), Filters.eq("g", group),
                        Filters.lte("delivered", System.currentTimeMillis() - Math.max(0, minIdleMillis))))
                .sort(Sorts.ascending("i")).limit(Math.max(1, count))) {
            found.add(new AbstractMap.SimpleImmutableEntry<>(Long.toString(number(document, "i")),
                    number(document, "deliveries")));
        }
        return found;
    }

    @Override
    public @NotNull List<Map.Entry<String, Map<String, String>>> streamClaim(@NotNull String stream,
                                                                           @NotNull String group,
                                                                           @NotNull String consumer,
                                                                           long minIdleMillis,
                                                                           @NotNull Collection<String> ids) {
        List<Map.Entry<String, Map<String, String>>> claimed = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String raw : ids) {
            long id = entryId(raw);
            String key = pendingId(stream, group, id);
            Bson idle = Filters.and(Filters.eq("_id", key), Filters.lte("delivered", now - Math.max(0, minIdleMillis)));
            if (pending.updateOne(idle, Updates.combine(Updates.set("consumer", consumer),
                    Updates.set("delivered", now), Updates.inc("deliveries", 1L))).getModifiedCount() == 0) {
                continue;
            }
            Document entry = entries.find(Filters.and(Filters.eq("_id", id), Filters.eq("stream", stream))).first();
            if (entry == null) {
                pending.deleteOne(Filters.eq("_id", key));
                continue;
            }
            claimed.add(pair(id, entry));
        }
        return claimed;
    }

    @Override
    public void streamAck(@NotNull String stream, @NotNull String group, @NotNull Collection<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>();
        for (String id : ids) {
            keys.add(pendingId(stream, group, entryId(id)));
        }
        pending.deleteMany(Filters.in("_id", keys));
    }

    private static String groupId(String stream, String group) {
        return stream + '\u0000' + group;
    }

    private static String pendingId(String stream, String group, long id) {
        return stream + '\u0000' + group + '\u0000' + id;
    }

    private static Map.Entry<String, Map<String, String>> pair(long id, Document entry) {
        Map<String, String> fields = new LinkedHashMap<>();
        Object stored = entry.get("fields");
        if (stored instanceof Document document) {
            document.forEach((name, value) -> fields.put(name, value == null ? "" : String.valueOf(value)));
        }
        return new AbstractMap.SimpleImmutableEntry<>(Long.toString(id), fields);
    }
}
