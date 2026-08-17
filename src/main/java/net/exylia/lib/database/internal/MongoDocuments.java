package net.exylia.lib.database.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Everything that turns a record into a Mongo document and back, with no Mongo
 * type anywhere in it.
 *
 * <p>Deliberately driver-free. {@link MongoBackend} is the one class in the
 * library allowed to name {@code com.mongodb} or {@code org.bson}, so a server
 * that never configures Mongo never loads the driver — the same confinement
 * PacketEvents gets in the effect and hologram modules. That confinement is
 * also what makes this layer testable: the interesting decisions here (which
 * field a column becomes, which BSON family a value lands in, what a filter or
 * an index spec looks like) are exactly the ones that would otherwise need a
 * running {@code mongod} to check, and there is no such thing on a build
 * machine.
 *
 * <p>The output types are the plain Java shapes that map one-to-one onto BSON
 * families. {@link MongoBackend} wraps them in {@code Document} and
 * {@code Decimal128} at the last possible moment, which is a type change and
 * not a decision.
 *
 * <pre>{@code
 * Map<String, Object> document = MongoDocuments.toDocument(model, stats);
 * // {_id=1f0..., elo=1840, kill_streak=7, ratio=1.75, banned=false}
 *
 * PlayerStats back = MongoDocuments.fromDocument(model, document);
 * }</pre>
 *
 * <h2>Threads</h2>
 * Stateless and safe from anywhere. Every method reads an immutable
 * {@link EntityModel} and allocates its own result.
 *
 * @see MongoBackend
 * @since 1.24.0
 */
public final class MongoDocuments {

    /**
     * The field Mongo addresses a document by.
     *
     * <p>The primary key column is stored here and nowhere else. Mongo indexes
     * {@code _id} uniquely on every collection whether asked to or not, so
     * writing the key under its own name too would mean carrying a second copy
     * of every UUID in the collection and paying for a second index to make it
     * findable — a lookup by key would otherwise be a full collection scan.
     */
    public static final String ID_FIELD = "_id";

    private MongoDocuments() {
    }

    // ------------------------------------------------------------------ names

    /**
     * The document field one column is stored under.
     *
     * @param column the column
     * @return {@link #ID_FIELD} for the primary key, the column name otherwise
     */
    public static @NotNull String fieldOf(@NotNull ColumnModel column) {
        return column.id() ? ID_FIELD : column.name();
    }

    /**
     * Resolves a name a caller filtered or sorted on to a real column.
     *
     * <p>A caller thinks in record components — {@code killStreak} — while the
     * document has {@code kill_streak}. Translating here rather than passing
     * the name through is what stops a filter from silently matching nothing:
     * Mongo does not complain about a field that does not exist, it simply
     * returns an empty result, so an untranslated name is a query that looks
     * like it worked and found no players.
     *
     * @param model the record model
     * @param name  a column name or a record component name
     * @return the column
     * @throws IllegalArgumentException if the model has neither
     */
    public static @NotNull ColumnModel columnOf(@NotNull EntityModel<?> model, @NotNull String name) {
        ColumnModel column = model.column(name);
        if (column == null) {
            column = model.byComponent(name);
        }
        if (column == null) {
            throw new IllegalArgumentException("No column or component '" + name + "' on "
                    + model.type().getSimpleName() + " (collection " + model.table() + ")");
        }
        return column;
    }

    // ------------------------------------------------------------------ write

