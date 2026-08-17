package net.exylia.lib.database.internal;

import net.exylia.lib.database.Codec;
import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Index;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Table;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A record class compiled into everything the database module needs to store it.
 *
 * <p>Compiled once, when a repository is registered, and then immutable and
 * shared. A row afterwards costs one array allocation and one
 * {@link MethodHandle} call per column: no annotation is read, no type is
 * inspected, no codec is looked up, and nothing is decided that was already
 * decided at enable.
 *
 * <pre>{@code
 * EntityModel model = EntityModel.of(PlayerStats.class);
 *
 * // writing
 * Object[] values = model.values(stats);           // in column order, encoded
 *
 * // reading
 * PlayerStats loaded = model.read(row::get);       // by column name
 * PlayerStats loaded = model.read(values);         // in column order
 * }</pre>
 *
 * <h2>Why MethodHandles and not reflection</h2>
 * This is the hot path of every plugin on the server: a leaderboard is ten
 * thousand rows through this code, and a join is one write per player per
 * event. A {@code MethodHandle} bound at compile time and invoked with an exact
 * descriptor is a direct call the JIT can inline; {@code Field.get} and
 * {@code Method.invoke} are not, and each carries an access check and an
 * argument array of their own.
 *
 * <h2>Why it fails at registration, loudly</h2>
 * Every mistake this class can detect — a missing {@link Table}, two
 * {@link Id}s, a type nothing can store — is a mistake in code, not in data. It
 * is the same on every row and every server, so it is worth exactly one loud
 * failure at enable, where the developer is watching, rather than an exception
 * inside a background write three days later, where nobody is.
 *
 * <h2>Threads</h2>
 * Compilation is expected on the main thread at enable and is safe anywhere:
 * two threads compiling the same class produce two equal models and one wins
 * the cache. Every other method is read-only on immutable state and safe from
 * any thread, which it must be — reads and writes run on the background pool.
 *
 * @param <T> the record stored
 */
public final class EntityModel<T> {

    /**
     * Compiled models, keyed by class.
     *
     * <p>The lookup a repository does per operation, so it has to be a plain
     * map hit. Unbounded on purpose: the key is a loaded {@code Class} and a
     * plugin has a fixed, small number of them — a bounded cache here would
     * mean recompiling a record because a different one was used more recently.
     */
    private static final Map<Class<?>, EntityModel<?>> COMPILED = new ConcurrentHashMap<>();

    /**
     * Types the driver stores as themselves, with no codec in between.
     *
     * <p>They keep their SQL type in the schema, which is what lets the
     * database sort an {@code elo} column numerically and sum a
     * {@code coins} one. Encoding them as text would work and would quietly
     * make every {@code ORDER BY} lexicographic.
     */
    private static final Set<Class<?>> DIRECT_TYPES = Set.of(
            int.class, Integer.class,
            long.class, Long.class,
            double.class, Double.class,
            float.class, Float.class,
            short.class, Short.class,
            byte.class, Byte.class,
            boolean.class, Boolean.class,
            String.class, BigDecimal.class);

    private final Class<T> type;
    private final String table;
    private final List<ColumnModel> columns;
    private final Map<String, ColumnModel> byName;
    private final Map<String, ColumnModel> byComponent;
    private final ColumnModel id;

    /**
     * Every index the record asks for, however it asked.
     *
     * <p>One list, not two: {@link Indexed} on a component and {@link Index} on
     * the record both land here, so a schema layer has exactly one thing to
     * iterate. See {@link #indexes()}.
     */
    private final List<IndexModel> indexes;

    /**
     * The canonical constructor, adapted to {@code (Object[]) -> Object}.
     *
     * <p>Adapted once so that building a record is a single
     * {@code invokeExact} on an array the caller already had to build, rather
     * than a spread and a cast per row.
     */
    private final MethodHandle constructor;

    /**
     * Which constructor parameter each column fills, parallel to {@link #columns}.
     *
     * <p>Not simply the column's own index: a record may declare components
     * that are not columns, and each of them still occupies a parameter.
     * Resolved at compile time because the alternative is searching the
     * component array per column per row, and {@code getRecordComponents}
     * clones its array on every call.
     */
    private final int[] slots;

    /**
     * A constructor argument array with every slot already at its absent value.
     *
     * <p>Cloned per read rather than rebuilt: {@code clone} on a small
     * {@code Object[]} is an intrinsic, and it lands the zeros that skipped
     * primitive components need without inspecting anything per row.
     */
    private final Object[] blank;

