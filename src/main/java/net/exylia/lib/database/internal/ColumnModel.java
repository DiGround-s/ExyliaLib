package net.exylia.lib.database.internal;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.exylia.lib.database.Codec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * One stored column, compiled once from one record component.
 *
 * <p>Everything expensive about a column — deciding how it is encoded, finding
 * its codec, resolving its accessor — is settled here at registration time. A
 * row afterwards is a {@code switch} over a field and a {@code MethodHandle}
 * call, which is what lets a query returning ten thousand rows cost ten
 * thousand encodes rather than ten thousand reflective lookups.
 *
 * <p>Immutable and shared. One instance serves every thread reading or writing
 * the table.
 *
 * @see EntityModel
 */
public final class ColumnModel {

    /**
     * One shared {@link Gson}. Documented as thread-safe, holds no per-call
     * state, and building one costs more than the encode it would perform.
     */
    private static final Gson GSON = new Gson();

    /**
     * How a value crosses the boundary between the record and the database.
     *
     * <p>Decided once, at compile time, from the component's declared type.
     * Deciding it per value — which is what ExyliaCommons did, with a chain of
     * {@code instanceof} and registry lookups on every field of every row — is
     * the same answer computed a million times.
     */
    enum Kind {

        /**
         * Handed to the driver untouched: numbers, booleans, {@code String},
         * {@code BigDecimal}. The driver already knows these types, and routing
         * them through text would lose the column's type in the schema and with
         * it the database's ability to compare or sum it.
         */
        DIRECT,

        /** Encoded to text by a {@link Codec}: {@code UUID}, enums, items, locations. */
        CODEC,

        /**
         * A list whose elements have a codec, written as a JSON array of the
         * encoded strings. This is ExyliaCommons' {@code serializeCollection}
         * format and it is not negotiable: the rows exist.
         */
        LIST_CODEC,

        /**
         * A list of values Gson already represents natively — strings, numbers,
         * booleans — written as Gson writes them.
         *
         * <p>Deliberately not the same as {@link #LIST_CODEC}. Commons only
         * built a string array when the element type had a registered
         * serializer, and fell through to {@code GSON.toJson(collection)}
         * otherwise, so a {@code List<Integer>} is stored as {@code [1,2]} and
         * not as {@code ["1","2"]}. Encoding those the tidier, uniform way
         * would make every existing list column of numbers unreadable.
         */
        LIST_JSON
    }

    private final String name;
    private final String component;
    private final Class<?> javaType;
    private final Class<?> storedType;
    private final int length;
    private final boolean nullable;
    private final boolean unique;
    private final boolean indexed;
    private final boolean id;

    private final Kind kind;
    private final Codec<Object> codec;
    private final Type genericType;
    private final MethodHandle accessor;

    ColumnModel(String name,
                String component,
                Class<?> javaType,
                int length,
                boolean nullable,
                boolean unique,
                boolean indexed,
                boolean id,
                Kind kind,
                @Nullable Codec<Object> codec,
                @Nullable Type genericType,
                MethodHandle accessor) {
        this.name = name;
        this.component = component;
        this.javaType = javaType;
        this.storedType = kind == Kind.DIRECT ? javaType : String.class;
        this.length = length;
        this.nullable = nullable;
        this.unique = unique;
        this.indexed = indexed;
        this.id = id;
        this.kind = kind;
        this.codec = codec;
        this.genericType = genericType;
        this.accessor = accessor;
    }

    /** The column name in the database. */
    public @NotNull String name() {
        return name;
    }

    /** The record component this column came from. */
    public @NotNull String component() {
        return component;
    }

    /** The type the record declares. */
    public @NotNull Class<?> javaType() {
        return javaType;
    }

    /**
     * The type the database actually sees.
     *
     * <p>{@link #javaType()} for a column the driver understands, and
     * {@code String} for everything a codec or a list encodes. This is what a
     * dialect maps to a SQL type: a {@code Location} column is a
     * {@code VARCHAR}, not whatever a dialect would invent for
     * {@code org.bukkit.Location}.
     */
    public @NotNull Class<?> storedType() {
        return storedType;
    }

    /**
     * How many characters a text column holds, or
     * {@link net.exylia.lib.database.Column#UNBOUNDED}.
     *
     * <p>Meaningless for a column whose {@link #storedType()} is not
     * {@code String}; compilation rejects a record that sets it on one.
     */
    public int length() {
        return length;
    }

    /** Whether the database accepts null in this column. */
    public boolean nullable() {
        return nullable;
    }

    /** Whether the database enforces uniqueness. */
    public boolean unique() {
        return unique;
    }

    /** Whether the column gets an index. */
    public boolean indexed() {
        return indexed;
    }

    /** Whether this column is the primary key. */
    public boolean id() {
        return id;
    }

    /**
     * Reads this column out of a record instance, already encoded.
     *
     * <p>The returned value is what the driver is handed: a number, a boolean,
     * a {@code String}, or {@code null}.
     *
     * <p>A {@code null} here is not necessarily an absent value. A codec
     * answering {@code null} means "this cannot be represented", and for at
     * least one built-in that is the normal case rather than a fault: an air
     * {@code ItemStack} encodes as absent, exactly as Commons wrote it, which
     * saves a row's worth of Base64 for every empty inventory slot.
     *
     * @param instance the record, never {@code null}
     * @return the stored form, possibly {@code null}
     * @throws IllegalStateException if the record's accessor threw
     */
    public @Nullable Object read(@NotNull Object instance) {
        Object value;
        try {
            value = accessor.invokeExact(instance);
        } catch (Throwable failure) {
            // A record accessor can only fail if it was overridden by hand, but
            // a Throwable from invokeExact carries no hint of which one, and a
            // bare stack trace inside MethodHandle internals names nothing.
            throw new IllegalStateException("Accessor " + component + "() failed", failure);
        }
        return encode(value);
    }