    /**
     * One record as a document, ready to be handed to the driver.
     *
     * <p>The primary key lands under {@link #ID_FIELD}; every other column
     * lands under its own name. Values keep their type: an {@code int} is a
     * number, not the text of one. That is the entire reason this backend
     * exists in the shape it does — see {@link #bsonValue}.
     *
     * <p>A column whose stored form is {@code null} is left out of the document
     * rather than written as an explicit null. The two are indistinguishable to
     * a query ({@code {field: null}} matches both), indistinguishable to a
     * unique index (both count as one null), and indistinguishable on the way
     * back, since {@link EntityModel#read(Function)} already treats an absent
     * field as absent. Omitting is simply smaller, which matters for the
     * columns that are null most of the time — an empty inventory slot encodes
     * to nothing at all.
     *
     * @param model    the record model
     * @param instance the record
     * @param <T>      the record type
     * @return a fresh, mutable map in column order
     * @throws IllegalArgumentException if the record's key encodes to
     *                                  {@code null}, which no document can be
     *                                  addressed by
     */
    public static <T> @NotNull Map<String, Object> toDocument(@NotNull EntityModel<T> model,
                                                              @NotNull T instance) {
        Map<String, Object> document = new LinkedHashMap<>();
        for (ColumnModel column : model.columns()) {
            Object value = bsonValue(column.read(instance));
            if (value == null) {
                if (column.id()) {
                    throw new IllegalArgumentException(model.type().getSimpleName()
                            + " produced no value for its key column '" + column.name()
                            + "'. A document is addressed by _id and cannot be written without one.");
                }
                continue;
            }
            document.put(fieldOf(column), value);
        }
        return document;
    }

    /**
     * The stored form of a primary key, as the {@code _id} of a document.
     *
     * @param model the record model
     * @param id    the key in its record form — a {@code UUID}, not its text
     * @return the value to compare {@code _id} against
     * @throws IllegalArgumentException if the key encodes to {@code null}
     */
    public static @NotNull Object idValue(@NotNull EntityModel<?> model, @NotNull Object id) {
        Object encoded = bsonValue(model.id().encode(id));
        if (encoded == null) {
            throw new IllegalArgumentException("The key " + id + " encodes to nothing for "
                    + model.type().getSimpleName() + ", so no document can match it.");
        }
        return encoded;
    }

    /**
     * Narrows an already-encoded column value to the BSON family that stores it.
     *
     * <h2>Why this is not simply {@code toString}</h2>
     * A document could hold every value as text and round-trip perfectly,
     * because {@link Coercions} parses numbers back out of strings. It would
     * also make {@code $sort} on {@code elo} lexicographic — 9 above 10, 900
     * above 1000 — and leave every numeric index unable to answer a range
     * query. A leaderboard is the single most common thing a plugin asks a
     * database for, so the type is not a detail.
     *
     * <p>Three families have no exact BSON counterpart and are widened, not
     * stringified:
     * <ul>
     *   <li>{@code float} becomes a double. BSON has no 32-bit float. The
     *       widening is exact in both directions — every {@code float} has an
     *       exact {@code double} value and narrowing it back returns the same
     *       bits — so this loses nothing, unlike text, where {@code 0.1f}
     *       written as {@code "0.10000000149011612"} is a value nobody wrote.</li>
     *   <li>{@code short} and {@code byte} become int32. BSON has nothing
     *       smaller, and {@link Coercions} narrows them back on read.</li>
     *   <li>{@link BigDecimal} stays a {@code BigDecimal} here and becomes a
     *       {@code Decimal128} in {@link MongoBackend}. A money column routed
     *       through a binary double is how {@code 0.1} becomes
     *       {@code 0.09999999999999999}, and it is the only reason a column is
     *       a {@code BigDecimal} in the first place.</li>
     * </ul>
     *
     * <p>Everything a {@link net.exylia.lib.database.Codec} touched — a
     * {@code UUID}, an enum, an {@code ItemStack}, a list — is already a
     * {@code String} by the time it arrives here and stays one. In particular a
     * {@code UUID} is stored as its 36-character text and <em>not</em> as the
     * BSON UUID binary subtype: the codec layer above already produced a
     * string, the SQL backends store that same string, and a value that
     * round-trips through a different representation on one backend is a value
     * two backends disagree about.
     *
     * @param encoded what {@link ColumnModel#read} or {@link ColumnModel#encode}
     *                produced, possibly {@code null}
     * @return the value to put in the document, or {@code null} when there is
     *         nothing to store
     */
    public static @Nullable Object bsonValue(@Nullable Object encoded) {
        if (encoded == null) {
            return null;
        }
        if (encoded instanceof Float value) {
            return value.doubleValue();
        }
        if (encoded instanceof Short value) {
            return value.intValue();
        }
        if (encoded instanceof Byte value) {
            return value.intValue();
        }
        // Integer, Long, Double, Boolean, String and BigDecimal are already the
        // families BSON stores. Anything else is a codec's output, which is a
        // String by construction, or a type EntityModel would have refused.
        return encoded;
    }

