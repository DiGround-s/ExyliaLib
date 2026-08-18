package net.exylia.lib.database.internal;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * One Mongo client and everything that runs against it.
 *
 * <p>The Mongo counterpart of {@link SqlBackend}: same operations, same
 * arguments, same compiled {@link EntityModel} on both sides of the wire. The
 * differences a consumer can actually observe are listed under
 * <em>Not quite SQL</em> below, and each one is a place where pretending to be
 * SQL would have been a lie rather than a convenience.
 *
 * <pre>{@code
 * MongoBackend backend = MongoBackend.open(
 *         SqlSettings.remote("mongo", "10.0.0.5", 27017, "practice", "user", "secret"), "practice");
 * EntityModel<PlayerStats> model = EntityModel.of(PlayerStats.class);
 * backend.prepare(model);
 *
 * backend.save(model, stats);                        // upsert, one round trip
 * PlayerStats loaded = backend.find(model, uuid);    // by _id
 * List<PlayerStats> top = backend.select(model, List.of(), List.of(),
 *         List.of(Dialect.Sort.desc("elo")), 10, 0); // leaderboard page
 * }</pre>
 *
 * <h2>The driver stops here</h2>
 * This is the only class in ExyliaLib that names {@code com.mongodb} or
 * {@code org.bson}, exactly as {@code ApolloLink} is the only one that names
 * Apollo. The driver is {@code compileOnly} and arrives through the server's
 * library loader, so a server that never configures Mongo never loads a class
 * from it — which it would the moment a type from it appeared in a signature
 * anywhere on a path that runs. Everything that could be decided without the
 * driver was, and lives in {@link MongoDocuments}, where it is unit-testable
 * without a {@code mongod}.
 *
 * <h2>Native BSON, not text</h2>
 * An {@code int} is written as an int32, a {@code long} as an int64, a
 * {@code double} as a double, a {@code boolean} as a boolean. Storing
 * everything as text would round-trip perfectly and would also make
 * {@code $sort} on {@code elo} lexicographic and every numeric index useless
 * for a range query. See {@link MongoDocuments#bsonValue}.
 *
 * <h2>Not quite SQL</h2>
 * <ul>
 *   <li>{@link #prepare} never reports added columns. A document carries only
 *       the fields it was written with, so a record that gains a component
 *       needs no migration: old documents simply lack the field and read back
 *       as absent.</li>
 *   <li>{@link #deleteWhere} with a limit is two round trips, not one, and is
 *       not atomic. Mongo has no {@code DELETE ... LIMIT}.</li>
 *   <li>{@link #select} with a large offset is slow, and gets slower the deeper
 *       the page. {@code skip} walks the documents it discards.</li>
 *   <li>{@link #saveAll} is unordered: a document the server rejects does not
 *       stop the others, and there is no transaction to roll back. A SQL batch
 *       is all-or-nothing.</li>
 *   <li>A filter on a field no document has returns nothing rather than
 *       failing, which is why every name is resolved against the model before
 *       it reaches the server.</li>
 * </ul>
 *
 * <h2>Threads</h2>
 * Every method blocks on I/O and must be called from a background thread —
 * {@code Tasks.runAsync}, never the main one. This class creates no threads of
 * its own and holds no executor; it runs on whichever thread calls it, and the
 * driver's own connection pool is the only concurrency it owns.
 * {@link MongoClient} is documented as thread-safe and one is shared by every
 * caller: a client per operation would open and tear down a connection pool and
 * a monitoring thread each time.
 *
 * @see MongoDocuments
 * @since 1.24.0
 */
public final class MongoBackend implements AutoCloseable {

    /**
     * How long a caller waits for a usable server before giving up.
     *
     * <p>Five seconds, not the driver's default thirty, and for the same reason
     * {@link SqlBackend} shortens Hikari's: a background worker blocked for
     * thirty seconds against a database that is gone is a backlog nobody
     * recovers from on a server that queues a write per join. Five is long
     * enough to ride out an election and short enough to surface while somebody
     * is still watching the console.
     */
    private static final long SERVER_SELECTION_TIMEOUT_MILLIS = 5_000L;

    /** The default port, used when the settings name none. */
    private static final int DEFAULT_PORT = 27017;

    /**
     * The server's error codes for an index that exists with different options.
     *
     * <p>{@code createIndex} is idempotent for an identical specification and
     * fails for a differing one, which is the behaviour {@link #prepare} has to
     * work around rather than around which it can simply retry.
     */
    private static final int INDEX_OPTIONS_CONFLICT = 85;
    private static final int INDEX_KEY_SPECS_CONFLICT = 86;

    /**
     * Where the generated-key counters live, one document per table.
     *
     * <p>Named after the pattern rather than after any plugin: every table in
     * one database shares this collection, keyed by table name, so a plugin
     * adding a table adds a document and not a collection.
     */
    private static final String COUNTERS = "exylia_sequences";
    private static final String COUNTER_FIELD = "value";

    private final MongoClient client;
    private final MongoDatabase database;
    private final String describe;

    private MongoBackend(MongoClient client, MongoDatabase database, String describe) {
        this.client = client;
        this.database = database;
        this.describe = describe;
    }

    /**
     * Opens a client.
     *
     * <p>One client, for the life of the backend. It owns a connection pool and
     * a background monitor per server it knows about, so opening one per
     * operation would pay for both on every read.
     *
     * <p>The connection is taken from {@code mongodb://} or
     * {@code mongodb+srv://} in {@link SqlSettings#host()} or in a
     * {@code uri} property when one is given, and built from host, port and
     * credentials otherwise. A URI is not merely a shortcut: a replica set, a
     * {@code +srv} record and a read preference cannot be expressed any other
     * way, and an operator with a managed cluster has one and nothing else.
     *
     * @param settings where and how to connect; {@link SqlSettings#database()}
     *                 is required, since Mongo has no default database and a
     *                 client without one can only be asked about the server
     * @param clientName a name for the client, usually the plugin's, surfacing
     *                   in the server's {@code currentOp} and logs
     * @return the backend, with the client already open
     * @throws IllegalStateException    if the driver is not on the classpath
     * @throws IllegalArgumentException if the settings name no database
     */
    public static @NotNull MongoBackend open(@NotNull SqlSettings settings, @NotNull String clientName) {
        String uri = connectionString(settings);
        String databaseName = databaseName(settings, uri);
        if (databaseName == null || databaseName.isBlank()) {
            throw new IllegalArgumentException("No Mongo database named in " + settings
                    + ". Mongo has no default database: name it in the settings or in the"
                    + " connection string's path.");
        }

        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applicationName("exylia-" + clientName)
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(
                        SERVER_SELECTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        if (uri != null) {
            builder.applyConnectionString(new ConnectionString(uri));
        } else {
            String host = settings.host() != null ? settings.host() : "127.0.0.1";
            builder.applyToClusterSettings(cluster -> cluster.hosts(
                    List.of(new ServerAddress(host, settings.portOr(DEFAULT_PORT)))));
            String user = settings.user();
            if (user != null && !user.isBlank()) {
                String password = settings.password() != null ? settings.password() : "";
                // Authenticated against the database being used, not against
                // admin. That is where a per-plugin user is created in every
                // deployment guide worth following, and the driver's own
                // default is the connection string's path anyway.
                builder.credential(MongoCredential.createCredential(
                        user, databaseName, password.toCharArray()));
            }
        }
        if (settings.poolSize() > 0) {
            builder.applyToConnectionPoolSettings(pool -> pool.maxSize(settings.poolSize()));
        }

        MongoClient client;
        try {
            client = MongoClients.create(builder.build());
        } catch (NoClassDefFoundError missing) {
            // The driver is compileOnly, exactly like the JDBC ones, and comes
            // from the server's library loader. A missing one is a plugin.yml
            // that was not updated, and "NoClassDefFoundError: com/mongodb/..."
            // tells an operator nothing about what to do next.
            throw new IllegalStateException("The MongoDB driver (org.mongodb:mongodb-driver-sync)"
                    + " is not on the classpath. ExyliaLib does not bundle database drivers: add it"
                    + " to the libraries section of the plugin.yml so the server downloads it once"
                    + " and every plugin shares the same copy.", missing);
        }
        // Nothing is contacted here. The driver connects lazily, so a wrong host
        // surfaces on the first operation rather than now — unlike Hikari, which
        // opens a connection eagerly. Failing on prepare() instead is the same
        // moment in practice, since that is what a plugin calls on enable.
        return new MongoBackend(client, client.getDatabase(databaseName),
                "mongo " + (uri != null ? redact(uri) : settings.host() + ":"
                        + settings.portOr(DEFAULT_PORT)) + "/" + databaseName);
    }

    /**
     * The connection string to use, or {@code null} when there is none.
     *
     * <p>A {@code uri} property wins over the host, so an operator can paste
     * what their provider gave them into a config that also carries the
     * host-and-port fields a SQL engine needs.
     */
    private static @Nullable String connectionString(@NotNull SqlSettings settings) {
        String property = settings.properties().get("uri");
        if (property == null) {
            property = settings.properties().get("connection-string");
        }
        if (property != null && !property.isBlank()) {
            return property;
        }
        String host = settings.host();
        return host != null && (host.startsWith("mongodb://") || host.startsWith("mongodb+srv://"))
                ? host
                : null;
    }

    /**
     * The database to use: the settings' own, or the connection string's path
     * when the settings name none.
     */
    private static @Nullable String databaseName(@NotNull SqlSettings settings, @Nullable String uri) {
        String named = settings.database();
        if (named != null && !named.isBlank()) {
            return named;
        }
        return uri != null ? new ConnectionString(uri).getDatabase() : null;
    }

    /**
     * A connection string with its credentials removed.
     *
     * <p>This string ends up in {@link #toString}, which ends up in a debug
     * line on enable. A credential in a console log is a credential in whatever
     * pastebin the next support ticket links to.
     *
     * <p>Package-private rather than private: it is a test seam. A leak here is
     * invisible until it is in somebody's log, and no test that needs a server
     * would catch it.
     */
    static @NotNull String redact(@NotNull String uri) {
        int scheme = uri.indexOf("://");
        int credentials = uri.indexOf('@', scheme + 3);
        return credentials < 0 ? uri : uri.substring(0, scheme + 3) + "***" + uri.substring(credentials);
    }

    /** The database name this backend works in. */
    public @NotNull String databaseName() {
        return database.getName();
    }

    /**
     * Everything about a model Mongo cannot store the way it is written.
     *
     * @param model the record model
     * @return the problems, empty when there are none
     * @see MongoDocuments#validate
     */
    public @NotNull List<String> validate(@NotNull EntityModel<?> model) {
        return MongoDocuments.validate(model);
    }

    // ----------------------------------------------------------------- schema

    /**
     * Creates the collection behind a model and the indexes it asks for.
     *
     * <p>Idempotent, and safe when two servers start against one database at
     * the same moment: both losing sides of a race see the object they wanted
     * already there, which is all they wanted.
     *
     * <p>{@link SchemaReport#addedColumns()} is always empty and that is not an
     * omission. A document carries the fields it was written with and no
     * others, so a record that gains a component needs nothing done to the
     * documents already stored — they lack the field, and
     * {@link EntityModel#read} reads an absent field as absent. The equivalent
     * SQL backend has to run an {@code ALTER TABLE} for the same change.
     *
     * <p>The collection is created explicitly rather than left to appear on the
     * first write. Only so the report can be honest about it: an implicitly
     * created collection is indistinguishable from one that was always there,
     * and the report exists so that a console line means something happened.
     *
     * @param model the record model
     * @return what changed
     * @throws IllegalStateException if the server refused something other than
     *                               "already there"
     */
    public @NotNull SchemaReport prepare(@NotNull EntityModel<?> model) {
        String name = model.table();
        boolean created = false;
        if (!collectionExists(name)) {
            try {
                database.createCollection(name);
                created = true;
            } catch (MongoCommandException failure) {
                // NamespaceExists (48). Another server got there between the
                // listing and the create, which is the normal shape of two
                // servers starting together.
                if (failure.getErrorCode() != 48) {
                    throw new IllegalStateException("Could not create the collection " + name
                            + " in " + describe, failure);
                }
            }
        }
        return new SchemaReport(name, created, List.of(), createIndexes(model, created));
    }

    private boolean collectionExists(@NotNull String name) {
        for (String existing : database.listCollectionNames()) {
            if (existing.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates the indexes a model asks for, once.
     *
     * <p>{@code createIndex} is idempotent for a specification identical to the
     * existing one and throws {@code IndexOptionsConflict} for one that differs
     * — which happens for a real reason: a column that gained
     * {@code unique = true} between two releases asks for an index the server
     * already has under the same name without it. Reporting that and stopping
     * would leave the collection with an index that no longer means what the
     * record says it means, and every start would fail identically.
     *
     * <p>So a conflicting index is dropped and rebuilt, but only when this
     * library is the one that named it. The name is generated
     * ({@link MongoDocuments#indexName}), so an index called anything else was
     * created by an operator or another tool, and dropping somebody else's
     * index — possibly one covering a query this library knows nothing about,
     * possibly minutes of rebuild on a large collection — is not a decision a
     * schema step gets to make silently. That case is reported instead, naming
     * the index.
     *
     * <p>Rebuilding a unique index over data that violates it fails, and it
     * fails here rather than later: that is a collection with duplicates in a
     * column somebody just declared unique, and the alternative to failing is
     * pretending the constraint exists.
     *
     * @param fresh whether the collection was created a moment ago, in which
     *              case it provably has no indexes and the listing is a round
     *              trip for a known answer
     */
    private @NotNull List<String> createIndexes(@NotNull EntityModel<?> model, boolean fresh) {
        // One list, whether an index came from @Indexed on a component or from
        // @Index on the record: EntityModel unified them, so there is nothing to
        // merge here and no second mechanism to keep in step with the SQL side.
        List<IndexModel> wanted = model.indexes();
        if (wanted.isEmpty()) {
            return List.of();
        }
        MongoCollection<Document> collection = database.getCollection(model.table());
        Set<String> existing = fresh ? Set.of() : indexNames(collection);

        List<String> created = new ArrayList<>(wanted.size());
        for (IndexModel index : wanted) {
            Map<String, Integer> key = MongoDocuments.keySpec(model, index);
            if (existing.contains(index.name())) {
                // Present under our name. Whether its key and options still
                // match is decided below only when the server says they do not,
                // because asking costs a round trip per index on every start.
                if (!recreateIfConflicting(collection, index, key)) {
                    continue;
                }
                created.add(index.name());
                continue;
            }
            try {
                collection.createIndex(keys(key), options(index));
                created.add(index.name());
            } catch (MongoCommandException failure) {
                if (!isIndexConflict(failure)) {
                    throw new IllegalStateException("Could not create the index " + index.name()
                            + " on " + model.table() + " in " + describe, failure);
                }
                if (recreateIfConflicting(collection, index, key)) {
                    created.add(index.name());
                }
            }
        }
        return List.copyOf(created);
    }

    /**
     * The driver's key specification for one index.
     *
     * <p>{@code compoundIndex} of one ascending or descending field per column,
     * in key order. A single-column index goes through the same call rather than
     * a shortcut: {@code compoundIndex} of one field produces exactly the
     * document {@code Indexes.ascending} does, so there is one path to be right
     * about instead of two.
     */
    private static @NotNull org.bson.conversions.Bson keys(@NotNull Map<String, Integer> key) {
        List<org.bson.conversions.Bson> fields = new ArrayList<>(key.size());
        key.forEach((field, direction) -> fields.add(direction < 0
                ? Indexes.descending(field)
                : Indexes.ascending(field)));
        return Indexes.compoundIndex(fields);
    }

    /**
     * The options for one index.
     *
     * <p>Named rather than left to the driver, which would call a compound index
     * {@code kit_id_1_elo_-1}. The name mirrors what the SQL dialects generate,
     * so a {@link SchemaReport} reads the same whichever backend produced it, and
     * so an index this library created can be told apart from one an operator
     * added by hand — which matters, because a conflicting index is dropped and
     * rebuilt and only ours may be.
     */
    private static @NotNull IndexOptions options(@NotNull IndexModel index) {
        return new IndexOptions().name(index.name()).unique(index.unique());
    }

    /**
     * Drops and rebuilds an index whose key or options no longer match, when we
     * own it.
     *
     * <p>The whole key is compared, in order, and not merely its field names: a
     * release that changed {@code @Index(columns = {"kit_id", "elo"})} into
     * {@code {"elo", "kit_id"}}, or that moved which column is descending, asks
     * for a genuinely different index under the same name. Comparing only the
     * field set would leave the old one in place and the leaderboard sorting in
     * memory forever.
     *
     * @return whether the index was actually rebuilt
     */
    private boolean recreateIfConflicting(@NotNull MongoCollection<Document> collection,
                                          @NotNull IndexModel index,
                                          @NotNull Map<String, Integer> wanted) {
        Document current = indexNamed(collection, index.name());
        if (current == null) {
            return false;
        }
        boolean unique = Boolean.TRUE.equals(current.getBoolean("unique"));
        if (unique == index.unique() && sameKey(current.get("key", Document.class), wanted)) {
            return false;
        }
        collection.dropIndex(index.name());
        collection.createIndex(keys(wanted), options(index));
        return true;
    }

    /**
     * Whether a live index's key is the one wanted, field for field and in order.
     *
     * <p>The direction is read as a number because that is what the server
     * stores, and it may come back as any numeric type: a key written by this
     * library is an {@code int}, one written by a shell script may be a
     * {@code double}. Comparing the boxed values directly would report
     * {@code 1.0} as different from {@code 1} and rebuild a correct index on
     * every start.
     */
    private static boolean sameKey(@Nullable Document current, @NotNull Map<String, Integer> wanted) {
        if (current == null || current.size() != wanted.size()) {
            return false;
        }
        java.util.Iterator<Map.Entry<String, Integer>> expected = wanted.entrySet().iterator();
        for (Map.Entry<String, Object> actual : current.entrySet()) {
            Map.Entry<String, Integer> want = expected.next();
            if (!actual.getKey().equals(want.getKey())
                    || !(actual.getValue() instanceof Number direction)
                    || want.getValue() < 0 != direction.doubleValue() < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIndexConflict(@NotNull MongoCommandException failure) {
        int code = failure.getErrorCode();
        return code == INDEX_OPTIONS_CONFLICT || code == INDEX_KEY_SPECS_CONFLICT;
    }

    private static @NotNull Set<String> indexNames(@NotNull MongoCollection<Document> collection) {
        Set<String> names = new LinkedHashSet<>();
        for (Document index : collection.listIndexes()) {
            String name = index.getString("name");
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    private static @Nullable Document indexNamed(@NotNull MongoCollection<Document> collection,
                                                 @NotNull String name) {
        for (Document index : collection.listIndexes()) {
            if (name.equals(index.getString("name"))) {
                return index;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ write

    /**
     * Writes one record, inserting or replacing as needed.
     *
     * <p>One {@code replaceOne} with {@code upsert}, which is one round trip
     * and no race: reading to decide between an insert and an update would let
     * another thread write between the two, and the loser would either fail on
     * a duplicate key or overwrite what it just read.
     *
     * <p>A replace, not an update: the document becomes exactly what the record
     * says, so a field a record no longer declares disappears rather than
     * lingering forever. A {@code $set} would leave it, and the next read would
     * ignore it, so the only trace would be a collection that grows fields
     * nobody can explain.
     *
     * @param model    the record model
     * @param instance the record
     * @param <T>      the record type
     */
    public <T> void save(@NotNull EntityModel<T> model, @NotNull T instance) {
        Document document = document(MongoDocuments.toDocument(model, instance));
        collection(model).replaceOne(Filters.eq(MongoDocuments.ID_FIELD,
                        document.get(MongoDocuments.ID_FIELD)),
                document, new ReplaceOptions().upsert(true));
    }

    /**
     * Inserts a record under a key this backend hands out, and returns it.
     *
     * <p>MongoDB has no counter of its own, so one is kept in a collection of
     * counters and advanced with {@code findOneAndUpdate} plus {@code $inc}.
     * That is a single atomic document update returning the new value, which is
     * what makes it safe with several servers on one database — the pattern
     * MongoDB's own manual gives for exactly this.
     *
     * <p>Not {@code count() + 1}, which is the version of this that looks
     * simpler and hands the same id to two servers that insert in the same
     * instant, and hands a used one back after any delete.
     *
     * @param model    the record model, whose key must be generated
     * @param instance the record; its key value is ignored
     * @param <T>      the record type
     * @return the key assigned
     * @since 1.32.0
     */
    public <T> long insert(@NotNull EntityModel<T> model, @NotNull T instance) {
        long key = nextKey(model.table());
        Document document = document(MongoDocuments.toDocument(model, model.withId(instance, key)));
        collection(model).insertOne(document);
        return key;
    }

    /** The next value of one table's counter, advanced atomically. */
    private long nextKey(@NotNull String table) {
        Document counter = database.getCollection(COUNTERS)
                .findOneAndUpdate(Filters.eq(MongoDocuments.ID_FIELD, table),
                        new Document("$inc", new Document(COUNTER_FIELD, 1L)),
                        new FindOneAndUpdateOptions()
                                .upsert(true)
                                .returnDocument(ReturnDocument.AFTER));
        if (counter == null) {
            throw new IllegalStateException("Counter for " + table + " returned nothing after $inc");
        }
        return ((Number) counter.get(COUNTER_FIELD)).longValue();
    }

    /**
     * Writes many records in one batch.
     *
     * <p>One round trip per batch rather than per record, and unordered on
     * purpose: with {@code ordered(false)} the server attempts every document
     * and reports the ones it refused, while an ordered batch stops at the
     * first failure and leaves the rest unwritten. For a periodic flush of
     * every online player's stats, one malformed record must not cost the other
     * ninety-nine their save.
     *
     * <p>Which is also the difference a consumer notices against
     * {@link SqlBackend#saveAll}: that one is a transaction and rolls back, this
     * one is not and does not. The documents that succeeded are already durable
     * when this throws, and the message says how many.
     *
     * @param model     the record model
     * @param instances the records
     * @param <T>       the record type
     * @return how many documents the server accepted
     * @throws IllegalStateException if the server refused any document, after
     *                               every other one has been written
     */
    public <T> int saveAll(@NotNull EntityModel<T> model, @NotNull Collection<T> instances) {
        if (instances.isEmpty()) {
            return 0;
        }
        List<ReplaceOneModel<Document>> writes = new ArrayList<>(instances.size());
        for (T instance : instances) {
            Document document = document(MongoDocuments.toDocument(model, instance));
            writes.add(new ReplaceOneModel<>(
                    Filters.eq(MongoDocuments.ID_FIELD, document.get(MongoDocuments.ID_FIELD)),
                    document, new ReplaceOptions().upsert(true)));
        }
        try {
            collection(model).bulkWrite(writes, new BulkWriteOptions().ordered(false));
            return writes.size();
        } catch (MongoBulkWriteException partial) {
            List<BulkWriteError> errors = partial.getWriteErrors();
            StringBuilder message = new StringBuilder(128)
                    .append(errors.size()).append(" of ").append(writes.size())
                    .append(" documents were refused writing ").append(model.table())
                    .append(" to ").append(describe)
                    .append("; the rest were written. ");
            for (BulkWriteError error : errors) {
                message.append('[').append(error.getIndex()).append("] ")
                        .append(error.getMessage()).append(' ');
            }
            throw new IllegalStateException(message.toString().trim(), partial);
        }
    }

    /**
     * Deletes the document with a primary key.
     *
     * @param model the record model
     * @param id    the key, in its record form — it is encoded here, exactly as
     *              the column that stores it was, because a key encoded
     *              differently from its field matches nothing and Mongo reports
     *              that as "no such document" rather than as an error
     * @return whether a document was removed
     */
    public boolean delete(@NotNull EntityModel<?> model, @NotNull Object id) {
        return collection(model).deleteOne(
                Filters.eq(MongoDocuments.ID_FIELD, value(MongoDocuments.idValue(model, id))))
                .getDeletedCount() > 0;
    }

    /**
     * Deletes the documents matching a filter, at most a given number of them.
     *
     * <p>Without a limit this is one {@code deleteMany}. With one it is two
     * round trips and it is not atomic, because Mongo has no
     * {@code DELETE ... LIMIT}: the matching keys are read first, capped, and
     * then deleted by key.
     *
     * <p>The consequence is real and worth stating rather than hiding. Between
     * the two trips another thread can delete one of those documents — this
     * then removes fewer than the limit — or write a new one that would have
     * matched, which this will not touch. Neither is a correctness problem for
     * what a limited delete is actually for (trimming a log, expiring a batch),
     * and the alternative shapes are worse: a loop of single deletes is one
     * round trip per document, and {@code findOneAndDelete} in a loop is the
     * same. An unlimited delete has neither problem.
     *
     * @param model        the record model
     * @param whereColumns column or component names, compared with equality
     * @param whereValues  the values, in record form, one per column
     * @param limit        documents at most, {@code 0} or less for all of them
     * @return how many documents were removed
     */
    public long deleteWhere(@NotNull EntityModel<?> model,
                            @NotNull List<String> whereColumns,
                            @NotNull List<Object> whereValues,
                            int limit) {
        Document filter = document(MongoDocuments.filter(model, whereColumns, whereValues));
        MongoCollection<Document> collection = collection(model);
        if (limit <= 0) {
            return collection.deleteMany(filter).getDeletedCount();
        }
        List<Object> ids = new ArrayList<>(limit);
        // Only _id comes back. The documents are about to be deleted, so
        // pulling a serialised inventory over the wire to throw it away is the
        // whole cost of this operation for nothing.
        for (Document found : collection.find(filter)
                .projection(new Document(MongoDocuments.ID_FIELD, 1))
                .limit(limit)) {
            ids.add(found.get(MongoDocuments.ID_FIELD));
        }
        if (ids.isEmpty()) {
            return 0L;
        }
        return collection.deleteMany(Filters.in(MongoDocuments.ID_FIELD, ids)).getDeletedCount();
    }

    // ------------------------------------------------------------------- read

    /**
     * The record with a primary key, or {@code null} when there is none.
     *
     * <p>A lookup on {@code _id}, so it uses the unique index Mongo maintains
     * on every collection whether asked to or not. That is the entire reason
     * the key is stored there rather than under its own name.
     *
     * @param model the record model
     * @param id    the key, in its record form
     * @param <T>   the record type
     * @return the record, or {@code null}
     */
    public <T> @Nullable T find(@NotNull EntityModel<T> model, @NotNull Object id) {
        Document found = collection(model)
                .find(Filters.eq(MongoDocuments.ID_FIELD, value(MongoDocuments.idValue(model, id))))
                .first();
        return found != null ? read(model, found) : null;
    }

    /**
     * Documents matching a filter, ordered and paged.
     *
     * <h2>Why a deep page is slow</h2>
     * {@code skip(n)} is O(n): the server walks and discards the documents it
     * skips, so page ninety costs ninety pages of work. On a sorted field with
     * an index it walks the index rather than the documents, which is cheaper
     * but still linear. There is a constant-time alternative — remembering the
     * last value seen and filtering {@code elo < that} — but it cannot express
     * "page ninety", only "the page after this one", and the API here promises
     * an offset. So the offset is implemented as an offset, and a consumer
     * paging deeply into a large collection should know it is paying for it.
     *
     * @param model        the record model
     * @param whereColumns column or component names, compared with equality
     * @param whereValues  the values, in record form, one per column
     * @param order        sort fields, may be empty
     * @param limit        documents at most, {@code 0} or less for all of them
     * @param offset       documents skipped
     * @param <T>          the record type
     * @return the records, in the server's order when none was asked for
     */
    public <T> @NotNull List<T> select(@NotNull EntityModel<T> model,
                                       @NotNull List<String> whereColumns,
                                       @NotNull List<Object> whereValues,
                                       @NotNull List<Dialect.Sort> order,
                                       int limit,
                                       int offset) {
        var cursor = collection(model)
                .find(document(MongoDocuments.filter(model, whereColumns, whereValues)));
        if (!order.isEmpty()) {
            cursor = cursor.sort(document(MongoDocuments.sort(model, order)));
        }
        if (offset > 0) {
            cursor = cursor.skip(offset);
        }
        if (limit > 0) {
            cursor = cursor.limit(limit);
        }
        List<T> found = new ArrayList<>();
        for (Document document : cursor) {
            found.add(read(model, document));
        }
        return List.copyOf(found);
    }

    /**
     * How many documents match a filter.
     *
     * <p>{@code countDocuments}, which actually counts, rather than
     * {@code estimatedDocumentCount}, which reads the collection's metadata and
     * can be wrong after an unclean shutdown. A count a plugin shows a player
     * has to be the number of things there are.
     *
     * @param model        the record model
     * @param whereColumns column or component names, compared with equality
     * @param whereValues  the values, in record form, one per column
     * @return the count
     */
    public long count(@NotNull EntityModel<?> model,
                      @NotNull List<String> whereColumns,
                      @NotNull List<Object> whereValues) {
        return collection(model)
                .countDocuments(document(MongoDocuments.filter(model, whereColumns, whereValues)));
    }

    /**
     * Whether anything matches a filter.
     *
     * <p>A capped find rather than a count, and only {@code _id} comes back.
     * Counting to compare against zero makes the server walk every match, which
     * on a popular clan is thousands of documents to answer a question one
     * answers.
     *
     * @param model        the record model
     * @param whereColumns column or component names, compared with equality
     * @param whereValues  the values, in record form, one per column
     * @return whether at least one document matches
     */
    public boolean exists(@NotNull EntityModel<?> model,
                          @NotNull List<String> whereColumns,
                          @NotNull List<Object> whereValues) {
        return collection(model)
                .find(document(MongoDocuments.filter(model, whereColumns, whereValues)))
                .projection(new Document(MongoDocuments.ID_FIELD, 1))
                .limit(1)
                .first() != null;
    }

    // -------------------------------------------------------------- documents

    private @NotNull MongoCollection<Document> collection(@NotNull EntityModel<?> model) {
        return database.getCollection(model.table());
    }

    /**
     * Turns a plain map from {@link MongoDocuments} into a BSON document.
     *
     * <p>The last step before the driver, and only a type change: every
     * decision was already made. {@link BigDecimal} becomes
     * {@link Decimal128} here rather than in {@code MongoDocuments} because
     * that class must not name a BSON type, and a nested list is walked because
     * an {@code $and} filter carries its clauses in one.
     *
     * <p>Package-private rather than private: it is a test seam. This is the
     * one conversion the driver-free layer cannot check, and it is the one that
     * decides whether a balance survives as a decimal or as a binary double.
     */
    static @NotNull Document document(@NotNull Map<String, Object> values) {
        Document document = new Document();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            document.put(entry.getKey(), value(entry.getValue()));
        }
        return document;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Object value(@Nullable Object plain) {
        if (plain instanceof BigDecimal decimal) {
            // Through Decimal128, never through a double. Money is the only
            // reason a column is a BigDecimal, and a binary double is how 0.1
            // becomes 0.09999999999999999 in somebody's balance.
            return new Decimal128(decimal);
        }
        if (plain instanceof Map<?, ?> nested) {
            return document((Map<String, Object>) nested);
        }
        if (plain instanceof List<?> list) {
            List<Object> converted = new ArrayList<>(list.size());
            for (Object element : list) {
                converted.add(value(element));
            }
            return converted;
        }
        return plain;
    }

    /**
     * Reads one document into a record.
     *
     * <p>Field access goes through {@link MongoDocuments#read}, which is what
     * maps {@code _id} back to the key column's own name. Handing the raw
     * document to {@link EntityModel#read} instead would ask it for a field
     * called {@code uuid}, get {@code null}, and build a record with no key and
     * no complaint from anywhere.
     */
    static <T> @NotNull T read(@NotNull EntityModel<T> model, @NotNull Document document) {
        return MongoDocuments.read(model, field -> javaValue(document.get(field)));
    }

    /**
     * Turns a BSON value back into something {@link Coercions} understands.
     *
     * <p>Only {@link Decimal128} needs it. It extends {@link Number}, so
     * {@code Coercions} would already route it through
     * {@code new BigDecimal(number.toString())} correctly, but that is a parse
     * of a string this method skips, and it would silently do the wrong thing
     * if the driver's {@code toString} ever gained an exponent form.
     */
    private static @Nullable Object javaValue(@Nullable Object bson) {
        return bson instanceof Decimal128 decimal ? decimal.bigDecimalValue() : bson;
    }

    /**
     * Closes the client.
     *
     * <p>Every connection and every monitoring thread goes with it. Called when
     * the library disables: a client that outlives its plugin holds sockets and
     * threads that nothing will ever close, and its threads keep the plugin's
     * classloader alive with them.
     */
    @Override
    public void close() {
        client.close();
    }

    @Override
    public String toString() {
        return "MongoBackend[" + describe + "]";
    }
}