    /**
     * Turns a value of the declared type into its stored form.
     *
     * <p>Separate from {@link #read} so a query can encode a value it was
     * handed — a {@code where uuid = ?} needs the same encoding as the column
     * it filters, and a filter encoded differently from the column silently
     * matches nothing.
     *
     * @param value the value, possibly {@code null}
     * @return the stored form, possibly {@code null}
     */
    @SuppressWarnings("unchecked")
    public @Nullable Object encode(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        return switch (kind) {
            case DIRECT -> value;
            case CODEC -> codec.encode(value);
            case LIST_CODEC -> encodeCodecList((Collection<Object>) value);
            case LIST_JSON -> GSON.toJson(value);
        };
    }

    /**
     * Turns what a driver returned into a value the record constructor accepts.
     *
     * <p>Never throws for a bad value. A stored form a codec cannot read
     * becomes the type's absent value — {@code null}, or the zero for a
     * primitive — because one unreadable row out of a hundred thousand must not
     * fail the load that would have told somebody about it.
     *
     * @param stored what the driver returned, possibly {@code null}
     * @return a value assignable to {@link #javaType()}
     */
    public @Nullable Object decode(@Nullable Object stored) {
        return switch (kind) {
            case DIRECT -> Coercions.toJava(stored, javaType);
            case CODEC -> decodeCodec(stored);
            case LIST_CODEC, LIST_JSON -> decodeList(stored);
        };
    }

    private @Nullable Object decodeCodec(@Nullable Object stored) {
        String text = text(stored);
        if (text == null) {
            // Null and empty are the same absence. An empty string reaches a
            // codec only from a column somebody blanked by hand, and every
            // codec would answer null for it anyway, after doing the work.
            return Coercions.zeroOf(javaType);
        }
        Object decoded = codec.decode(text);
        return decoded != null ? decoded : Coercions.zeroOf(javaType);
    }

    /**
     * Commons' collection format: a JSON array of per-element encoded strings.
     *
     * <p>An element the codec cannot represent is dropped rather than written
     * as a null entry, which is what Commons did and what the reading side
     * therefore expects. It means a list can come back shorter than it went in;
     * that is preferable to a list with a hole in it that every consumer has to
     * null-check.
     */
    private @NotNull String encodeCodecList(@NotNull Collection<Object> values) {
        JsonArray array = new JsonArray(values.size());
        for (Object element : values) {
            if (element == null) {
                continue;
            }
            String encoded = codec.encode(element);
            if (encoded != null) {
                array.add(encoded);
            }
        }
        return GSON.toJson(array);
    }

    /**
     * Reads a list column back.
     *
     * <p>An absent or unreadable column yields an empty list, never
     * {@code null}. Commons stored an empty collection as {@code NULL} and
     * handed the field back as {@code null}, so every consumer of a list column
     * in the ecosystem either null-checks it or is a latent
     * {@link NullPointerException}. Reading absence as empty removes the
     * distinction rather than propagating it, and still reads every row Commons
     * wrote.
     */
    private @NotNull List<?> decodeList(@Nullable Object stored) {
        String text = text(stored);
        if (text == null) {
            return List.of();
        }
        try {
            if (kind == Kind.LIST_CODEC) {
                JsonArray array = GSON.fromJson(text, JsonArray.class);
                if (array == null) {
                    return List.of();
                }
                List<Object> decoded = new ArrayList<>(array.size());
                for (JsonElement element : array) {
                    if (!element.isJsonPrimitive()) {
                        continue;
                    }
                    Object value = codec.decode(element.getAsString());
                    if (value != null) {
                        decoded.add(value);
                    }
                }
                return List.copyOf(decoded);
            }
            List<?> parsed = GSON.fromJson(text, genericType);
            if (parsed == null) {
                return List.of();
            }
            // Gson happily produces nulls from a JSON null, and List.copyOf
            // refuses them; a record component holding a list with a hole in it
            // is worse than a shorter list either way.
            List<Object> cleaned = new ArrayList<>(parsed.size());
            for (Object element : parsed) {
                if (element != null) {
                    cleaned.add(element);
                }
            }
            return List.copyOf(cleaned);
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }

    /**
     * The stored form as text, or {@code null} when there is nothing to read.
     *
     * <p>{@code toString} rather than a cast: a Mongo document hands back
     * whatever type it was written with, and a MySQL {@code TEXT} column read
     * through some drivers arrives as a {@code char[]} or a {@code Clob}
     * wrapper rather than a {@code String}.
     */
    private static @Nullable String text(@Nullable Object stored) {
        if (stored == null) {
            return null;
        }
        String text = stored instanceof String string ? string : stored.toString();
        return text.isEmpty() ? null : text;
    }

    @Override
    public String toString() {
        return "ColumnModel[" + name + " " + javaType.getSimpleName() + " as " + kind + "]";
    }
}