    // ------------------------------------------------------------------- read

    /**
     * Builds a record out of a document addressed by field name.
     *
     * <p>The function is the raw document lookup; the {@code _id} redirect
     * happens here. That redirect is the one thing a caller must not forget:
     * ask the document for {@code uuid} and it answers {@code null}, because
     * the key was written to {@code _id}, and the record comes back with a null
     * key and no complaint from anywhere.
     *
     * <p>A field the document does not carry reads as absent, which is what a
     * document written before a plugin added a column looks like. Mongo is
     * schemaless, so this is the normal case rather than the exotic one: there
     * is no migration that adds a field to old documents, and none is needed.
     *
     * @param model the record model
     * @param field raw field access, answering {@code null} for an absent field
     * @param <T>   the record type
     * @return the record
     */
    public static <T> @NotNull T read(@NotNull EntityModel<T> model,
                                      @NotNull Function<String, Object> field) {
        String idColumn = model.id().name();
        return model.read(name -> field.apply(name.equals(idColumn) ? ID_FIELD : name));
    }

    /**
     * The same read, from a document already in hand.
     *
     * @param model    the record model
     * @param document the document, by field name
     * @param <T>      the record type
     * @return the record
     */
    public static <T> @NotNull T fromDocument(@NotNull EntityModel<T> model,
                                              @NotNull Map<String, Object> document) {
        return read(model, document::get);
    }

    // ----------------------------------------------------------------- filter

    /**
     * A query document for a set of equality filters.
     *
     * <p>Every value is encoded through the column that stores it, exactly as
     * the write path encoded it. A {@code UUID} compared as a {@code UUID}
     * object against a field holding its text matches nothing, and Mongo
     * reports that as an empty result rather than as an error — the failure
     * mode this method exists to prevent.
     *
     * <p>Two filters on the same field cannot both be top-level keys of one
     * document, so that case becomes an explicit {@code $and}. It is rare and
     * usually pointless with {@code =} on both sides, but a filter that quietly
     * dropped half of what a caller asked for would return rows the caller
     * meant to exclude.
     *
     * @param model   the record model
     * @param columns column or component names, compared with equality
     * @param values  the values, in record form, one per column
     * @return a fresh query document, empty when nothing was filtered
     * @throws IllegalArgumentException if the two lists differ in length, or a
     *                                  name is not on the model
     */
    public static @NotNull Map<String, Object> filter(@NotNull EntityModel<?> model,
                                                      @NotNull List<String> columns,
                                                      @NotNull List<Object> values) {
        if (columns.size() != values.size()) {
            throw new IllegalArgumentException("filter on " + model.table() + " got "
                    + columns.size() + " columns and " + values.size() + " values");
        }
        Map<String, Object> query = new LinkedHashMap<>();
        List<Map<String, Object>> repeated = null;
        for (int index = 0; index < columns.size(); index++) {
            ColumnModel column = columnOf(model, columns.get(index));
            String field = fieldOf(column);
            Object value = bsonValue(column.encode(values.get(index)));
            if (!query.containsKey(field)) {
                query.put(field, value);
                continue;
            }
            if (repeated == null) {
                repeated = new ArrayList<>(2);
            }
            repeated.add(one(field, value));
        }
        if (repeated == null) {
            return query;
        }
        List<Map<String, Object>> clauses = new ArrayList<>(query.size() + repeated.size());
        for (Map.Entry<String, Object> single : query.entrySet()) {
            clauses.add(one(single.getKey(), single.getValue()));
        }
        clauses.addAll(repeated);
        Map<String, Object> conjunction = new LinkedHashMap<>(2);
        conjunction.put("$and", clauses);
        return conjunction;
    }

    /**
     * A one-entry document.
     *
     * <p>Not {@link Map#of}, which throws on a null value. A filter for a null
     * value is legitimate — "everyone with no clan" — and Mongo reads it as
     * "the field is null or absent", which is exactly what the write path
     * produces for an unset column.
     */
    private static @NotNull Map<String, Object> one(@NotNull String field, @Nullable Object value) {
        Map<String, Object> single = new LinkedHashMap<>(2);
        single.put(field, value);
        return single;
    }