    private EntityModel(Class<T> type,
                        String table,
                        List<ColumnModel> columns,
                        ColumnModel id,
                        List<IndexModel> indexes,
                        MethodHandle constructor,
                        int[] slots,
                        Object[] blank) {
        this.type = type;
        this.table = table;
        this.columns = List.copyOf(columns);
        this.id = id;
        this.indexes = List.copyOf(indexes);
        this.constructor = constructor;
        this.slots = slots;
        this.blank = blank;

        Map<String, ColumnModel> names = new HashMap<>(columns.size() * 2);
        Map<String, ColumnModel> components = new HashMap<>(columns.size() * 2);
        for (ColumnModel column : this.columns) {
            names.put(column.name(), column);
            components.put(column.component(), column);
        }
        this.byName = Map.copyOf(names);
        this.byComponent = Map.copyOf(components);
    }

    // ------------------------------------------------------------ compilation

    /**
     * The model for a record class, compiling it the first time it is asked for.
     *
     * @param type the record class
     * @param <T>  the record stored
     * @return the compiled model, never {@code null}
     * @throws IllegalArgumentException if the class cannot be stored, with a
     *                                  message naming exactly what is wrong
     */
    @SuppressWarnings("unchecked")
    public static <T> @NotNull EntityModel<T> of(@NotNull Class<T> type) {
        EntityModel<?> cached = COMPILED.get(type);
        if (cached != null) {
            return (EntityModel<T>) cached;
        }
        // Compiled outside computeIfAbsent: compilation of one record must not
        // hold the map's bin lock, and two threads racing here produce two
        // equal models of which one is simply discarded.
        EntityModel<T> compiled = compile(type);
        EntityModel<?> raced = COMPILED.putIfAbsent(type, compiled);
        return raced != null ? (EntityModel<T>) raced : compiled;
    }