    /**
     * A sort document.
     *
     * <p>{@code 1} ascending, {@code -1} descending, in the order given, which
     * is the order Mongo applies them in.
     *
     * <p>Sorts on the record's names, translated here, and on {@code _id} when
     * the key is what is being sorted by. A sort naming a field the documents
     * do not have is not an error to Mongo: those documents sort as if the
     * field were null, all together, which is why the name is resolved against
     * the model first.
     *
     * @param model the record model
     * @param order the sorts, may be empty
     * @return a fresh sort document, empty when no order was asked for
     * @throws IllegalArgumentException if a sort names something not on the model
     */
    public static @NotNull Map<String, Object> sort(@NotNull EntityModel<?> model,
                                                    @NotNull List<Dialect.Sort> order) {
        Map<String, Object> sort = new LinkedHashMap<>(order.size() * 2);
        for (Dialect.Sort entry : order) {
            sort.put(fieldOf(columnOf(model, entry.column())), entry.descending() ? -1 : 1);
        }
        return sort;
    }

    // ---------------------------------------------------------------- indexes

    /**
     * The key specification of one index, as the driver builds it.
     *
     * <p>Field name to {@code 1} for ascending and {@code -1} for descending, in
     * key order — which is why the map is ordered and has to stay ordered. A
     * compound index whose fields arrive in a different order is a different
     * index: {@code {kit_id: 1, elo: -1}} answers "the top of this kit by elo,
     * highest first" and {@code {elo: -1, kit_id: 1}} does not.
     *
     * <p>Built here rather than in {@link MongoBackend} so it can be asserted
     * without a running {@code mongod}, which no build machine has. The backend
     * turns this into {@code Indexes.compoundIndex(...)}, which is a type change
     * and not a decision.
     *
     * <p>A field is addressed exactly as {@link #fieldOf} addresses it
     * everywhere else, so the primary key would be {@link #ID_FIELD} — although
     * in practice no index here ever covers it, since Mongo indexes
     * {@code _id} uniquely on every collection whether asked to or not.
     *
     * @param model the record model
     * @param index the compiled index
     * @return the key document, in key order
     */
    public static @NotNull Map<String, Integer> keySpec(@NotNull EntityModel<?> model,
                                                        @NotNull IndexModel index) {
        Map<String, Integer> key = new LinkedHashMap<>(index.parts().size() * 2);
        for (IndexModel.Part part : index.parts()) {
            key.put(fieldOf(columnOf(model, part.column())), part.descending() ? -1 : 1);
        }
        return key;
    }

    // --------------------------------------------------------------- validate

    /**
     * Everything about a model Mongo cannot store the way it is written.
     *
     * <p>Far shorter than the SQL list, because most of what a dialect has to
     * check does not exist here: there is no column type to map, no length to
     * respect, and no row-size ceiling. What is left is field names, where
     * Mongo is permissive about what it accepts and unforgiving about what it
     * then means.
     *
     * @param model the record model
     * @return the problems, empty when there are none
     */
    public static @NotNull List<String> validate(@NotNull EntityModel<?> model) {
        List<String> problems = new ArrayList<>(0);
        for (ColumnModel column : model.columns()) {
            String name = column.name();
            if (!column.id() && name.equals(ID_FIELD)) {
                problems.add("Column '" + name + "' is called _id but is not the @Id."
                        + " Mongo stores the key under _id, so the two would overwrite each other"
                        + " on every write.");
            }
            if (name.indexOf('.') >= 0) {
                // Accepted by the server since 3.6 and still a trap: every
                // query, sort and index spec addresses fields by dotted path,
                // so a field named a.b is unreachable by the only syntax there
                // is for reaching it.
                problems.add("Column '" + name + "' contains a dot. Mongo reads a dot as a path"
                        + " into a nested document, so this field cannot be filtered, sorted or"
                        + " indexed by name.");
            }
            if (name.startsWith("$")) {
                problems.add("Column '" + name + "' starts with $. Mongo reads a leading $ as an"
                        + " operator, so this field cannot appear in a query document.");
            }
            if (name.isBlank()) {
                problems.add("A column of " + model.type().getSimpleName() + " has a blank name.");
            }
        }
        return List.copyOf(problems);
    }
}