    private static <T> EntityModel<T> compile(Class<T> type) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException(type.getName()
                    + " is not a record. The database module stores records: they are immutable,"
                    + " they have a canonical constructor, and their components are their columns.");
        }
        Table table = type.getAnnotation(Table.class);
        if (table == null) {
            throw new IllegalArgumentException(type.getName()
                    + " has no @Table. Name the table it is stored in, exactly as it exists in the database.");
        }
        if (table.value().isBlank()) {
            throw new IllegalArgumentException(type.getName() + " has a blank @Table name.");
        }

        MethodHandles.Lookup lookup = lookupIn(type);
        RecordComponent[] components = type.getRecordComponents();

        List<ColumnModel> columns = new ArrayList<>(components.length);
        Map<String, String> takenNames = new HashMap<>(components.length * 2);
        ColumnModel id = null;

        for (RecordComponent component : components) {
            Id key = component.getAnnotation(Id.class);
            Column column = component.getAnnotation(Column.class);
            if (key == null && column == null) {
                // Not an error. A record is allowed to carry something derived
                // or transient, and this is the only way to say so — the
                // alternative is a column nobody meant to create.
                continue;
            }
            if (key != null && column != null) {
                throw new IllegalArgumentException(where(type, component)
                        + " has both @Id and @Column. @Id already implies a column.");
            }

            ColumnModel model = column(type, component, lookup, key, column);
            String previous = takenNames.putIfAbsent(model.name(), model.component());
            if (previous != null) {
                throw new IllegalArgumentException(where(type, component)
                        + " maps to column '" + model.name() + "', which " + previous
                        + " already maps to. Two components cannot share a column:"
                        + " one would silently overwrite the other on every write.");
            }
            if (model.id()) {
                if (id != null) {
                    throw new IllegalArgumentException(type.getName()
                            + " has more than one @Id (" + id.component() + " and " + model.component()
                            + "). A row is addressed by exactly one key.");
                }
                id = model;
            }
            columns.add(model);
        }

        if (id == null) {
            throw new IllegalArgumentException(type.getName()
                    + " has no @Id. A table whose rows cannot be addressed individually"
                    + " can only be appended to, never updated or deleted.");
        }

        int[] slots = new int[columns.size()];
        for (int index = 0; index < columns.size(); index++) {
            slots[index] = slotOf(components, columns.get(index).component());
        }
        Object[] blank = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            blank[index] = Coercions.zeroOf(components[index].getType());
        }

        return new EntityModel<>(type, table.value(), columns, id,
                indexes(type, table.value(), columns),
                canonicalConstructor(type, lookup), slots, blank);
    }

    // ---------------------------------------------------------------- indexes

    /**
     * Compiles {@link Indexed} and {@link Index} into one list.
     *
     * <p>Component-level indexes come first, in declaration order, then the
     * record-level ones in the order they were written. A single-column
     * {@code @Indexed} is an {@link IndexModel} exactly like a composite one is,
     * which is what leaves the schema layers with a single mechanism instead of
     * two that have to be kept in step.
     *
     * <p>Everything wrong here is wrong in code and identical on every server,
     * so it fails at compilation, loudly, naming the record: an index over a
     * column that does not exist, a {@code descending} entry that is not one of
     * the index's own columns, two indexes under one name, or a composite index
     * over a column no database can index at all.
     */
    private static List<IndexModel> indexes(Class<?> type, String table, List<ColumnModel> columns) {
        List<IndexModel> indexes = new ArrayList<>(0);
        // Case-insensitively, because that is how the names end up in the
        // database: every identifier is folded to lower case before it is
        // quoted, so idx_A and idx_a are one index there and would collide.
        Map<String, String> takenNames = new HashMap<>();

        for (ColumnModel column : columns) {
            // The primary key is already indexed by every engine there is, and
            // a unique column gets its index from the uniqueness itself.
            if (!column.id() && (column.indexed() || column.unique())) {
                List<IndexModel.Part> parts = List.of(IndexModel.Part.asc(column.name()));
                IndexModel model = new IndexModel(IndexModel.derivedName(table, parts),
                        parts, column.unique());
                takenNames.put(model.name().toLowerCase(Locale.ROOT), "component " + column.component());
                indexes.add(model);
            }
        }

        for (Index declared : type.getAnnotationsByType(Index.class)) {
            IndexModel model = index(type, table, columns, declared);
            String previous = takenNames.putIfAbsent(model.name().toLowerCase(Locale.ROOT),
                    "@Index " + model.columns());
            if (previous != null) {
                throw new IllegalArgumentException(type.getName() + " declares two indexes named '"
                        + model.name() + "' (" + previous + " and @Index " + model.columns() + ")."
                        + " A second CREATE INDEX under a name that already exists reads as"
                        + " \"already there\" and is forgiven, so one of the two would simply never"
                        + " be created. Name one of them explicitly.");
            }
            indexes.add(model);
        }
        return indexes;
    }

    /**
     * Compiles one {@link Index} against the columns the record actually has.
     *
     * <p>Names are accepted as either the column name or the record component
     * name and normalised to the column name, because a developer writing an
     * index next to the components reads {@code kitId} while the database has
     * {@code kit_id}. Normalising here rather than passing the name through is
     * what keeps the {@code CREATE INDEX} and the metadata lookup addressing
     * the same column.
     */
    private static IndexModel index(Class<?> type,
                                    String table,
                                    List<ColumnModel> columns,
                                    Index declared) {
        if (declared.columns().length == 0) {
            throw new IllegalArgumentException(type.getName()
                    + " has an @Index over no columns. Name the columns it covers, in the order a"
                    + " query uses them: the ones it filters by first, the one it sorts by last.");
        }
        Set<String> descending = new LinkedHashSet<>(declared.descending().length * 2);
        for (String name : declared.descending()) {
            descending.add(resolve(type, columns, declared, name).name());
        }

        List<IndexModel.Part> parts = new ArrayList<>(declared.columns().length);
        Set<String> seen = new LinkedHashSet<>(declared.columns().length * 2);
        for (String name : declared.columns()) {
            ColumnModel column = resolve(type, columns, declared, name);
            if (!seen.add(column.name())) {
                throw new IllegalArgumentException(type.getName() + " has an @Index naming '"
                        + column.name() + "' twice " + java.util.Arrays.toString(declared.columns())
                        + ". A column appears in an index once; a second mention narrows nothing"
                        + " and no engine accepts it.");
            }
            if (declared.columns().length > 1 && column.length() == Column.UNBOUNDED
                    && column.storedType() == String.class && column.javaType() != UUID.class) {
                // A single-column index on an unbounded column is already
                // reported by the dialect, which knows the engine's own limit.
                // A composite one is refused here regardless of engine: there is
                // no length at which a LONGTEXT or TEXT column can be one part
                // of a multi-column key, so no engine can build this index.
                throw new IllegalArgumentException(type.getName() + " has a composite @Index "
                        + java.util.Arrays.toString(declared.columns()) + " covering '"
                        + column.name() + "', which is unbounded text. No database can index an"
                        + " unbounded column as part of a composite key. Give the column a length,"
                        + " or leave it out of the index.");
            }
            parts.add(new IndexModel.Part(column.name(), descending.contains(column.name())));
        }

        // Checked after the parts are built so the message can name what the
        // index does cover. A descending entry outside the index is either a
        // typo or a misunderstanding of what descending means here, and both
        // produce an index sorted the wrong way for the query it was written
        // for — which is invisible until the table is large.
        for (String name : descending) {
            if (!seen.contains(name)) {
                throw new IllegalArgumentException(type.getName() + " has an @Index marking '"
                        + name + "' descending, but the index covers " + seen
                        + ". Only a column the index itself lists can be sorted by it.");
            }
        }

        String name = declared.name().isBlank()
                ? IndexModel.derivedName(table, parts)
                : declared.name();
        return new IndexModel(name, parts, declared.unique());
    }

    /**
     * The column an {@link Index} entry names, by column name or component name.
     *
     * <p>Refused rather than skipped when there is no such column. An index
     * quietly built over fewer columns than were asked for is an index that does
     * not answer the query it exists for, and the only symptom is a table scan
     * on a live server.
     */
    private static ColumnModel resolve(Class<?> type,
                                       List<ColumnModel> columns,
                                       Index declared,
                                       String name) {
        for (ColumnModel column : columns) {
            if (column.name().equals(name) || column.component().equals(name)) {
                return column;
            }
        }
        List<String> known = new ArrayList<>(columns.size());
        for (ColumnModel column : columns) {
            known.add(column.name());
        }
        throw new IllegalArgumentException(type.getName() + " has an @Index "
                + java.util.Arrays.toString(declared.columns()) + " naming '" + name
                + "', which is neither a column nor a component of the record. It has: " + known
                + ". A component with no @Column is not stored, so it cannot be indexed.");
    }

    private static int slotOf(RecordComponent[] components, String name) {
        for (int index = 0; index < components.length; index++) {
            if (components[index].getName().equals(name)) {
                return index;
            }
        }
        throw new IllegalStateException("No component named " + name);
    }

    /**
     * Compiles one component into a column.
     *
     * <p>Exactly one of {@code key} and {@code column} is non-null; the caller
     * has already rejected both and neither.
     */
    private static ColumnModel column(Class<?> owner,
                                      RecordComponent component,
                                      MethodHandles.Lookup lookup,
                                      @Nullable Id key,
                                      @Nullable Column column) {
        boolean isId = key != null;
        String declared = isId ? key.value() : column.value();
        String name = declared.isEmpty() ? component.getName() : declared;
        int length = isId ? key.length() : column.length();
        // A key is by definition present and by definition unique; saying so on
        // the annotation would let somebody declare a nullable primary key,
        // which no database accepts anyway.
        boolean nullable = !isId && column.nullable();
        boolean unique = isId || column.unique();
        // A primary key is already indexed by every engine there is; asking for
        // a second index on it would create a redundant one.
        boolean indexed = !isId && component.isAnnotationPresent(Indexed.class);

        Class<?> javaType = component.getType();
        ColumnModel.Kind kind = kindOf(owner, component, javaType);
        Codec<Object> codec = codecFor(owner, component, javaType, kind);
        Type generic = kind == ColumnModel.Kind.LIST_JSON ? component.getGenericType() : null;

        if (kind == ColumnModel.Kind.DIRECT && javaType != String.class && lengthWasSet(isId, key, column)) {
            throw new IllegalArgumentException(where(owner, component)
                    + " sets length on a " + javaType.getSimpleName()
                    + " column. Length describes how much text a column holds and means nothing here.");
        }
        if (isId && length == Column.UNBOUNDED) {
            throw new IllegalArgumentException(where(owner, component)
                    + " asks for an unbounded key. A database cannot index an unbounded text column,"
                    + " and a primary key must be indexed.");
        }

        return new ColumnModel(name, component.getName(), javaType, length, nullable, unique, indexed,
                isId, kind, codec, generic, accessor(owner, component, lookup));
    }

    /**
     * Whether the annotation carries a length the developer actually wrote.
     *
     * <p>An annotation cannot say whether a value is the default, so the
     * default is what stands in for "not set". It means a component that sets
     * {@code length = 255} explicitly on an {@code int} is not reported — which
     * is a far better trade than reporting every {@code @Column int} in the
     * ecosystem for a length nobody wrote.
     */
    private static boolean lengthWasSet(boolean isId, @Nullable Id key, @Nullable Column column) {
        return isId ? key.length() != 64 : column.length() != 255;
    }

    private static ColumnModel.Kind kindOf(Class<?> owner, RecordComponent component, Class<?> javaType) {
        if (DIRECT_TYPES.contains(javaType)) {
            return ColumnModel.Kind.DIRECT;
        }
        if (Collection.class.isAssignableFrom(javaType)) {
            return listKind(owner, component, javaType);
        }
        if (javaType == UUID.class || javaType.isEnum() || CodecRegistry.has(javaType)) {
            return ColumnModel.Kind.CODEC;
        }
        throw new IllegalArgumentException(where(owner, component)
                + " is a " + javaType.getName() + ", which nothing knows how to store."
                + " Register a codec for it with Databases.codec(" + javaType.getSimpleName()
                + ".class, ...), or leave the component unannotated so it is not a column.");
    }

    /**
     * Decides how a collection column is stored, and refuses the shapes that
     * cannot be read back.
     *
     * <p>Only {@link List} is accepted. A {@code Set} would round-trip through
     * the same JSON array and come back with its order and its duplicates
     * decided by the decoder rather than by the writer, and Commons' own reader
     * turned every stored collection into whatever the declared type was, so
     * the rows carry no evidence of which it was. {@code List} is what the
     * ecosystem declares.
     */
    private static ColumnModel.Kind listKind(Class<?> owner, RecordComponent component, Class<?> javaType) {
        if (javaType != List.class) {
            throw new IllegalArgumentException(where(owner, component)
                    + " is a " + javaType.getSimpleName()
                    + ". Collection columns are stored as a JSON array and read back as a List;"
                    + " declare the component as List.");
        }
        Class<?> element = elementType(owner, component);
        if (CodecRegistry.has(element) || element.isEnum()) {
            return ColumnModel.Kind.LIST_CODEC;
        }
        if (element == String.class || DIRECT_TYPES.contains(element)) {
            return ColumnModel.Kind.LIST_JSON;
        }
        throw new IllegalArgumentException(where(owner, component)
                + " is a List of " + element.getName() + ", and nothing knows how to store one of those."
                + " Register a codec for the element type with Databases.codec("
                + element.getSimpleName() + ".class, ...).");
    }

    /**
     * The declared element type of a list component.
     *
     * <p>A raw or wildcard {@code List} is rejected rather than guessed: the
     * element type decides the wire format, so guessing it wrong writes rows
     * the reader cannot parse.
     */
    private static Class<?> elementType(Class<?> owner, RecordComponent component) {
        if (component.getGenericType() instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length == 1 && arguments[0] instanceof Class<?> element) {
                return element;
            }
        }
        throw new IllegalArgumentException(where(owner, component)
                + " is a List with no concrete element type. The element type decides how the column is"
                + " written, so it has to be stated: List<String>, not List or List<?>.");
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Codec<Object> codecFor(Class<?> owner,
                                                    RecordComponent component,
                                                    Class<?> javaType,
                                                    ColumnModel.Kind kind) {
        Class<?> target = switch (kind) {
            case DIRECT, LIST_JSON -> null;
            case CODEC -> javaType;
            case LIST_CODEC -> elementType(owner, component);
        };
        if (target == null) {
            return null;
        }
        Codec<Object> codec = (Codec<Object>) CodecRegistry.find(target);
        if (codec == null) {
            // Unreachable through kindOf, which asked the same question. Kept
            // because the two would otherwise have to stay in step by memory,
            // and a null codec surfaces as a NullPointerException on a
            // background thread months later.
            throw new IllegalArgumentException(where(owner, component)
                    + " needs a codec for " + target.getName() + " and there is none.");
        }
        return codec;
    }

    /**
     * The accessor for one component, adapted to {@code (Object) -> Object}.
     *
     * <p>Adapting once is what makes {@link ColumnModel#read} a single
     * {@code invokeExact}: an exact descriptor is a direct call, while an
     * {@code invoke} would re-derive the conversion on every row.
     */
    private static MethodHandle accessor(Class<?> owner,
                                         RecordComponent component,
                                         MethodHandles.Lookup lookup) {
        try {
            return lookup.unreflect(component.getAccessor())
                    .asType(MethodType.methodType(Object.class, Object.class));
        } catch (IllegalAccessException inaccessible) {
            throw new IllegalArgumentException(where(owner, component)
                    + " cannot be read. The record and its module must be readable from ExyliaLib:"
                    + " make the record public, or open its package.", inaccessible);
        }
    }

    /**
     * The canonical constructor, adapted to {@code (Object[]) -> Object}.
     *
     * <p>The generic adaptation is what unboxes: a column read as an
     * {@code Integer} becomes the {@code int} the constructor wants without
     * this class knowing which components are primitive.
     */
    private static MethodHandle canonicalConstructor(Class<?> type, MethodHandles.Lookup lookup) {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameters = new Class<?>[components.length];
        for (int index = 0; index < components.length; index++) {
            parameters[index] = components[index].getType();
        }
        try {
            MethodHandle handle = lookup.unreflectConstructor(type.getDeclaredConstructor(parameters));
            return handle.asType(handle.type().generic()).asSpreader(Object[].class, components.length);
        } catch (NoSuchMethodException | IllegalAccessException unusable) {
            throw new IllegalArgumentException(type.getName()
                    + " has no reachable canonical constructor. A record that declares one by hand must"
                    + " keep it public, and the record's package must be readable from ExyliaLib.",
                    unusable);
        }
    }

    /**
     * A lookup that can actually reach the record.
     *
     * <p>{@link MethodHandles#lookup()} carries <em>this</em> class's access
     * rights, which reach a public record in an exported package and nothing
     * else. A plugin's entity is very often neither: package-private next to
     * its repository, or nested inside the class that owns it.
     * {@link MethodHandles#privateLookupIn} borrows the record's own rights,
     * which is what the JDK's own record serialisation does.
     *
     * <p>It needs the record's module to be open to ours. A plugin is loaded
     * from the classpath into the unnamed module, which is open by definition,
     * so this succeeds for every record a plugin can hand us. The fallback
     * exists for the one case it does not — a record from a real, closed
     * module — where a public record still works.
     */
    private static MethodHandles.Lookup lookupIn(Class<?> type) {
        try {
            return MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        } catch (IllegalAccessException closed) {
            return MethodHandles.lookup();
        }
    }

    private static String where(Class<?> owner, RecordComponent component) {
        return owner.getName() + "." + component.getName();
    }

    // -------------------------------------------------------------- structure

    /** The record class this model was compiled from. */
    public @NotNull Class<T> type() {
        return type;
    }

    /** The table or collection name, exactly as {@link Table} stated it. */
    public @NotNull String table() {
        return table;
    }

    /**
     * Every stored column, in declaration order.
     *
     * <p>The order is the record's, and it is the order of every {@code Object[]}
     * this class produces or accepts. It is stable for a given class, which is
     * what lets a statement be prepared once and reused.
     */
    public @NotNull List<ColumnModel> columns() {
        return columns;
    }

    /** The primary key column. Never {@code null}: compilation refuses a record without one. */
    public @NotNull ColumnModel id() {
        return id;
    }

    /**
     * Every index this record asks for, in one list.
     *
     * <p>Component-level {@link Indexed} first, in declaration order, then the
     * record-level {@link Index} declarations in the order they were written. A
     * single-column index and a composite one are the same type here on purpose:
     * the schema layers iterate this and nothing else, so there is no second
     * mechanism to keep in step.
     *
     * <p>The primary key is never among them — every engine indexes it already,
     * and a second index over the same column costs a write on every insert and
     * answers nothing the first does not.
     *
     * @return the indexes, empty when the record asks for none
     */
    public @NotNull List<IndexModel> indexes() {
        return indexes;
    }

    /**
     * A column by its database name, or {@code null} when there is none.
     *
     * @param name the column name
     * @return the column, or {@code null}
     */
    public @Nullable ColumnModel column(@NotNull String name) {
        return byName.get(name);
    }

    /**
     * A column by its record component name, or {@code null} when there is none.
     *
     * <p>This is the lookup a typed query uses: a caller filtering on
     * {@code "killStreak"} means the component, and the column may well be
     * named {@code kill_streak}.
     *
     * @param component the record component name
     * @return the column, or {@code null}
     */
    public @Nullable ColumnModel byComponent(@NotNull String component) {
        return byComponent.get(component);
    }

    // ------------------------------------------------------------------ write

    /**
     * Every column of one record, encoded, in column order.
     *
     * <p>The array is fresh and belongs to the caller: it is what gets bound to
     * a statement, and a shared one would be rewritten underneath a batch still
     * being built.
     *
     * @param instance the record, never {@code null}
     * @return the values, in the order of {@link #columns()}
     */
    public @NotNull Object[] values(@NotNull T instance) {
        Object[] values = new Object[columns.size()];
        for (int index = 0; index < values.length; index++) {
            values[index] = columns.get(index).read(instance);
        }
        return values;
    }

    /**
     * The encoded primary key of one record.
     *
     * <p>Every update and delete needs it on its own, and pulling it out of a
     * full {@link #values} array would encode every other column to throw them
     * away — for a record holding a serialised inventory, that is a Base64
     * encode of the whole thing to build a {@code DELETE}.
     *
     * @param instance the record, never {@code null}
     * @return the stored form of the key
     */
    public @Nullable Object idOf(@NotNull T instance) {
        return id.read(instance);
    }

    /**
     * Every column of one record, encoded, by column name.
     *
     * <p>For the stores that address values by name rather than by position —
     * a Mongo document is written this way — where an ordered array would have
     * to be zipped back against the column list.
     *
     * @param instance the record, never {@code null}
     * @return a fresh map from column name to stored form
     */
    public @NotNull Map<String, Object> valuesByName(@NotNull T instance) {
        Map<String, Object> values = new HashMap<>(columns.size() * 2);
        for (ColumnModel column : columns) {
            values.put(column.name(), column.read(instance));
        }
        return values;
    }

    // ------------------------------------------------------------------- read

    /**
     * Builds a record from values in column order.
     *
     * <p>The array must be as long as {@link #columns()}; a shorter one is a
     * caller that built it from something other than this model, which is a bug
     * worth failing on rather than padding over.
     *
     * @param stored the driver's values, in column order
     * @return the record
     * @throws IllegalArgumentException if the array is the wrong length
     * @throws IllegalStateException    if the record's constructor rejected the row
     */
    public @NotNull T read(@NotNull Object[] stored) {
        if (stored.length != columns.size()) {
            throw new IllegalArgumentException("Expected " + columns.size() + " values for "
                    + type.getSimpleName() + ", got " + stored.length);
        }
        Object[] arguments = blank.clone();
        for (int index = 0; index < columns.size(); index++) {
            arguments[slots[index]] = columns.get(index).decode(stored[index]);
        }
        return construct(arguments);
    }

    /**
     * Builds a record from a row addressed by column name.
     *
     * <p>The function is asked for each column once and may answer {@code null}
     * for any of them, which is what a row from a table missing a column the
     * record has just gained looks like. Those columns come back as the type's
     * absent value rather than failing the read: a plugin that added a column
     * must still be able to read the rows written before it did.
     *
     * @param row the row, by column name
     * @return the record
     * @throws IllegalStateException if the record's constructor rejected the row
     */
    public @NotNull T read(@NotNull Function<String, Object> row) {
        Object[] arguments = blank.clone();
        for (int index = 0; index < columns.size(); index++) {
            ColumnModel column = columns.get(index);
            arguments[slots[index]] = column.decode(row.apply(column.name()));
        }
        return construct(arguments);
    }

    private T construct(Object[] arguments) {
        try {
            @SuppressWarnings("unchecked")
            T instance = (T) constructor.invokeExact(arguments);
            return instance;
        } catch (Throwable failure) {
            // Almost always a compact constructor rejecting a value: a record
            // that validates its own invariants meets a row written before the
            // validation existed. The class name is the only clue the caller
            // gets from the throwable itself, so it goes in the message.
            throw new IllegalStateException("Could not build " + type.getName() + " from a stored row",
                    failure);
        }
    }

    /**
     * Test seam: forgets every compiled model.
     *
     * <p>Package-private. Nothing in production should discard a model — a
     * record's shape cannot change while the JVM runs — but a test that
     * registers a codec and then compiles a record against it must not leave
     * that model behind for the next one.
     */
    static void forgetCompiled() {
        COMPILED.clear();
    }

    @Override
    public String toString() {
        return "EntityModel[" + type.getSimpleName() + " -> " + table + ", "
                + columns.size() + " columns]";
    }
}
